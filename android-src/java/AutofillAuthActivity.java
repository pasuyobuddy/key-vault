package com.pasuyolabs.keyvault;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;

/**
 * The small "Unlock KeyVault" screen shown over another app when the user taps the Autofill suggestion.
 * Fingerprint (if turned on) or master password -> the matching saved logins are offered in the app's login boxes.
 */
@TargetApi(26)
public class AutofillAuthActivity extends Activity {
    static final String EXTRA_USER_ID = "kv_user_id";
    static final String EXTRA_PASS_ID = "kv_pass_id";
    static final String EXTRA_USER_KIND = "kv_user_kind";
    static final String EXTRA_PKG = "kv_pkg";
    static final String EXTRA_DOMAIN = "kv_domain";
    private static final int BRAND = Color.parseColor("#0E7A62");

    private AutofillId userId, passId;
    private String userKind, pkg, domain, appName;
    private EditText pw;
    private TextView err;
    private Button unlockBtn;
    private boolean busy;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setResult(RESULT_CANCELED);
        Intent in = getIntent();
        userId = in.getParcelableExtra(EXTRA_USER_ID);
        passId = in.getParcelableExtra(EXTRA_PASS_ID);
        userKind = in.getStringExtra(EXTRA_USER_KIND);
        pkg = in.getStringExtra(EXTRA_PKG);
        domain = in.getStringExtra(EXTRA_DOMAIN);
        appName = domain != null && !domain.isEmpty() ? domain : labelOf(pkg);
        buildUi();
        if (!VaultCore.hasVault(this)) { showErr("Open KeyVault and create your vault first."); unlockBtn.setEnabled(false); return; }
        if (VaultCore.bioEnabled(this) && Build.VERSION.SDK_INT >= 28) startFingerprint();
    }

    private String labelOf(String p) {
        if (p == null) return "this app";
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)));
        } catch (Exception e) {
            return p;
        }
    }

    private int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private void buildUi() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(22), dp(24), dp(18));
        box.setMinimumWidth(dp(300));

        TextView title = new TextView(this);
        title.setText("Unlock KeyVault");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("To fill your login in " + appName);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setPadding(0, dp(4), 0, dp(14));
        box.addView(sub);

        pw = new EditText(this);
        pw.setHint("Master password");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setImeOptions(EditorInfo.IME_ACTION_GO);
        pw.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        pw.setOnEditorActionListener((v, action, ev) -> { unlockWithPassword(); return true; });
        box.addView(pw, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        err = new TextView(this);
        err.setTextColor(Color.parseColor("#C2372D"));
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        err.setPadding(0, dp(6), 0, dp(6));
        box.addView(err);

        unlockBtn = button("Unlock", true);
        unlockBtn.setOnClickListener(v -> unlockWithPassword());
        box.addView(unlockBtn, full());

        if (VaultCore.bioEnabled(this) && Build.VERSION.SDK_INT >= 28) {
            Button fp = button("Use fingerprint", false);
            fp.setOnClickListener(v -> startFingerprint());
            LinearLayout.LayoutParams lp = full();
            lp.topMargin = dp(8);
            box.addView(fp, lp);
        }
        Button cancel = button("Cancel", false);
        cancel.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(8);
        box.addView(cancel, lp);

        box.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        setContentView(box);
        long wait = VaultCore.waitLeftMs(this);
        if (wait > 0) showErr("Too many wrong tries. Wait " + ((wait + 999) / 1000) + " s.");
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
    }

    private Button button(String text, boolean main) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (main) { bg.setColor(BRAND); b.setTextColor(Color.WHITE); }
        else { bg.setColor(Color.TRANSPARENT); bg.setStroke(dp(1), Color.parseColor("#9AA8A4")); }
        b.setBackground(bg);
        return b;
    }

    private void showErr(String s) { err.setText(s); }

    private void setBusy(boolean b) {
        busy = b;
        unlockBtn.setEnabled(!b);
        unlockBtn.setText(b ? "Unlocking…" : "Unlock");
    }

    private void unlockWithPassword() {
        if (busy) return;
        long wait = VaultCore.waitLeftMs(this);
        if (wait > 0) { showErr("Too many wrong tries. Wait " + ((wait + 999) / 1000) + " s."); return; }
        final String p = pw.getText().toString();
        if (p.isEmpty()) { showErr("Type your master password."); return; }
        setBusy(true);
        showErr("");
        new Thread(() -> {
            try {
                JSONObject rec = VaultCore.readRecord(this);
                byte[] dek = VaultCore.dekFromPassword(rec, p);
                JSONArray list = VaultCore.entries(rec, dek);
                Arrays.fill(dek, (byte) 0);
                VaultCore.goodTry(this);
                runOnUiThread(() -> { setBusy(false); deliver(list); });
            } catch (AEADBadTagException bad) {
                VaultCore.wrongTry(this);
                runOnUiThread(() -> {
                    setBusy(false);
                    pw.setText("");
                    long w = VaultCore.waitLeftMs(this);
                    showErr(w > 0 ? "Too many wrong tries. Wait 30 s." : "Wrong master password.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setBusy(false); showErr("Could not open your vault. Open KeyVault once and try again."); });
            }
        }).start();
    }

    @TargetApi(28)
    private void startFingerprint() {
        final Cipher c;
        try {
            c = VaultCore.bioDecryptCipher(this);
        } catch (KeyPermanentlyInvalidatedException e) {
            VaultCore.bioDisable(this);
            showErr("Your fingerprints changed. Use your master password, then turn fingerprint unlock on again in KeyVault.");
            return;
        } catch (Exception e) {
            showErr("Fingerprint unlock is not available. Use your master password.");
            return;
        }
        if (c == null) return;
        VaultCore.bioPrompt(this, c, "Unlock KeyVault", "To fill your login in " + appName, (unlocked, error) -> {
            if (unlocked == null) { pw.requestFocus(); return; }
            try {
                byte[] dek = unlocked.doFinal(VaultCore.bioWrapped(this));
                JSONArray list = VaultCore.entries(VaultCore.readRecord(this), dek);
                Arrays.fill(dek, (byte) 0);
                deliver(list);
            } catch (Exception e) {
                showErr("Fingerprint unlock failed. Use your master password.");
            }
        });
    }

    /** Builds one suggestion per matching account and hands them back to the app's login boxes. */
    @SuppressWarnings("deprecation")
    private void deliver(JSONArray all) {
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e != null && !e.optString("pass").isEmpty()) items.add(e);
        }
        List<JSONObject> matches = new ArrayList<>();
        for (JSONObject e : items) if (VaultCore.score(e, pkg, domain) > 0) matches.add(e);
        boolean exact = !matches.isEmpty();
        List<JSONObject> show = exact ? matches : items;
        show.sort((a, b) -> {
            int s = VaultCore.score(b, pkg, domain) - VaultCore.score(a, pkg, domain);
            if (s != 0) return s;
            return a.optString("site").toLowerCase(Locale.ROOT).compareTo(b.optString("site").toLowerCase(Locale.ROOT));
        });
        if (show.size() > 40) show = show.subList(0, 40);

        FillResponse.Builder fr = new FillResponse.Builder();
        int added = 0;
        for (JSONObject e : show) {
            String email = e.optString("email"), user = e.optString("user"), pass = e.optString("pass");
            String login;
            if ("user".equals(userKind)) login = !user.isEmpty() ? user : email;
            else login = !email.isEmpty() ? email : user;
            String shown = !email.isEmpty() ? email : (user.isEmpty() ? "" : "@" + user);
            Dataset.Builder d = new Dataset.Builder(VaultCore.presentation(this, e.optString("site"), shown));
            boolean any = false;
            if (userId != null && !login.isEmpty()) { d.setValue(userId, AutofillValue.forText(login)); any = true; }
            if (passId != null) { d.setValue(passId, AutofillValue.forText(pass)); any = true; }
            if (any) { fr.addDataset(d.build()); added++; }
        }
        if (added == 0) {
            showErr(items.isEmpty() ? "Your vault is empty. Add the login in KeyVault first." : "No saved login fits this box.");
            return;
        }
        Intent reply = new Intent();
        reply.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fr.build());
        setResult(RESULT_OK, reply);
        finish();
    }
}
