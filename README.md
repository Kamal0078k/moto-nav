# MotoNav Android simulator

This is the repository's second and final code project. It computes fixture and live-GPS navigation locally, can request Google Routes API `TWO_WHEELER` initial and safely bounded replacement routes when a restricted local key is configured, and previews the exact 128×160 portrait TFT raster at nearest-neighbour scale. Fixture mode starts without a key, network, location permission or BLE connection.

## Run

Use Android Studio with JDK 17, or run from this directory:

```sh
./scripts/build-android.sh
./scripts/install-android.sh
```

`build-android.sh` runs host unit tests and creates `app/build/outputs/apk/debug/app-debug.apk`. `install-android.sh` installs that existing APK on the selected `adb` device; set `ANDROID_SERIAL` when more than one device is connected.

For tests only:

```sh
./scripts/test-android.sh
```

## Google motorcycle route setup

Keep `google.mapsApiKey` in the ignored `mobile-app/local.properties`; an empty or absent value builds fixture-only mode. The local key is injected into debug builds only; release artifacts are deliberately fixture-only so they cannot disclose it. Before adding a key, enable billing, confirm eligible India pricing, enable Routes API and Places API (New), cap the Routes API Cloud quota at 200 requests/day, and restrict the key to exactly those APIs plus this Android package and signing certificate. Never put the key in source, logs, screenshots or test data.

Use **Route setup** to choose the explicit source, select Google places for the origin and destination, enter coordinates manually, optionally take one foreground origin fix, and manage locally saved user-entered coordinate destinations. Place Autocomplete and its follow-up place lookup use a billing session separate from the Routes request counter. Google-selected coordinates remain session-only and cannot be saved as a local destination. A Routes response opens an overview and beta warning; choose **Replay** for deterministic local playback or **Start live GPS** for foreground location-driven progress. Neither mode makes another hosted routing request. Diagnostics show the persistent billing-month Routes attempt count and reset date.

### Plan 7 device verification

1. In Google Cloud, enable billing, Routes API and Places API (New), set the Routes API daily quota to 200, and restrict the key to those two APIs plus Android package `dev.motonav.simulator` and the debug certificate SHA-1 reported by `./gradlew signingReport`.
2. Copy `local.properties.example` to the ignored `local.properties`, set `google.mapsApiKey` there, and do not share that file or its value.
3. Run `./scripts/build-android.sh`, then `./scripts/install-android.sh`. These install the credential-bearing debug APK; release builds intentionally remain fixture-only.
4. In **Route setup**, choose an origin and destination through the Google place selectors. Confirm each selection fills its coordinate fields, then choose **Google motorcycle route** and load a short familiar route once. Confirm the local Routes count rises by one; Places usage is separate in Google Cloud. The overview must show distance, duration, endpoints, step count, `Google Maps`, and the motorcycle beta warning.
5. Tap **Start replay**. Replay to completion, restart it, and repeat once with networking disabled. The local request count must not change and the loaded route must continue to replay from memory.
6. Connect the ESP32 and replay. Confirm the enlarged maneuver, distance and five-pixel route update across the portrait TFT. This custom layout does not render an attribution band; do not use it for Google-derived guidance unless your applicable terms permit that omission.
7. For the controlled rejection check, temporarily replace the key's Android restriction with a deliberately incorrect package or certificate, wait for the restriction to propagate, and make exactly one visible load attempt. Confirm an authentication/configuration error and a one-count increase, then restore the correct restriction before any further request. Never remove both application and API restrictions.
8. Remove `google.mapsApiKey`, rebuild, and confirm fixture mode still works without network. Record Cloud quota/restriction evidence, the before/after counts, a phone screenshot without precise home coordinates, and the TFT observation in the Plan 7 acceptance evidence.

## Live locked-screen navigation

Load one Google motorcycle route, then choose **Start live GPS** while the app is visible. Android requests precise foreground location, the version-appropriate Bluetooth permission and, on Android 13+, explains the notification permission. Approximate location is deliberately rejected because it cannot reliably separate nearby roads or turn boundaries. If location services are disabled, the app offers the platform settings once. Notification denial does not block navigation, although Android may expose the notice only through Task Manager.

The active session moves into a typed `location|connectedDevice` foreground service, so GPS processing and TFT updates continue when the screen locks, the activity is recreated or the task is removed from recents. Its low-noise ongoing notification shows destination, maneuver distance and GPS/TFT state; tapping it returns to the session, and **Stop** releases location and BLE work. The in-app **Stop live GPS** control does the same. Arrival stops GPS immediately, retains the arrived state for 30 seconds, then disconnects the display and ends the service.

The app does not request background-location access, auto-start after boot, use a wake lock or override a force-stop. If Android kills the whole process, Google route geometry is not restored from disk: the next launch says `Previous navigation was interrupted`, and you must explicitly load a fresh route. Removing the task from recents is different from force-stop and intentionally leaves an already-started navigation service running.

