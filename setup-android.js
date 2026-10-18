// Runs after "npx cap add android" (GitHub Actions).
// 1) blocks screenshots / screen recording and hides the vault in the Recent apps screen,
// 2) adds the AdMob App ID to the Android manifest, 3) sets the app version numbers, 4) sets the app icon,
// 5) lets the Open button / app picker see installed apps, 6) lets Download save into Documents on old phones,
// 7) adds KeyVault's own native code (vault bridge, fingerprint unlock, Autofill service) from android-src/.
const fs = require('fs');
const path = require('path');

// 2) tells Google Mobile Ads which AdMob app this is.
//    KeyVault's own AdMob App ID (not To-Do Alarm's ~6287900616).
const ADMOB_APP_ID = 'ca-app-pub-1108774748467505~5686968107';

const manifest = path.join('android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifest, 'utf8');
if (!xml.includes('com.google.android.gms.ads.APPLICATION_ID')) {
  xml = xml.replace('</application>',
    '    <meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="' + ADMOB_APP_ID + '"/>\n    </application>');
}
// 5) Open button: Android 11+ only lets an app launch other apps it declares here.
//    Keep this list in sync with PLATFORMS in index.html.
const OPEN_APPS = [
  'com.google.android.gm', 'com.facebook.katana', 'com.facebook.orca', 'com.instagram.android',
  'com.zhiliaoapp.musically', 'com.twitter.android', 'com.google.android.youtube', 'com.whatsapp',
  'org.telegram.messenger', 'com.viber.voip', 'com.snapchat.android', 'com.linkedin.android',
  'com.microsoft.office.outlook', 'com.yahoo.mobile.client.android.mail', 'com.globe.gcash.android',
  'com.paymaya', 'com.paypal.android.p2pmobile', 'com.lazada.android', 'com.amazon.mShop.android.shopping',
  'com.netflix.mediaclient', 'com.spotify.music', 'com.discord', 'com.github.android',
  'net.metaquotes.metatrader5', 'com.tradingview.tradingviewapp', 'com.valvesoftware.android.steam.community'
];
if (!xml.includes('<queries>')) {
  const q = '    <queries>\n' +
    OPEN_APPS.map(p => '        <package android:name="' + p + '" />').join('\n') + '\n' +
    '        <intent>\n            <action android:name="android.intent.action.VIEW" />\n' +
    '            <data android:scheme="https" />\n        </intent>\n' +
    // "Choose from my apps": apps that have a home-screen icon (no QUERY_ALL_PACKAGES needed)
    '        <intent>\n            <action android:name="android.intent.action.MAIN" />\n' +
    '            <category android:name="android.intent.category.LAUNCHER" />\n        </intent>\n' +
    '        <intent>\n            <action android:name="android.intent.action.VIEW" />\n' +
    '            <data android:scheme="market" />\n        </intent>\n    </queries>\n';
  xml = xml.replace(/(\s*)<application/, '\n' + q + '$1<application');
}

// 6) Download button: Android 10 and older need storage permission to save into Documents.
const storagePerms = [
  '<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />',
  '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="29" />'
].filter(p => !xml.includes(p.split('"')[1]));
if (storagePerms.length) xml = xml.replace('</manifest>', '    ' + storagePerms.join('\n    ') + '\n</manifest>');
if (!xml.includes('requestLegacyExternalStorage')) xml = xml.replace('<application', '<application android:requestLegacyExternalStorage="true"');

// 7a) fingerprint permissions
const bioPerms = [
  '<uses-permission android:name="android.permission.USE_BIOMETRIC" />',
  '<uses-permission android:name="android.permission.USE_FINGERPRINT" />'
].filter(p => !xml.includes(p.split('"')[1]));
if (bioPerms.length) xml = xml.replace('</manifest>', '    ' + bioPerms.join('\n    ') + '\n</manifest>');

// 7b) Autofill service (fills logins in other apps) + its unlock screen
if (!xml.includes('KeyVaultAutofillService')) {
  xml = xml.replace('</application>',
    '    <service\n' +
    '            android:name=".KeyVaultAutofillService"\n' +
    '            android:label="KeyVault"\n' +
    '            android:exported="true"\n' +
    '            android:permission="android.permission.BIND_AUTOFILL_SERVICE">\n' +
    '            <intent-filter>\n' +
    '                <action android:name="android.service.autofill.AutofillService" />\n' +
    '            </intent-filter>\n' +
    '            <meta-data android:name="android.autofill" android:resource="@xml/kv_autofill_service" />\n' +
    '        </service>\n' +
    '        <activity\n' +
    '            android:name=".AutofillAuthActivity"\n' +
    '            android:exported="false"\n' +
    '            android:excludeFromRecents="true"\n' +
    '            android:theme="@android:style/Theme.DeviceDefault.Dialog.NoActionBar" />\n' +
    '    </application>');
}
fs.writeFileSync(manifest, xml);

// 1) + 7c) our native code: MainActivity (screenshot block + bridge), vault bridge, fingerprint, Autofill.
const appId = require('./capacitor.config.json').appId;            // com.pasuyolabs.keyvault
const javaDir = path.join('android', 'app', 'src', 'main', 'java', ...appId.split('.'));
if (!fs.existsSync(path.join(javaDir, 'MainActivity.java'))) throw new Error('MainActivity.java not found in ' + javaDir);
const srcJava = path.join('android-src', 'java');
if (!fs.existsSync(srcJava)) throw new Error('android-src/java is missing: upload the android-src folder to GitHub');
for (const f of fs.readdirSync(srcJava).filter(f => f.endsWith('.java'))) {
  const code = fs.readFileSync(path.join(srcJava, f), 'utf8');
  if (!code.startsWith('package ' + appId + ';')) throw new Error(f + ' must start with: package ' + appId + ';');
  fs.writeFileSync(path.join(javaDir, f), code);
}
const resSrc = path.join('android-src', 'res');
const resOut = path.join('android', 'app', 'src', 'main', 'res');
(function copyDir(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const f of fs.readdirSync(from)) {
    const a = path.join(from, f), b = path.join(to, f);
    if (fs.statSync(a).isDirectory()) copyDir(a, b); else fs.copyFileSync(a, b);
  }
})(resSrc, resOut);

