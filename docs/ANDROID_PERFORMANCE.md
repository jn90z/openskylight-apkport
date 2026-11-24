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

## Review of the November 24 tablet diagnostic capture

The capture identifies an SM-T510 running Android 11. The app process has
105,633 KB reported total PSS, 102,576 KB RSS and 55,670 KB swap PSS.
Its summary reports 27,012 KB graphics memory and SQLite reports 630 KB used
with a 176 KB database. Android's total PSS accounting in this capture includes
swap, so these totals must not be added as independent resident allocations.

The capture includes one app memory sample and omits a separate WebView
renderer memory dump. It cannot establish combined steady-state usage,
a memory leak, or the 24-hour memory target. The empty crash file contains
no recorded crash, and does not establish that the process never restarted.

Graphics records 66 janky frames out of 70, a 65 ms median, and a 1,300 ms
99th percentile. This small sample demonstrates poor responsiveness during
the sampled activity. It needs a repeatable release-build measurement with
a reset graphics counter and an identified interaction before attributing
the delays to an app component. GPU histogram values at the final bucket
are insufficient to assign a precise GPU duration.

The CPU report covers 14:33:02–14:37:29 device time, before the captured
Google authorization attempts around 14:40. OpenSkyLight is absent from
that report. Gmail leads its process list at 20%, while kswapd0 accounts
for 1.4%. These values provide system context but cannot measure the app's
idle CPU. Battery snapshots likewise lack a controlled app-only baseline.

For release acceptance, record the app and renderer PSS at startup, 10 minutes,
one hour and 24 hours. Reset gfxinfo before a fixed calendar scrolling and
editing sequence, then compare the signed release to the debug capture.