Accepted fixes advance a local route index. The app rejects stale, inaccurate, duplicate, backward-time and implausible-jump fixes, searches a bounded corridor after acquisition, and retains the last committed progress during GPS loss. Measured movement at 0.5 m/s or above advances immediately; slower or speed-less travel accumulates displacement from a stable anchor until it reaches a threshold between 3 m and 5 m. The threshold still considers GPS accuracy but is capped at 5 m so a large reported accuracy radius cannot produce 20 m display jumps. After at least two consistent fixes and roughly 10 m of reverse evidence, genuine backtracking rewinds route progress, increases remaining distance and restores earlier maneuver boundaries; isolated backward jitter remains ignored. True stationary noise still freezes progress. `GPS LOST` requires 20 seconds without an accepted fix, and valid off-route fixes are reported through rerouting diagnostics rather than masquerading as lost GPS. Location decisions and distances are shown without logging coordinates, and no live history is written to disk.

The route viewport is heading-up: it rotates so the physical direction of the top of the portrait phone always points toward the top of the TFT, while the horizontally centred triangle remains fixed five-sixths down the route area at row 94 and points up. The forward view defaults to 250 m and adapts between 200 m near slow or tight turns and 300 m at higher speed on a longer straight. This continues while the phone is stationary or locked; circular smoothing and a three-degree change gate suppress magnetometer jitter. No sensor permission is required. Keep the phone away from strongly magnetic mounts if the map orientation is consistently offset.

When **Now playing** is enabled and Android notification access is granted, the active song scrolls at 2× size in the live-route screen's 24-pixel-tall top status bar. Incoming or active phone calls overlay the right side with matching large text and a 16×16 handset. The route screen deliberately omits its clock because the motorcycle already provides one. Its bottom panel keeps the maneuver and next-turn distance centred inside a rectangle, with provider-backed ETA on the left and remaining trip distance on the right. When duration is unavailable, ETA displays `--`. The idle screen still keeps its song line below the clock.

Sustained movement off route—three usable fixes spanning at least five seconds, outside `max(25 m, 2 × accuracy)` and with credible movement—can start one replacement request from the latest accepted raw location to the original destination. The existing route stays on the phone and TFT in `REROUTING` state until the response is completely validated and rematched. A 30-second cooldown, one bounded transient retry, a 20-per-session cap, generation-safe callbacks and local 200/day plus 6,000/month stops prevent request storms. Network/auth/quota/no-route failures retain the old route; **Retry route** is available when policy permits it. Ordinary location and display updates do not contact Google.

For a quota-free check, open **Indoor reroute lab · no Google calls**. Its controls inject a single outlier, sustained deviation with delayed replacement, delayed network failure, recovery, cancellation and destination change through the same rerouting controller used by the foreground service. This synthetic lab requires no key, network or location permission.

For the physical check, start while the app is visible, lock the phone for ten minutes and walk a short safe part of the loaded `TWO_WHEELER` route. Do not interact with the phone while operating a motorcycle. Verify continuing TFT updates, stable stationary behavior, ordered close turns, GPS-loss recovery, BLE catch-up and notification Stop. Google Cloud's Routes count must remain unchanged after the initial route load. Also repeat once after disabling battery optimizations only if the normal run demonstrates a repeatable manufacturer-specific failure.

## Simulator

Choose a bundled right turn, left turn, consecutive junctions, GPS loss/recovery or labelled missed-turn replacement route. Start, pause, single-step and restart are deterministic. The speed slider ranges from 1.0 to 30.0 m/s.

The pure Kotlin navigation package owns route models, cumulative distance/projection/interpolation, upcoming slicing and heading-up route rendering. The display package rasterizes the same Adafruit glyphs, bottom-centred 24×24 maneuver symbol and large distance, five-pixel route, enlarged lowered rider marker, top status bar and destination flag used by the ESP32. `FrameCodec` encodes/decodes protocol v3 and fragments it for GATT writes; its test reads the shared vector at `docs/protocol/golden-frames.json`. Google frames retain their source flag, but this personal TFT layout no longer reserves an attribution band.

Offline simulation still starts without a permission prompt. Bluetooth permissions are requested only after **Scan / select** is tapped; Android 12+ uses Nearby devices, while Android 8–11 uses the location permission required by their BLE scan API.

## ESP32 display connection

The app now distinguishes a Bluetooth link from a validated display session. If protocol validation fails, it keeps an actionable firmware error on screen instead of reconnecting forever; upload the current `arduino-sketch/display2/display2.ino`, restart the board and scan again. For a later link loss, the reconnect status retains the GATT or frame-write reason. With the phone attached, `adb logcat -s MotoNavBle:D '*:S'` prints the same diagnostic.

1. Upload the current firmware and leave it in BLE receiver mode (`b` over serial if needed). The TFT should show the MotoNav idle face while advertising.
2. In the app, tap **Scan / select**, grant the version-appropriate Bluetooth permission and enable Bluetooth if prompted.
3. Tap `MotoNav Display` in the explicit device list. The app validates the protocol-v3 dimensions, frame size and heading-up-map capabilities before sending anything.
4. Start or step a fixture. The newest frame is sent every 400 ms, including while paused or stationary. Writes remain serialized, so a slow fragmented transfer finishes before the next frame starts.
5. Tap **Disconnect** to stop automatic reconnection. Unexpected loss uses bounded 1, 2, 4, 8, 16 and 30 second retry delays and sends the newest full frame immediately after recovery.

The status line reports scanning, connecting, protocol validation, negotiated MTU, last sent sequence, Bluetooth-off, permission and reconnection states. Writes use response and remain serialized across all fragments.
