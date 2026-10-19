# Proggaa apps

Proggaa for **Windows** and **Android**: the [prooggaa.com](https://prooggaa.com) learning platform as an app, with
updates delivered inside the app and phone notifications on Android.

People download the apps from **[prooggaa.com/download](https://prooggaa.com/download)**, which links to the newest
[release](https://github.com/rahmannaiku-ux/proggaa-apps/releases/latest) of this repo.

| | Windows | Android |
|---|---|---|
| Built with | [Tauri 2](https://tauri.app) (Rust + Microsoft Edge WebView2) | Kotlin, Android WebView |
| Folder | [`desktop/`](desktop) | [`android/`](android) |
| Installer | `Proggaa-Setup.exe` (about 3 MB, installs per user, no admin needed) | `Proggaa.apk` |
| Updates | Checks on launch and every 6 hours; signed with the updater key | Checks on launch (at most every 4 hours); SHA-256 checked, and Android only accepts an APK signed with the same key |
| Notifications | Shown inside the site | Phone notifications, checked about every 15 minutes, even when the app is closed |
| Needs | Windows 10/11, 64-bit | Android 7.0 or newer |

## How the apps work

Both apps show the live website. The site is the app, so a change to prooggaa.com reaches every app at once without a
new release. A release is only needed when the app itself changes (updater, notifications, icons...).

- **The site gets no access to the device.** There is no JavaScript bridge: the Windows app grants remote pages no
  Tauri permissions, and the Android web view has no `addJavascriptInterface`.
- **Links:** prooggaa.com (and Google sign-in, used by admins connecting Drive) stay in the app. Every other link opens in
  the normal browser.
- **User agent:** each app adds a tag the site reads to adapt (`ProggaaDesktop/<version>`, `ProggaaApp/Android/<version>`;
  see `src/lib/apps.ts` in the website). It's only used to choose what to show, never for access.
- **"Check for updates" link:** a link to `proggaa://update` (on the site's /download and Settings pages, shown only
  inside the app) makes either app check straight away.
- **Open in the browser:** a link to `proggaa://browser?url=<https address>` opens that page in the normal browser
  instead of the app. Only `https` addresses are accepted. The site uses it for buying a mission: **paid missions are
  bought on the website, not in the apps**, so a mission's page in the app shows a "Buy on prooggaa.com" button instead
  of checkout. Free missions can still be joined in the app.
- **No landing page:** the apps open at `/dashboard`, which goes to the login screen when signed out. The site sends
  the apps from `/` to `/login` (or `/dashboard` when signed in), so they never show the marketing home page.
- **Offline:** the Windows app opens on a small local page (`desktop/dist/index.html`) that waits for a connection
  before opening the site. Android shows an offline screen with **Try again**.

### Windows (`desktop/`)

`src-tauri/src/lib.rs` opens one window on the start page, keeps a single copy running (a second launch focuses the
first), remembers the window size and position, and runs the updater. Updates come from
`https://github.com/rahmannaiku-ux/proggaa-apps/releases/latest/download/latest.json`. The installer downloads only if
its signature matches the public key in `tauri.conf.json`. The person is asked first ("Update now" / "Later"), the
download progress shows in the window title, and the installer closes, updates and reopens the app.

### Android (`android/`)

- `MainActivity.kt`: the web view, file and video picking for uploads (reels, photos, assignments), downloads through
  Android's download manager (with the session cookie), full-screen video, the back button, the offline screen, edge-to-edge
  layout that keeps clear of the keyboard, and opening the page a notification points to.
- `AppUpdater.kt`: reads `android-latest.json`, offers the update, downloads with a progress bar, checks the SHA-256 and
  opens the system installer. The first time, Android asks the person to allow Proggaa to install apps.
- `NotificationWorker.kt`: a background job (WorkManager, about every 15 minutes with a connection) calls the site's
  `GET /api/app/notifications?after=…` with the web view's session cookie and shows new unread notifications. Tapping one
  opens its page. A notification the site updates in place (for example, more messages in the same chat) replaces the
  old one. While the app is open the site shows notifications itself, so the job stays quiet.
- Android 13+ asks for notification permission on first launch.

## Building on your computer

You only need this to try changes locally. Releases are built by GitHub Actions.

**Windows app:** install [Rust](https://rustup.rs), Node 22+, and the "Desktop development with C++" workload of Visual
Studio Build Tools. Then:

```bash
cd desktop
npm install
npx tauri dev                # runs the app
cd src-tauri && cargo test   # tests
npx tauri build --no-bundle  # a release build without an installer (no key needed)
```

**Android app:** install [Android Studio](https://developer.android.com/studio) (it includes the SDK and Java 17) and
open the `android/` folder. Run on a phone or emulator with ▶. From a terminal with Gradle 8.11:
`gradle testDebugUnitTest assembleDebug` (the APK is in `android/app/build/outputs/apk/debug/`). A debug build can't
update a release build or the other way round, because they're signed with different keys.

**Scripts:** `npm test` checks the update-manifest code.

## Releasing an update

1. Set the new version (for both apps):
   ```bash
   node scripts/set-version.mjs 1.0.1
   ```
   It must be higher than the current one. Android's version code is worked out from it: 1.0.1 → 10001.
2. Commit, then tag with what's new. The tag message becomes the release notes the apps show:
   ```bash
   git commit -am "Proggaa 1.0.1"
   git tag -a v1.0.1 -m "Faster start-up and fixes for file uploads"
   git push && git push origin v1.0.1
   ```
3. The **Release** workflow (`.github/workflows/release.yml`) tests and builds both apps, signs them, and publishes the
   GitHub Release with:
   - `Proggaa_1.0.1_x64-setup.exe` and its `.sig`, plus `latest.json` for the Windows updater
   - `Proggaa-1.0.1.apk` and `android-latest.json` for the Android updater
   - `Proggaa-Setup.exe` and `Proggaa.apk`, the same files under fixed names for the website's download buttons

   It's published as a draft first and made "latest" only when every file is uploaded, so no app sees an update before
   its files exist. Installed apps pick it up the next time they check.

If a release goes wrong, delete it (and its tag) on GitHub. The apps then see the previous release as the latest again.

## Keys

Two keys sign the apps. **If either is lost, installed apps can't be updated any more.** Windows would reject the
update, and Android would refuse to install it over the old app, so everyone would have to reinstall. Keep backups
somewhere safe and private (never in this repo; `.gitignore` blocks key files).

| Key | On the release computer | GitHub secret |
|---|---|---|
| Windows updater key (minisign) | `D:\proggaa-keys\tauri\proggaa-updater.key` (+ `.password`, `.key.pub`) | `TAURI_SIGNING_PRIVATE_KEY`, `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` |
| Android signing keystore (PKCS#12, alias `proggaa`) | `D:\proggaa-keys\android\proggaa-release.p12` (+ `keystore.password`, `cert.pem`) | `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` |

The Windows public key is in `desktop/src-tauri/tauri.conf.json`. The Android certificate's SHA-256 fingerprint is in
`D:\proggaa-keys\android\cert-sha256.txt`. The release workflow checks every APK is signed by `CN=Proggaa`.

To put a key into GitHub again: `gh secret set TAURI_SIGNING_PRIVATE_KEY < proggaa-updater.key`, and for Android
`base64 -w0 proggaa-release.p12 | gh secret set ANDROID_KEYSTORE_BASE64`.

## Good to know

- **Windows SmartScreen:** the installer isn't signed with a paid code-signing certificate, so the first time Windows
  may say "Windows protected your PC" and people click **More info → Run anyway**. The download page explains this. A
  code-signing certificate would remove the warning.
- **Play Store:** the APK is installed directly (sideloaded). To publish on Google Play later, use the same keystore
  as the upload key and switch notifications to Firebase Cloud Messaging for instant delivery.
- **Notification delay on Android:** background checks run about every 15 minutes (Android's minimum), and battery
  saver can delay them further. Instant push would need Firebase Cloud Messaging.
- **iPhone:** there is no iOS app. iPhone users add prooggaa.com to their home screen from Safari.

## Website side

These live in the website repo (`rahmannaiku-ux/progga`):

- `src/app/(public)/download/page.tsx`: the download page, with links in the header, footer and Settings
- `src/app/api/app/notifications/route.ts`: what the Android notification job reads
- `src/lib/apps.ts`: download links and app detection
