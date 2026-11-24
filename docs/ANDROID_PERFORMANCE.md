# SM-T510 performance verification

Test on the 2 GB SM-T510 with the exact APK intended for release. Record the APK
SHA-256, Android security patch, WebView version, brightness, screen
resolution, power source, Wi-Fi signal and enabled tiles. Run a fresh install
and an in-place upgrade from the previous signed APK when one is available.

```bash
pkg=com.jn90z.openskylight
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell dumpsys webviewupdate
adb shell am start -W -S -n "$pkg/.MainActivity"
adb shell dumpsys meminfo "$pkg"
adb shell dumpsys activity processes
adb shell top -b -n 5 -d 2
adb shell dumpsys batterystats "$pkg"
```

Use `dumpsys activity processes` to identify the separately tracked WebView
renderer PID, then run `adb shell dumpsys meminfo PID`. Record total PSS for the
app and its WebView renderer. Capture screenshots or a video for cold launch
until calendar data is visible. The `am start -W` time only measures Activity
startup.

| Scenario | What to measure |
|---|---|
| Cold launch, offline and online | Repeat five times and record time to populated kiosk |
| Idle, no optional media | Sample PSS and CPU after 10 minutes |
| Active scrolling and edits | Import a representative calendar with at least 500 events and recurring series. Scroll and edit for 10 minutes then record peaks |
| Media and network | Run a camera, RSS and BirdNET tiles with companion clients. Rotate the device, open Settings, and leave Home to confirm playback releases its decoder |
| Scheduled sleep and wake | Confirm brightness drop, touch override, and scheduled return. Measure power at the USB supply if available |
| 24-hour soak | Start at normal operating temperature. Sample PSS, CPU and thermal state hourly. Check sync, companion and camera recovery after an offline period |
| Restart and memory pressure | Force stop and relaunch, confirm saved edits. Then background the app, open several apps and return. Confirm the kiosk recovers if WebView is reclaimed |

Initial targets are **under 150 MB combined idle PSS, under 250 MB active PSS,
under 2% idle CPU averaged over five minutes, under five seconds to visible
calendar, and under 30 MB APK size**. Compare the first and last samples of the
soak. Investigate persistent PSS growth, thermal throttling, ANRs, renderer
restarts and interrupted sync. Report measured values alongside the scenarios.
