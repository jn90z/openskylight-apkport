# Android verification status

The Android branch uses the Linux kiosk as its behavior reference. The matrix
distinguishes automated coverage from live providers and SM-T510 acceptance.

| Area | Automated coverage | Acceptance on tablet |
|---|---|---|
| Calendar and family profiles | Shared service tests and Android instrumented RPCs, including repeat across DST | Create, move, and delete one occurrence, following occurrences, and the full series. Restart offline and confirm edits |
| Chores, rewards, grocery lists and meals | Shared tests and Android SQLite writes through packaged services | Complete/uncomplete recurring chores, redeem and grant rewards, clear grocery items, edit meals and restart |
| Parental authorization | Shared PIN tests and Android lock, unlock, guarded route tests | Confirm locked Settings, Android resume behavior, and PIN protection for companion options |
| Google and ICS | Google mapping, ICS mapping, recurrence and outbox logic covered by shared tests | Register APK certificate, grant Google access, select calendars, push and pull edits, disconnect, verify refresh and conflict handling, then import and refresh an ICS feed |
| Weather, RSS and BirdNET | Shared parsing, URL and provider service tests | Verify live forecast and search, RSS refresh, and BirdNET-Go detections and images |
| Companion | Shared contract tests and native HTTP request bounds | Pair from a second device, verify bidirectional writes, revoke, reconnect Wi-Fi, and check screen-off reachability |
| RTSP | Shared settings/URL semantics | Verify H.264 over TCP with a real camera and decoder teardown during sleep and Settings. Verify RTSPS with a valid certificate. Android limits playback to one active decoder |
| Screensaver and kiosk | Shared schedule and idle tests | Grant photo access, reboot, verify images, check touch wake, Home launcher and power usage. Android scheduled sleep uses dimming |
| APK and performance | Gradle, typecheck, test and signature checks in CI | Install/update on API 28–30 Samsung firmware, measure startup and power, and pass the 24-hour soak |

The target tablet, paired phone, camera, BirdNET host, and a Google OAuth
project with the release certificate are required for live acceptance. See the
workflow for the test results tied to a specific APK and
[performance instructions](ANDROID_PERFORMANCE.md) for the long-running test.
