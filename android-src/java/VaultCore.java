package com.pasuyolabs.keyvault;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Everything the native side of KeyVault shares:
 *  - the encrypted vault copy that the Autofill service reads (same format the app writes),
 *  - opening it with the master password (PBKDF2-SHA256 + AES-256-GCM, identical to the app),
 *  - fingerprint unlock: the vault key wrapped by an Android Keystore key that needs a fingerprint,
 *  - matching saved accounts to the app or website asking for a login.
 * Nothing here ever writes a password in plain text to disk.
 */
final class VaultCore {
    static final String FILE = "kv_vault.json";
    static final String PREFS = "keyvault_native";
    static final String BIO_ALIAS = "keyvault_bio_v1";
    static final int MAX_TRIES = 5;
    static final long WAIT_MS = 30_000L;

    private VaultCore() {}

    /* ---------------- encrypted vault copy ---------------- */

    static File vaultFile(Context c) { return new File(c.getFilesDir(), FILE); }

    static boolean hasVault(Context c) { return vaultFile(c).exists(); }

    static void store(Context c, String json) throws IOException {
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(json.getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        }
        if (!tmp.renameTo(vaultFile(c))) throw new IOException("Could not save the vault copy");
    }

    static JSONObject readRecord(Context c) throws IOException, JSONException {
        File f = vaultFile(c);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        vaultFile(c).delete();
        bioDisable(c);
        prefs(c).edit().remove("fails").remove("until").apply();
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /* ---------------- crypto (must match index.html) ---------------- */

    static byte[] unb64(String s) { return Base64.decode(s, Base64.DEFAULT); }

    static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }

