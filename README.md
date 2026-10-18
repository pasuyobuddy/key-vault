# KeyVault v1.2.1 — by PasuyoLabs

Offline password manager for Android. Master password → AES-256 encrypted vault on the phone. No server, no account.

## What's inside
| File | What it does |
|---|---|
| `index.html` | The whole app (lock screen, vault, copy buttons, generator, settings, ads) |
| `package.json` | App version (`1.2.1`) + Capacitor plugins |
| `capacitor.config.json` | App ID `com.pasuyolabs.keyvault`, name `KeyVault` |
| `setup-android.js` | Copies the native code in, registers Autofill, fingerprint permission, AdMob App ID, icon, version numbers |
| `android-src/` | KeyVault's own Android code: vault bridge, fingerprint unlock, Autofill service + unlock screen |
| `.github/workflows/main.yml` | GitHub builds the TEST APK (and the Play Store .aab once the key secrets are added) |
| `app-icons.json` | App icon in every Android size |
| `keyvault-privacy.html` | Privacy policy page for Google Play |
| `play-store-icon-512.png` | 512×512 icon for the Play Store listing |

## Build it (same as To-Do Alarm)
1. On GitHub (pasuyobuddy account) create a new repository named **keyvault** (public).
2. Upload every file **and folder** from this folder (including `android-src`). If the `.github` folder doesn't upload, use **Add file → Create new file**, name it `.github/workflows/main.yml`, and paste in that file's text.
3. Open the **Actions** tab → wait for the green tick → download **keyvault-TEST-apk** → install it on your phone. It shows Google's test ads only.

## Before the Play Store
1. **AdMob**: Apps → Add app → "KeyVault" (Android, not on Play yet). Create one **Banner** and one **Interstitial** ad unit.
   - Put the App ID in `setup-android.js` (`ADMOB_APP_ID`).
   - Put the two ad unit IDs in `index.html` (`AD_BANNER`, `AD_INTERSTITIAL`).
   - Done in v1.2.1 (KeyVault App `~5686968107`). The Play Store build refuses to run if Google's sample IDs (`3940256099942544`) ever come back.
2. **Signing key**: in the keyvault repo → Settings → Secrets and variables → Actions, add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (you can reuse the same upload key you made for To-Do Alarm). The next build also makes **keyvault-PLAYSTORE-aab**.
3. **Privacy policy**: upload `keyvault-privacy.html` to your `pasuyobuddy.github.io` repository. The link becomes `https://pasuyobuddy.github.io/keyvault-privacy.html` (the app's Settings already points there).
4. **Play Console → Data safety**: data collected = only what the AdMob SDK collects (device or other IDs, app interactions, diagnostics). Passwords are **not** collected — they never leave the phone. Data is encrypted in transit: yes (ads). Users can delete data: yes (in app).

## What's new in v1.2.1
- KeyVault's own AdMob IDs are in: App `~5686968107`, Banner `/2449771532`, Interstitial `/3036754030`. The TEST APK still shows test ads only; the Play Store file shows real ads.

## What came in v1.2.0
- **Autofill**: tap a login box in any app or website → **Unlock KeyVault** → fingerprint or master password → email and password are filled. Works for apps KeyVault has never heard of (it matches the app/site to the platform name, the picked app, or the website).
- **Fingerprint unlock** for KeyVault itself and for Autofill (Settings → Unlock with fingerprint).
- **Choose from my apps**: pick the platform from the apps installed on the phone; Open then launches that exact app. Other accounts of the same platform get linked too.
- **Open** order: the linked app if it's on the phone → the website → a message **"Can't open … — Install from Play Store / Add website"** (nothing is copied in that case).
- A tip card on the vault screen helps users turn on Autofill (Settings → Autofill in other apps).

## What came in v1.1.0
- Accounts are **grouped by platform** (2 Gmail accounts sit together under GMAIL). Typing "gmail" or "GMAIL" joins the same group.
- Each account has **email address + username**, an **eye** button (shows the password for 15 s), **copy**, and **Open**.
- **Open** copies the password, then opens the platform's app (Gmail, Facebook, GCash, TikTok and about 30 others) or its website. You long-press the password box and Paste.
- **Download** → encrypted `.kvault` file in Documents/KeyVault. **Upload to Drive** → share menu, pick Drive. **Import keys** → pick a `.kvault` file (on a new phone, or to merge).
- **Forgot master password** → type the recovery code shown when the vault was created, set a new password.
- 5 wrong tries → wait 30 seconds, then try again (never locked for good).
- Full-screen ad after every **3** new passwords + banner on the vault screen.
- A vault made with v1.0 upgrades itself on the first unlock and shows a new recovery code.

## Security features
- Autofill only fills after an unlock (fingerprint or master password). The Autofill service reads an **encrypted** copy of the vault in KeyVault's private storage.
- Fingerprint unlock keeps the vault key in Android Keystore; adding a new fingerprint to the phone switches it off automatically.
- AES-256-GCM. A random vault key is stored twice: locked by the master password (PBKDF2-SHA256, 600,000 rounds) and by the recovery code. Nothing is stored in plain text.
- Auto-lock when you leave the app (right away / 30 s / 1 min / 5 min) and after 5 minutes idle.
- Copied passwords wiped from the clipboard after 30 s.
- Screenshots, screen recording and the Recent-apps preview are blocked.

## Testing on the phone (v1.2.0)
1. Install the TEST APK, create the vault, save the recovery code.
2. The tip card says **Turn on** → Android Settings opens → choose **KeyVault** as the Autofill service.
3. Settings → **Unlock with fingerprint** → type the master password once → touch the sensor.
4. Add a Facebook account, then open the Facebook app → tap the email box → **Unlock KeyVault** → fingerprint → pick the account.
5. Add password → **Choose from my apps** → pick any app → Open launches it.

## Every update
Bump `"version"` in `package.json` (e.g. 1.2.1). The build number goes up by itself on every GitHub run.
