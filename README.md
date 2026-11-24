# Helios Sentinel Companion (Android)

A complete Android Studio project (Kotlin + Jetpack Compose) that monitors,
controls and analyses the **Helios Sentinel DS2001** dual-axis solar tracker
over WiFi, plus matching ESP32-S3 firmware.

## Features

**Dashboard**
- Live tracking mode, pan/tilt angles, response time and connection status
- **Sun position** for your location (azimuth/elevation) and the ideal servo angles
- **Tracking error**: how many degrees the panel is off the real sun, colour-coded
- **Sky map**: top-down view of the sun and where the panel is pointing
- **Light heat-grid** of the 4 LDRs plus a plain-English light balance ("18% brighter on the right")
- **Sensor health warnings**: saturated sensors, one sensor shaded/faulty, all-dark

**Control**
- Auto-track / Manual toggle, pan/tilt sliders
- **Point at the sun**: aims the panel at the calculated sun position (great for checking calibration)
- **Stow**: parks the panel flat, facing up, for wind or rain

**History**
- Time ranges: 15 min / 1 h / 6 h / All
- **Summary stats**: span, total panel movement, time in manual, average light, per-sensor min/avg/max,
  average and worst tracking error
- Charts: panel angle, **tracking error over time**, all four light sensors
- **CSV export** (via Android's file picker), with derived columns ready for pandas/Excel:
  `timestamp, epoch_ms, mode, pan_deg, tilt_deg, ldr_* , ldr_total, balance_horizontal,
  balance_vertical, sun_azimuth_deg, sun_elevation_deg, expected_pan_deg, expected_tilt_deg,
  tracking_error_deg`
- Stores the latest 10,000 readings (~5.5 h at 2 s) on the phone, using cheap append-only writes

**Settings**
- **Find tracker on this network** (mDNS auto-discovery plus a direct check of the hotspot address)
- Threshold and angle limits are **sent to the tracker** when you save, and saved in its flash;
  "Load current values from tracker" reads them back
- Location (type it in, or **use the phone's location**) and panel orientation (compass direction at pan 90°)
- **Alerts**: notification if the tracker goes offline or a sensor looks faulty
- **Background monitoring**: keeps logging and alerting with the app closed (shows a notification
  with a Stop button). Without it, polling pauses in the background to save battery.

**Firmware** (`firmware/HeliosSentinelFirmware/HeliosSentinelFirmware.ino`)
- Same REST API, mDNS name `helios-sentinel.local`, settings saved to flash across reboots
- 8-sample ADC averaging, step size that scales with the light imbalance, holds still at night
- Validates every request (bad input is rejected with HTTP 400 and changes nothing)
- `INVERT_PAN` / `INVERT_TILT` flags if your servos move away from the light

## What has been verified (and what hasn't)

| Check | Result |
|---|---|
| Sun-position maths vs NREL SPA (pvlib) at 7 locations incl. near-zenith & horizon | within **0.005°** |
| App unit tests (`app/src/test`): solar geometry, stats, anomaly rules, CSV format | **16/16 pass** |
| Data layer, repository and all ViewModels compiled with the real Kotlin compiler against stand-in Android/OkHttp/DataStore APIs | **compiles, no errors** |
| Firmware compiled with `g++ -Wall -Wextra` against stand-in ESP32 libraries | **no warnings** |
| Firmware functional tests (HTTP API, validation, reboot persistence, tracking behaviour) | **28/28 pass** |
| Full Android build (Compose UI, resources, manifest) | **not yet run**: the first GitHub Actions build (or Android Studio sync) is the real test |

Run the app's unit tests in Android Studio by right-clicking `app/src/test` → *Run tests*
(or `./gradlew test`).

## Building the app without Android Studio (no disk space needed)

GitHub builds the APK in the cloud for free. Nothing is installed on your PC.

1. Sign in at github.com → **New repository** → name it (e.g. `helios-sentinel-app`) → Private → **Create**.
2. On the empty repo page click **uploading an existing file**. Unzip this project, open the
   `HeliosSentinelApp` folder, select **everything inside it** and drag it onto the page →
   **Commit changes**.
3. Open the **Actions** tab. If you see a green button to enable workflows, click it. A
   **Build APK** run starts on every upload (or click *Build APK → Run workflow*). It takes
   about 5–8 minutes.
4. When it shows a green tick, open the run → **Artifacts** → download **HeliosSentinel-APK**
   (a zip containing `app-debug.apk`).
5. Copy the APK to your phone (or download it there directly while signed in to GitHub), open it,
   and allow "Install unknown apps" when Android asks.

If the `.github` folder didn't upload (some browsers skip hidden folders), add the workflow
by hand: **Actions → set up a workflow yourself**, paste the contents of
`.github/workflows/build-apk.yml`, and commit.

If a run fails (red ✗), open it, click the failed step, and copy the error text. It pinpoints
the problem exactly.

## Opening the project in Android Studio (if you have the space)

1. Unzip and open the `HeliosSentinelApp` folder in **Android Studio** (*File → Open*) and let
   Gradle sync. The Gradle wrapper (8.7) is included.
2. Run on a device with **Android 8.0 (API 26)** or newer. On Android 13+ the app asks once
   for notification permission (for alerts).

Toolchain: AGP 8.5.2, Gradle 8.7, Kotlin 1.9.24, Compose compiler 1.5.14, compileSdk/targetSdk 34.
If a newer Android Studio offers the AGP Upgrade Assistant, accepting it is fine.

No API keys, Firebase or Google Play Services are needed.

## Flashing the firmware

1. Open the `.ino` in the Arduino IDE you use for the ESP32-S3.
2. Install **ESP32Servo** from Library Manager.
3. Set the `PIN_*` constants to your wiring.
4. Flash it. The board starts a hotspot **`HeliosSentinel`** (password `sentinel123`) at
   `192.168.4.1`. Join it from the phone, open the app, and the Dashboard should go green within
   seconds (that's the default address). To use your home WiFi instead, set `USE_STATION_MODE`
   to `true`, fill in your WiFi details, and use **Find tracker** in Settings.
5. If the panel moves *away* from the light, flip `INVERT_PAN` and/or `INVERT_TILT`.

## Calibrating the sun features

The sun maths needs to know how the tracker is mounted:
- **Panel direction at pan 90°**: the compass direction the panel faces when the pan servo is at
  90°. Default 180 (due south), which is right for Pakistan/UAE if you point it south.
- **Tilt**: 0° = horizon, 90° = straight up, above 90° tips over to the opposite horizon. This
  lets two 180° servos reach the whole sky.

Then press **Point at the sun** on a sunny day: if the panel's shadow is square-on, the
calibration is right. The Dashboard's tracking-error number then tells you how well the LDR
auto-tracking is doing. That's a good metric for the DS2001 report.

## REST API contract

| Method | Path | Request body | Response |
|---|---|---|---|
| GET | `/api/status` | – | `{"mode","angleX","angleY","ldrTopLeft","ldrTopRight","ldrBottomLeft","ldrBottomRight","uptimeMs"}` |
| POST | `/api/control` | `{"mode":"manual","angleX":F,"angleY":F}` or `{"mode":"auto"}` | updated status |
| GET | `/api/config` | – | `{"ldrThreshold","pollIntervalMs","minAngle","maxAngle"}` |
| POST | `/api/config` | any subset of the config fields | saved config, or HTTP 400 |

mDNS service type: `_helios._tcp` on port 80. Plain HTTP on the local network with no login,
which is fine for a lab or hobby network but not meant for the open internet.

## Project structure

```
app/src/main/java/com/heliossentinel/companion/
├── MainActivity.kt, HeliosApp.kt   # entry point, service locator, app-visible tracking
├── domain/        # pure Kotlin, unit-tested: SolarCalculator, TrackingAnalytics,
│                  # AnomalyDetector, CsvExporter
├── data/
│   ├── model/      # TrackerStatus, Reading, ControlCommand, TrackerConfig
│   ├── remote/     # TrackerApiService (OkHttp), TrackerDiscovery (mDNS)
│   ├── local/      # HistoryStore (JSON Lines, append-only, compacted)
│   ├── prefs/      # AppSettings + SettingsRepository (DataStore)
│   ├── location/   # LocationHelper (framework LocationManager, no Play Services)
│   └── repository/ # TrackerRepository: lifecycle-aware polling, alerts, commands
├── notifications/  # AlertNotifier
├── service/        # MonitorService (optional background monitoring)
└── ui/             # theme, navigation, components (charts, sky map, light grid), 4 screens
app/src/test/        # JUnit tests for the domain layer
firmware/            # ESP32-S3 Arduino sketch
```

No Hilt, Room, Retrofit or third-party chart library. Everything is AndroidX + OkHttp, which keeps
the first sync fast and avoids version conflicts.
