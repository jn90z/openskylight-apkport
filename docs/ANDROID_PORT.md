# OpenSkyLight on Android

The `android` branch targets the Samsung Galaxy Tab A SM-T510 (2 GB RAM). The APK
requires Android 9 or later (API 28+). It contains the desktop React kiosk and
the original companion web app. Update Android System WebView and Chrome from
Google Play before installing. The kiosk needs a current WebView for its CSS and
touch interface.

## Install and configure

1. Open the Android APK workflow and select a successful run for the `android`
   branch. Download and extract the signed APK artifact. The debug APK is for
   testing and its CI signing certificate can change between runs.
2. On the tablet, open the APK and allow installation from that file manager
   when Android asks. With USB debugging, use
   `adb install -r path/to/app-release.apk`. For upgrades, use a higher build
   version signed by the same certificate. Keep an existing install until
   you have accounted for its local data.
3. Start OpenSkyLight. It creates an offline Family calendar on first run.
   Add profiles and a parent PIN in Settings. Set Home as the default view
   and choose the widgets you want.
4. Set up Google Calendar, external feeds, companion and kiosk features as
   described below. See the status matrix for tests that still need the
   actual tablet and devices on the local network.

[Android workflow runs](https://github.com/jn90z/openskylight-apkport/actions/workflows/android.yml)
[Verification status](ANDROID_STATUS.md) · [Companion setup](ANDROID_COMPANION.md) · [Performance test](ANDROID_PERFORMANCE.md)

### Google Calendar

Android uses Google Play services to authorize Calendar access for an account
on the device. In the Google Cloud project, enable the Calendar API, configure
the OAuth consent screen, and create an **Android** OAuth client with package
`com.jn90z.openskylight` and the installed APK's **signing SHA-1**. Add your
account as a test user if the consent screen is in testing. Use the SHA-1 from
`apksigner verify --print-certs app-release.apk`. Debug and release APKs signed
by different keys need separate OAuth clients.

On the tablet, choose **Settings → Google Calendar → Add Google account**, choose
the account and grant Calendar permission. Select the calendars to sync. Google
Play services manages refresh and app access tokens stay in memory. Read-only
remote calendars retain their access settings. Use **Sync now** to refresh on
demand or reconnect a removed or expired account in Settings.

### Cameras, feeds and kiosk

- Use `rtsp://` or `rtsps://` cameras that provide H.264 with SDP parameter sets. Select a
  matching substream on the camera if its default stream uses another codec.
  Media3 uses RTP over TCP and allows one active video decoder. RTSPS requires
  a certificate the tablet trusts. Camera URLs are
  encrypted with Android Keystore.
- Weather city search uses Open-Meteo. News uses the existing RSS presets.
  BirdNET tiles connect to the BirdNET-Go service and request its species
  images. ICS feeds accept HTTP(S) URLs as on desktop.
- Choose a photo directory through Android's folder picker. OpenSkyLight
  retains read permission and downsamples large photos before display.
- The kiosk uses immersive mode. Swipe from a system edge to show navigation.
  Screensaver idle time and the scheduled sleep window are configurable in
  Settings. Scheduled sleep dims the panel and displays the sleep overlay. A
  touch temporarily wakes the UI. Android controls actual display power.
- For unattended startup on Android 10 and newer, choose OpenSkyLight under
  Android **Settings → Apps → Default apps → Home app**. Use **Launch on
  startup** in OpenSkyLight when using its boot receiver. Home app selection is
  reversible in Android settings. Tablet owner controls are required for
  managed lock task mode.

## Build and verify

Use Node 22, JDK 21 and Android SDK Platform 35 with Platform Tools and Build
Tools 34.0.0/35.0.0. From the repository root:

```bash
npm ci --ignore-scripts
npm run typecheck
npm rebuild better-sqlite3
npx --no-install vitest run
node scripts/android-prepare.mjs
cd android
./gradlew testDebugUnitTest assembleDebug
# With a compatible emulator or USB-authorized tablet attached:
./gradlew :app:connectedDebugAndroidTest
```

The Android preparation step bundles both React apps and the backend, then runs
Capacitor sync against the checked-in Gradle project. Node is used at build time.
The tablet runs one Android System WebView, a small QuickJS backend, native
SQLite (WAL and foreign keys), and a bounded native HTTP listener when companion
is enabled. The trusted backend runs the shared validation and services for
both the WebView and authenticated companion requests.

### Release signing

Use an OpenSkyLight-specific key, keep a secure backup, and retain it for every
upgrade. The APK workflow supports the same persistent-key pattern as
`android-audio-book` and verifies the certificate as in `chess-plus-plus`.

Configure repository Actions secrets `OSL_KEYSTORE_BASE64`,
`OSL_KEYSTORE_PASSWORD`, `OSL_KEY_ALIAS`, and `OSL_KEY_PASSWORD`. Set repository
Actions variable `OSL_CERT_SHA256` to the expected certificate digest in
hexadecimal without colons. Run the workflow on the `android` branch. It
packages and checks the release signature after the debug tests pass. The
workflow run number becomes the Android version code. Local releases accept
`OSL_KEYSTORE_PATH`, the three password/alias variables, and
`OSL_VERSION_CODE` in the Gradle environment.

Source SQLite tables and migrations are shared with desktop. The APK starts a
new private database. Copying desktop files, Google grants, or camera credentials
between platforms is outside the setup flow. Uninstalling clears Android data.
