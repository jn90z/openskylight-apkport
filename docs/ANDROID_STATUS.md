# Android port status

Date: 2026-11-24

## Completed
- Created `android` branch from `main`.
- Added a separate Vite build for the existing React UI.
- Added Capacitor configuration with Android application ID.
- Added Android-only HTML and bootstrap entrypoint.
- Added a fail-closed compatibility bridge for the Electron IPC contract.

## Important limitations
This is **not yet an installable or functional Android APK**. No native Android
Gradle project, bundled native SQLite backend, Google authentication or working
IPC channel handlers are included. The existing renderer relies on Electron IPC;
the stub deliberately responds `ANDROID_NOT_IMPLEMENTED` to prevent misleading
UI results. There are no successful Android build or device test results.

Next work: adopt an Android-only dependency set, install Capacitor and generate
the Android project, then implement the native bridge and persistence.
