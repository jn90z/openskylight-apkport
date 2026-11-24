# OpenSkyLight Android port

This branch is the **Android development branch**, based on the upstream Electron project.
The initial commit is **scaffolding only**, not a working calendar APK.

## Target

- Initial test device: Samsung Galaxy Tab A 10.1 SM-T510
- Application ID: `com.jn90z.openskylight`
- Android WebView: Capacitor
- UI: existing React / TypeScript renderer, built separately from Electron
- Goal: offline-capable standalone calendar without an external desktop/server

## Architecture

The Electron renderer calls `window.osl.invoke(channel, payload)` through
`src/renderer/src/api/client.ts`, and subscribes to `push:` notifications.
The Electron preload implements this bridge with IPC. Android needs a
corresponding native implementation using Capacitor plugins and/or Kotlin.

The current `android-bridge.ts` is intentionally a fail-closed placeholder.
It reports `ANDROID_NOT_IMPLEMENTED` instead of returning fake records or
pretending that writes were saved. Data will **not** load until native handlers
exist.

## Initial setup

With Node.js and Android Studio installed:

```sh
npm install
npm install --save @capacitor/core
npm install --save-dev @capacitor/cli @capacitor/android
npx vite build --config android.vite.config.ts
npx cap add android
npx cap sync android
npx cap open android
```

The Android project and APK build are not yet checked in. Capacitor dependency
versions must be pinned and a lockfile committed before CI packaging is enabled.
The Electron `postinstall` script and `better-sqlite3` native dependency
may require a separate, Android-only dependency installation strategy.

## Implementation milestones

1. Add reproducible Android-only dependency installation and committed Gradle project.
2. Implement native `window.osl` channel handlers, matching
   `src/shared/ipc/contract` response schemas and push events.
3. Replace Electron/Node `better-sqlite3` persistence with Android SQLite,
   including schema migration and transaction support.
4. Add settings, people, calendars, events, chores, lists, and rewards.
5. Port Google OAuth and Calendar sync (secure token storage, redirect callbacks).
6. Adapt weather, media, screensaver, kiosk and other platform services.
7. Add Android CI build, device testing, installation documentation and
   long-running performance tests.

Do not treat a successful WebView build as proof that calendar functionality
works. Verify offline edits, restart persistence, sync conflict handling and
Google authorization on an actual SM-T510 before publishing an APK.
