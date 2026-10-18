package com.pasuyolabs.keyvault;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.view.autofill.AutofillManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.crypto.Cipher;

/** The bridge between the KeyVault screens (index.html) and Android. */
@CapacitorPlugin(name = "VaultBridge")
public class VaultBridgePlugin extends Plugin {

    /** Keeps the encrypted vault copy that the Autofill service reads up to date. */
    @PluginMethod
    public void store(PluginCall call) {
        String record = call.getString("record");
        if (record == null || record.isEmpty()) { call.reject("No vault given"); return; }
        try {
            VaultCore.store(getContext(), record);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not save the vault copy", e);
        }
    }

    @PluginMethod
    public void clear(PluginCall call) {
        VaultCore.clear(getContext());
        call.resolve();
    }

    /** Apps the user can open from the home screen: name, package id and a small icon. */
    @PluginMethod
    public void listApps(PluginCall call) {
        new Thread(() -> {
            try {
                Context ctx = getContext();
                PackageManager pm = ctx.getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
                Set<String> seen = new HashSet<>();
                List<JSObject> apps = new ArrayList<>();
                for (ResolveInfo ri : found) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(ctx.getPackageName()) || !seen.add(pkg)) continue;
                    JSObject o = new JSObject();
                    o.put("pkg", pkg);
                    o.put("label", String.valueOf(ri.loadLabel(pm)));
                    String icon = iconPng(ri.loadIcon(pm));
                    if (icon != null) o.put("icon", "data:image/png;base64," + icon);
                    apps.add(o);
                }
                apps.sort((a, b) -> a.getString("label", "").toLowerCase(Locale.ROOT)
                        .compareTo(b.getString("label", "").toLowerCase(Locale.ROOT)));
                JSObject ret = new JSObject();
                ret.put("apps", new JSArray(apps));
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Could not list apps", e);
            }
        }).start();
    }

    private static String iconPng(Drawable d) {
        try {
            int size = 96;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            bmp.recycle();
            return VaultCore.b64(out.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    @PluginMethod
    public void isInstalled(PluginCall call) {
        String pkg = call.getString("pkg", "");
        JSObject ret = new JSObject();
        boolean ok = false;
        try {
            ok = pkg != null && !pkg.isEmpty() && getContext().getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Exception ignored) { }
        ret.put("installed", ok);
        call.resolve(ret);
    }

    /* ---------------- fingerprint ---------------- */

    @PluginMethod
    public void bioStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("available", VaultCore.bioAvailability(getContext()));
        ret.put("enabled", VaultCore.bioEnabled(getContext()));
        call.resolve(ret);
    }

    /** Wraps the vault key with a fingerprint-protected Keystore key. dek = base64 vault key. */
    @PluginMethod
    public void bioEnable(PluginCall call) {
        if (Build.VERSION.SDK_INT < 28) { call.reject("Fingerprint unlock needs Android 9 or newer", "UNSUPPORTED"); return; }
        String dek = call.getString("dek");
        if (dek == null) { call.reject("No key given"); return; }
        final byte[] raw = VaultCore.unb64(dek);
        final Cipher cipher;
        try {
            cipher = VaultCore.bioEncryptCipher();
        } catch (Exception e) {
            call.reject("Could not set up fingerprint unlock", "FAILED", e);
            return;
        }
        Activity a = getActivity();
        a.runOnUiThread(() -> VaultCore.bioPrompt(a, cipher, "Turn on fingerprint unlock", "Touch the sensor to confirm",
                (unlocked, err) -> {
                    if (unlocked == null) { VaultCore.bioDisable(getContext()); Arrays.fill(raw, (byte) 0); call.reject(err == null ? "cancel" : err, "CANCELLED"); return; }
                    try {
                        byte[] wrapped = unlocked.doFinal(raw);
                        VaultCore.bioSave(getContext(), unlocked.getIV(), wrapped);
                        call.resolve();
                    } catch (Exception e) {
                        VaultCore.bioDisable(getContext());
                        call.reject("Could not turn on fingerprint unlock", "FAILED", e);
                    } finally {
                        Arrays.fill(raw, (byte) 0);
                    }
                }));
    }

    /** Fingerprint -> base64 vault key. */
    @PluginMethod
    public void bioUnlock(PluginCall call) {
        if (Build.VERSION.SDK_INT < 28) { call.reject("Not supported", "UNSUPPORTED"); return; }
        final Cipher cipher;
        try {
            cipher = VaultCore.bioDecryptCipher(getContext());
        } catch (KeyPermanentlyInvalidatedException e) {
            VaultCore.bioDisable(getContext());
            call.reject("Your phone's fingerprints changed. Unlock with your master password and turn fingerprint unlock on again.", "INVALIDATED");
            return;
        } catch (Exception e) {
            call.reject("Fingerprint unlock failed", "FAILED", e);
            return;
        }
        if (cipher == null) { call.reject("Fingerprint unlock is off", "OFF"); return; }
        Activity a = getActivity();
        a.runOnUiThread(() -> VaultCore.bioPrompt(a, cipher, "Unlock KeyVault", "Touch the sensor",
                (unlocked, err) -> {
                    if (unlocked == null) { call.reject(err == null ? "cancel" : err, "CANCELLED"); return; }
                    try {
                        byte[] dek = unlocked.doFinal(VaultCore.bioWrapped(getContext()));
                        JSObject ret = new JSObject();
                        ret.put("dek", VaultCore.b64(dek));
                        Arrays.fill(dek, (byte) 0);
                        call.resolve(ret);
                    } catch (Exception e) {
                        call.reject("Fingerprint unlock failed", "FAILED", e);
                    }
                }));
    }

    @PluginMethod
    public void bioDisable(PluginCall call) {
        VaultCore.bioDisable(getContext());
        call.resolve();
    }

    /* ---------------- Autofill ---------------- */

    @PluginMethod
    public void autofillStatus(PluginCall call) {
        JSObject ret = new JSObject();
        boolean supported = false, enabled = false;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                AutofillManager am = getContext().getSystemService(AutofillManager.class);
                supported = am != null && am.isAutofillSupported();
                enabled = supported && am.hasEnabledAutofillServices();
            } catch (Exception ignored) { }
        }
        ret.put("supported", supported);
        ret.put("enabled", enabled);
        call.resolve(ret);
    }

    @PluginMethod
    public void openAutofillSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT < 26) { call.reject("Autofill needs Android 8 or newer", "UNSUPPORTED"); return; }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
            i.setData(Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            try {
                getActivity().startActivity(new Intent(Settings.ACTION_SETTINGS));
                call.resolve();
            } catch (Exception e2) {
                call.reject("Could not open Settings", e2);
            }
        }
    }
}