// 4) app icon: copy our KeyVault icon (all sizes) over Capacitor's default icon.
const iconFile = 'app-icons.json';
if (!fs.existsSync(iconFile)) throw new Error('app-icons.json is missing: upload it to GitHub');
const icons = JSON.parse(fs.readFileSync(iconFile, 'utf8'));
const resDir = path.join('android', 'app', 'src', 'main', 'res');
for (const rel of Object.keys(icons)) {
  const dest = path.join(resDir, ...rel.split('/'));
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.writeFileSync(dest, Buffer.from(icons[rel], 'base64'));
}

// 3) version: Google Play needs a bigger versionCode on every upload.
const versionCode = parseInt(process.env.VERSION_CODE || '1', 10);
const versionName = process.env.VERSION_NAME || require('./package.json').version;
const gradleFile = path.join('android', 'app', 'build.gradle');
let gradle = fs.readFileSync(gradleFile, 'utf8');
if (!/versionCode \d+/.test(gradle) || !/versionName "[^"]*"/.test(gradle)) {
  throw new Error('Could not find versionCode/versionName in ' + gradleFile);
}
gradle = gradle.replace(/versionCode \d+/, 'versionCode ' + versionCode)
               .replace(/versionName "[^"]*"/, 'versionName "' + versionName + '"');
fs.writeFileSync(gradleFile, gradle);

console.log('Android setup done: native code, Autofill, fingerprint, screenshot block, AdMob app ID, app icon, version ' +
  versionName + ' (code ' + versionCode + ').');
