# Low-memory Android target

Target SM-T510, 2 GB RAM, Android 9 or newer. Targets are not measurements.

- Use system WebView, one activity, no Electron, Node runtime, embedded server or ffmpeg.
- Use native SQLite with indexed date-range queries; avoid loading entire history.
- Keep React Query cache bounded and avoid polling while hidden.
- Lazy-load optional media, settings and calendar views; stop camera and animation when hidden.
- Disable optional video and photo-heavy tiles by default on low-memory devices.
- Use Android lifecycle callbacks for sync and screen state. Do not hold a permanent wake lock.
- Measure combined app and WebView renderer PSS using adb dumpsys meminfo, and CPU using adb shell top.
- Test cold launch, 24-hour idle, calendar scrolling, restart persistence and low-memory recovery.
- Goals: idle PSS <150 MB, active <250 MB, APK <30 MB, idle CPU <2%, cold launch <5 s.
- First APK is a packaging prototype only: backend handlers remain unimplemented.
- CI generates the native Gradle project in a disposable checkout; dependency lock and checked-in native project remain follow-up work.