    /** PBKDF2-HMAC-SHA256, one 32-byte block, password as UTF-8 (same as WebCrypto). */
    static byte[] pbkdf2(byte[] pw, byte[] salt, int iter) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pw, "HmacSHA256"));
        mac.update(salt);
        mac.update(new byte[] {0, 0, 0, 1});
        byte[] u = mac.doFinal();
        byte[] out = u.clone();
        for (int i = 1; i < iter; i++) {
            mac.update(u);
            mac.doFinal(u, 0);
            for (int j = 0; j < out.length; j++) out[j] ^= u[j];
        }
        return out;
    }

    static byte[] gcmOpen(byte[] key, JSONObject box) throws GeneralSecurityException, JSONException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, unb64(box.getString("iv"))));
        return c.doFinal(unb64(box.getString("ct")));
    }

    static void checkRecord(JSONObject rec) throws JSONException {
        if (rec == null || rec.optInt("v") != 2) throw new JSONException("Not a KeyVault v2 vault");
        int iter = rec.getInt("iter");
        if (iter < 100_000 || iter > 5_000_000) throw new JSONException("Bad iteration count");
    }

    /** Master password -> vault key. Throws AEADBadTagException when the password is wrong. */
    static byte[] dekFromPassword(JSONObject rec, String pw) throws GeneralSecurityException, JSONException {
        checkRecord(rec);
        byte[] kek = pbkdf2(pw.getBytes(StandardCharsets.UTF_8), unb64(rec.getString("salt")), rec.getInt("iter"));
        try {
            return gcmOpen(kek, rec.getJSONObject("pw"));
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    static JSONArray entries(JSONObject rec, byte[] dek) throws GeneralSecurityException, JSONException {
        byte[] pt = gcmOpen(dek, rec.getJSONObject("data"));
        JSONObject data = new JSONObject(new String(pt, StandardCharsets.UTF_8));
        Arrays.fill(pt, (byte) 0);
        JSONArray a = data.optJSONArray("entries");
        return a == null ? new JSONArray() : a;
    }

    /* ---------------- wrong-password wait (shared by the Autofill unlock screen) ---------------- */

    static long waitLeftMs(Context c) { return Math.max(0, prefs(c).getLong("until", 0) - System.currentTimeMillis()); }

    static void wrongTry(Context c) {
        SharedPreferences p = prefs(c);
        int n = p.getInt("fails", 0) + 1;
        SharedPreferences.Editor e = p.edit();
        if (n >= MAX_TRIES) { e.putInt("fails", 0).putLong("until", System.currentTimeMillis() + WAIT_MS); }
        else e.putInt("fails", n);
        e.apply();
    }

    static void goodTry(Context c) { prefs(c).edit().remove("fails").remove("until").apply(); }

    /* ---------------- fingerprint ---------------- */

    /** "ok", "none" (no fingerprint enrolled) or "unsupported". */
    @SuppressWarnings("deprecation")
    static String bioAvailability(Context c) {
        if (Build.VERSION.SDK_INT < 28) return "unsupported";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                BiometricManager bm = c.getSystemService(BiometricManager.class);
                if (bm == null) return "unsupported";
                int r = Build.VERSION.SDK_INT >= 30
                        ? bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        : bm.canAuthenticate();
                if (r == BiometricManager.BIOMETRIC_SUCCESS) return "ok";
                if (r == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) return "none";
                return "unsupported";
            }
            FingerprintManager fm = (FingerprintManager) c.getSystemService(Context.FINGERPRINT_SERVICE);
            if (fm == null || !fm.isHardwareDetected()) return "unsupported";
            return fm.hasEnrolledFingerprints() ? "ok" : "none";
        } catch (Exception e) {
            return "unsupported";
        }
    }

    static boolean bioEnabled(Context c) { return prefs(c).contains("bio_ct") && prefs(c).contains("bio_iv"); }

    static void bioDisable(Context c) {
        prefs(c).edit().remove("bio_ct").remove("bio_iv").apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(BIO_ALIAS)) ks.deleteEntry(BIO_ALIAS);
        } catch (Exception ignored) { }
    }

    @TargetApi(28)
    static SecretKey newBioKey() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(BIO_ALIAS)) ks.deleteEntry(BIO_ALIAS);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(BIO_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        return kg.generateKey();
    }

    @TargetApi(28)
    static Cipher bioEncryptCipher() throws GeneralSecurityException, IOException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, newBioKey());
        return c;
    }

    /** null when fingerprint unlock is off. Throws KeyPermanentlyInvalidatedException after new fingerprints are added. */
    @TargetApi(28)
    static Cipher bioDecryptCipher(Context ctx) throws GeneralSecurityException, IOException {
        String iv = prefs(ctx).getString("bio_iv", null);
        if (iv == null) return null;
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Key k = ks.getKey(BIO_ALIAS, null);
        if (!(k instanceof SecretKey)) return null;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, unb64(iv)));
        return c;
    }

    static void bioSave(Context c, byte[] iv, byte[] wrapped) {
        prefs(c).edit().putString("bio_iv", b64(iv)).putString("bio_ct", b64(wrapped)).apply();
    }

    static byte[] bioWrapped(Context c) {
        String s = prefs(c).getString("bio_ct", null);
        return s == null ? null : unb64(s);
    }

    interface BioDone { void done(Cipher unlocked, String error); }

    /** Shows the system fingerprint sheet. Must be called on the main thread. */
    @TargetApi(28)
    static void bioPrompt(Activity a, Cipher cipher, String title, String subtitle, BioDone cb) {
        final AtomicBoolean once = new AtomicBoolean(false);
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(a)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButton("Use master password", a.getMainExecutor(), (d, w) -> {
                    if (once.compareAndSet(false, true)) cb.done(null, "cancel");
                });
        if (Build.VERSION.SDK_INT >= 29) b.setConfirmationRequired(false);
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        b.build().authenticate(new BiometricPrompt.CryptoObject(cipher), new CancellationSignal(), a.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        if (once.compareAndSet(false, true)) cb.done(result.getCryptoObject().getCipher(), null);
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        if (once.compareAndSet(false, true)) cb.done(null, msg == null ? "cancel" : msg.toString());
                    }
                });
    }

    /* ---------------- matching saved accounts to the app / website ---------------- */

    private static final List<String> SLD = Arrays.asList("com", "co", "org", "net", "gov", "edu", "ac");

    static String alnum(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    /** mail.google.com -> google.com, www.lazada.com.ph -> lazada.com.ph */
    static String baseDomain(String host) {
        if (host == null) return "";
        host = host.trim().toLowerCase(Locale.ROOT);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        boolean cc = p[p.length - 1].length() == 2 && SLD.contains(p[p.length - 2]);
        int n = cc ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = p.length - n; i < p.length; i++) { if (sb.length() > 0) sb.append('.'); sb.append(p[i]); }
        return sb.toString();
    }

    /** 3 = linked to this exact app/site, 2 = the platform name is in the app id/site, 0 = no match. */
    static int score(JSONObject e, String pkg, String domain) {
        String site = alnum(e.optString("site"));
        if (domain != null && !domain.isEmpty()) {
            String bd = baseDomain(domain);
            String dom = e.optString("dom");
            if (!dom.isEmpty() && baseDomain(dom).equals(bd)) return 3;
            if (site.length() >= 3 && alnum(bd).contains(site)) return 2;
            return 0;
        }
        String app = e.optString("app");
        if (!app.isEmpty() && app.equals(pkg)) return 3;
        if (site.length() >= 3 && pkg != null && alnum(pkg).contains(site)) return 2;
        return 0;
    }

    static RemoteViews presentation(Context c, String title, String sub) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.kv_autofill_item);
        v.setTextViewText(R.id.kv_title, title);
        v.setTextViewText(R.id.kv_sub, sub == null ? "" : sub);
        return v;
    }
}
