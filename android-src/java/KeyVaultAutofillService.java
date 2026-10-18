package com.pasuyolabs.keyvault;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.ViewNode;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Build;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.text.InputType;
import android.util.Pair;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Android asks this service when the user taps a login box in any app or website.
 * It only finds the email/username and password boxes and answers "Unlock KeyVault".
 * The saved logins are read only after the user unlocks (AutofillAuthActivity).
 */
@TargetApi(26)
public class KeyVaultAutofillService extends AutofillService {

    @Override
    @SuppressWarnings("deprecation")   // setAuthentication(..., RemoteViews) still works on all Android versions
    public void onFillRequest(FillRequest request, CancellationSignal cancel, FillCallback callback) {
        try {
            List<FillContext> contexts = request.getFillContexts();
            AssistStructure st = contexts.get(contexts.size() - 1).getStructure();
            String pkg = st.getActivityComponent().getPackageName();
            if (getPackageName().equals(pkg) || !VaultCore.hasVault(this)) { callback.onSuccess(null); return; }

            Finder f = new Finder();
            for (int i = 0; i < st.getWindowNodeCount(); i++) f.walk(st.getWindowNodeAt(i).getRootViewNode());
            f.finish();
            if (f.pass == null && f.user == null) { callback.onSuccess(null); return; }

            ArrayList<AutofillId> ids = new ArrayList<>();
            if (f.user != null) ids.add(f.user);
            if (f.pass != null) ids.add(f.pass);

            Intent it = new Intent(this, AutofillAuthActivity.class);
            it.putExtra(AutofillAuthActivity.EXTRA_USER_ID, f.user);
            it.putExtra(AutofillAuthActivity.EXTRA_PASS_ID, f.pass);
            it.putExtra(AutofillAuthActivity.EXTRA_USER_KIND, f.userKind);
            it.putExtra(AutofillAuthActivity.EXTRA_PKG, pkg);
            it.putExtra(AutofillAuthActivity.EXTRA_DOMAIN, f.domain);
            int flags = PendingIntent.FLAG_CANCEL_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            IntentSender sender = PendingIntent.getActivity(this, (int) (System.nanoTime() & 0x7fffffff), it, flags).getIntentSender();

            FillResponse r = new FillResponse.Builder()
                    .setAuthentication(ids.toArray(new AutofillId[0]), sender,
                            VaultCore.presentation(this, "Unlock KeyVault", "Fill your saved login"))
                    .build();
            callback.onSuccess(r);
        } catch (Exception e) {
            callback.onSuccess(null);
        }
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        callback.onSuccess();   // saving new logins from other apps is not offered yet
    }

    /** Walks the screen and picks the login boxes. */
    static final class Finder {
        AutofillId user, pass, lastText, beforePass;
        String userKind = "unknown";
        String domain;

        void walk(ViewNode n) {
            if (n == null) return;
            String wd = n.getWebDomain();
            if (domain == null && wd != null && !wd.isEmpty()) domain = wd;
            AutofillId id = n.getAutofillId();
            if (id != null && n.getAutofillType() == View.AUTOFILL_TYPE_TEXT && n.getVisibility() == View.VISIBLE) {
                String kind = classify(n);
                if ("pass".equals(kind)) {
                    if (pass == null) { pass = id; beforePass = lastText; }
                } else if (!"skip".equals(kind)) {
                    if (kind != null && user == null) { user = id; userKind = kind; }
                    lastText = id;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) walk(n.getChildAt(i));
        }

        void finish() {
            if (user == null && pass != null && beforePass != null) { user = beforePass; userKind = "unknown"; }
        }

        private static String lc(CharSequence s) { return s == null ? "" : s.toString().toLowerCase(Locale.ROOT); }

        static String classify(ViewNode n) {
            String[] hints = n.getAutofillHints();
            if (hints != null) {
                for (String h : hints) {
                    String x = lc(h);
                    if (x.contains("password")) return "pass";
                    if (x.contains("email")) return "email";
                    if (x.contains("username") || x.contains("phone")) return "user";
                }
            }
            int it = n.getInputType();
            int cls = it & InputType.TYPE_MASK_CLASS, var = it & InputType.TYPE_MASK_VARIATION;
            if (cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) return "pass";
            if (cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return "pass";
            if (cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) return "email";

            StringBuilder sb = new StringBuilder();
            ViewStructure.HtmlInfo h = n.getHtmlInfo();
            if (h != null && h.getAttributes() != null) {
                for (Pair<String, String> a : h.getAttributes()) {
                    String k = lc(a.first), v = lc(a.second);
                    if (k.equals("type")) {
                        if (v.equals("password")) return "pass";
                        if (v.equals("email")) return "email";
                        if (v.equals("hidden") || v.equals("submit") || v.equals("checkbox") || v.equals("radio")
                                || v.equals("button") || v.equals("search")) return "skip";
                    }
                    if (k.equals("name") || k.equals("id") || k.equals("autocomplete") || k.equals("placeholder")
                            || k.equals("aria-label")) sb.append(v).append(' ');
                }
            }
            sb.append(lc(n.getIdEntry())).append(' ').append(lc(n.getHint()));
            String t = sb.toString();
            if (t.contains("search")) return "skip";
            if (t.contains("password") || t.contains("passwd") || t.contains("current-password")) return "pass";
            if (t.contains("email") || t.contains("e-mail")) return "email";
            if (t.contains("user") || t.contains("login") || t.contains("account") || t.contains("phone")
                    || t.contains("mobile")) return "user";
            return null;
        }
    }
}
