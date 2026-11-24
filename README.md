# OpenSkyLight for Android

An open-source family calendar and touchscreen dashboard, ported from the original [OpenSkyLight desktop project](https://github.com/jn90z/openskylight-apkport/tree/main). No subscription or mandatory OpenSkyLight cloud account.

This branch brings the shared React interface to Android tablets using Capacitor and native Android services rather than Electron. Designed with the Samsung Galaxy Tab A (SM-T510, 2 GB RAM) in mind.

[Download releases](https://github.com/jn90z/openskylight-apkport/releases) · [Android builds](https://github.com/jn90z/openskylight-apkport/actions/workflows/android.yml) · [Setup guide](docs/ANDROID_PORT.md) · [Verification status](docs/ANDROID_STATUS.md)

## Requirements

- Android 9 (API 28) or newer.
- Updated Android System WebView and Chrome.
- Google Play services for Google Calendar authorization.
- Wi-Fi for synchronization, weather and optional phone companion access.

## Features

The port includes the shared family dashboard, day/week/month/agenda calendar views, local events and recurrence, family profiles, Google Calendar integration, ICS subscriptions, chores and rewards, grocery and custom lists, meal planning, weather, photo screensaver, PIN-protected settings, kiosk controls and the LAN phone companion. Camera and BirdNET integrations are also included. See the [verification matrix](docs/ANDROID_STATUS.md) for platform-specific limitations and outstanding physical-device tests.

## Interface previews (upstream desktop screenshots)

The customizable home screen — a big clock and a 3-day weather glance in the
header, with drag-and-drop tiles for today's events, the week ahead, meals,
chores progress, and star balances:

![Home screen](docs/screenshots/home.png)

At sunset it switches itself to a warm dark palette (here with a live news tile
swapped in):

![Home screen in dark mode](docs/screenshots/home-dark.png)

| Week view with the meal strip | Touch-first event editor |
| --- | --- |
| ![Week view](docs/screenshots/week.png) | ![Event editor](docs/screenshots/editor.png) |

| Month view | Chore board with star rewards | Family lists |
| --- | --- | --- |
| ![Month view](docs/screenshots/month.png) | ![Chores](docs/screenshots/chores.png) | ![Lists](docs/screenshots/lists.png) |

The phone companion app (pair by QR, served by the display itself — no cloud):

| Lists on your phone | Chores on your phone |
| --- | --- |
| ![Companion lists](docs/screenshots/companion-lists.png) | ![Companion chores](docs/screenshots/companion-chores.png) |



## Install or update

Download the permanently signed APK from Releases when available, or the `OpenSkyLight-Android-signed` artifact from a successful Android workflow. Extract the artifact ZIP before installing.

```powershell
adb install -r app-release.apk
```

Alternatively open the APK on the tablet and allow installation from that source. Updates require the same signing certificate and a compatible version code. Moving from a differently signed debug APK requires uninstalling it first, which deletes local app data. Back up important data before uninstalling.

## Google Calendar setup

1. Create or select a Google Cloud project and enable the Google Calendar API.
2. Configure the OAuth consent screen and add your Google account as a test user if the project is in testing mode.
3. Create an **Android** OAuth client with package name `com.jn90z.openskylight` and the installed APK signing certificate's SHA-1 fingerprint.
4. In OpenSkyLight, open Settings → Google Calendar → Add Google account and grant Calendar access.
5. Choose calendars and use Sync now to verify synchronization.

Use the permanent release certificate for official builds. Android authorization uses Google Play services, not the desktop OAuth client-secret flow. Calendar selection and synchronization should be verified on your device.

## Tablet kiosk and companion

Use landscape orientation for the wall display. Immersive mode, photo screensaver and scheduled dimming are available in Settings. Android controls actual panel power. For unattended startup, see the [Android setup guide](docs/ANDROID_PORT.md).

Enable the phone companion in Settings and pair a phone using the QR code on the same trusted Wi-Fi network. See [companion setup](docs/ANDROID_COMPANION.md).

## Architecture

- Shared React and TypeScript UI running in Android System WebView through Capacitor.
- QuickJS backend and native SQLite with WAL and foreign keys.
- Native Android services for authorization, media, secure storage and kiosk behavior.
- Bounded native HTTP listener for the optional LAN companion.
- Node.js is used at build time; Electron is not bundled into the APK.

## Build from source

Use Node 22, JDK 21, Android SDK Platform 35 and the required build tools.

```bash
git clone --branch android https://github.com/jn90z/openskylight-apkport.git
cd openskylight-apkport
npm ci --ignore-scripts
npm run typecheck
npm rebuild better-sqlite3
npx --no-install vitest run
node scripts/android-prepare.mjs
cd android
./gradlew testDebugUnitTest assembleDebug
```

On Windows use `gradlew.bat` instead of `./gradlew`. See [performance testing](docs/ANDROID_PERFORMANCE.md) and [Android build instructions](docs/ANDROID_PORT.md).

## Release signing

GitHub Actions supports persistent release signing using repository secrets `OSL_KEYSTORE_BASE64`, `OSL_KEYSTORE_PASSWORD`, `OSL_KEY_ALIAS` and `OSL_KEY_PASSWORD`, plus repository variable `OSL_CERT_SHA256` (certificate digest without colons). Never commit the keystore or passwords. Use the signed APK for normal installation; debug APKs are for testing.

## Project status and attribution

This is an Android port of OpenSkyLight, not the original desktop distribution. Automated tests do not replace physical tablet verification. Consult the verification matrix for current coverage.

The original desktop documentation and source remain on the [main branch](https://github.com/jn90z/openskylight-apkport/tree/main). Original authors and contributors retain their attribution under the repository license. See [LICENSE](LICENSE).
