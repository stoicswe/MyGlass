# MyGlass

A modern, open re-creation of the original **MyGlass** experience for the **Google
Glass Explorer Edition**: forward notifications from an iPhone to Glass so they
appear as popups on the device, and keep the Glass clock in sync with the phone.

Built using the resources from the [GlassKit](https://github.com/glasskit) revival
project and the original [Google Glass GitHub](https://github.com/googleglass)
samples, which are checked out locally as `GlassKit/` and `GoogleGlass/`.

## What's in here

| Directory | What it is |
| --- | --- |
| `glass-app/` | The Glassware (Android). It listens for iPhone notifications over Bluetooth LE and shows them as Glass notifications/popups, and it sets the device clock. |
| `ios-companion/` | The iPhone companion (SwiftUI). It advertises the standard Bluetooth **Current Time Service** so Glass can read your phone's clock, and documents the ANCS pairing flow. |

## Architecture

```
   iPhone                                                  Google Glass (XE)
┌──────────┐   Apple Notification Center Service (ANCS)   ┌──────────────────────┐
│  iOS     │ ─────────── Bluetooth LE ───────────────────▶│  glass-app           │
│  system  │        (notifications + attributes)          │  (BLE central /       │
│  ANCS    │                                              │   ANCS client)        │
└──────────┘                                              │        │              │
┌──────────────────┐  Current Time Service (GATT 0x1805)  │        ▼              │
│ ios-companion    │ ─────────── Bluetooth LE ───────────▶│  notification popups  │
│ (CBPeripheral)   │         (phone clock)                │  + clock sync        │
└──────────────────┘                                      └──────────────────────┘
```

### Notifications (ANCS)

iOS does **not** let a third-party app read other apps' notifications. The only
supported way to get *real* iPhone notifications onto an accessory is Apple's
[Notification Center Service (ANCS)](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/),
which iOS exposes automatically over Bluetooth LE to a **bonded** accessory.

So on Glass, `glass-app` acts as the BLE **accessory**:

1. It tries the paired devices first (the iPhone must be bonded for ANCS), then scans
   for an Apple-named BLE peer.
2. It discovers the ANCS service
   (`7905F431-B5CE-4E99-A40F-4B1E122D00D0`) and subscribes to **Notification Source**.
   If the iPhone is not bonded yet it asks the system to start pairing.
3. For every `Added` notification it requests `AppIdentifier`, `Title`, `Subtitle` and
   `Message` from **Data Source** via the **Control Point**.
4. It renders the result as a standard Android notification, which the Glass system UI
   shows as a popup/timeline card, and cancels it after a few seconds. `Removed`
   events dismiss the matching notification.

The code is written against the Android 4.4 (API 19) BLE stack that Glass ships with,
so it uses only pre-API-21 APIs (`startLeScan`, the deprecated
`setValue`/`getValue` characteristic API, and so on).

### Popups use standard notifications, not the GDK

Google Glass XE normally exposes the **GDK** (CardBuilder/LiveCard/GestureDetector).
The Glass build this project was developed against (`XRV39`) does **not** ship those
classes anywhere in `/system/framework`, and the `gdk.jar` distributed with the
GlassKit samples is a compile-only **stub** (`RuntimeException("Stub!")`). The app
therefore has **no GDK dependency**: popups are posted with `NotificationManager`, and
the activity uses plain Android views. This also makes it work on Glass builds that
lack the GDK.

### Voice command

Glass only accepts the **predefined** voice commands in
`com.google.android.glass.app.VoiceTriggers.Command`. Custom phrases such as “my glass”
are rejected by the system parser (`Unable to build voice trigger`), so the app uses the
conventional catch-all:

> **“ok glass, show me a demo”**

To change it, edit
[`glass-app/app/src/main/res/xml/voice_trigger_start.xml`](glass-app/app/src/main/res/xml/voice_trigger_start.xml)
and use any `VoiceTriggers.Command` constant.

### Clock & timezone sync

`ios-companion` advertises the standard **Current Time Service** (`0x1805`, Current
Time `0x2A2B`, Local Time Information `0x2A0F`) plus a custom `MyGlass` service
exposing the clock as Unix epoch milliseconds and the phone's IANA timezone id.

Glass reads these on connect (`AncsClient.readCompanionClock`) and applies them:

- **Absolute time** (`TimeSync.applyTime`) — `SystemClock.setCurrentTimeMillis()`, then
  root `date`, then SNTP over Wi-Fi.
- **Timezone** (`TimeSync.applyTimeZone`) — primarily via Glass's own companion hook,
  which needs **no root**:
  ```java
  Intent i = new Intent("com.google.glass.timezone.COMPANION_UPDATE_TIME_ZONE");
  i.putExtra("time_zone", "America/New_York");
  i.putExtra("priority", 100); // TZ_PRIORITY_COMPANION
  context.sendBroadcast(i);
  ```
  The system applies it as a privileged service (this is exactly what the original
  MyGlass `TimeZoneUtil.updateTimeZoneByCompanion()` does). Fallbacks: `AlarmManager`
  (needs `SET_TIME`), then root `setprop persist.sys.timezone`.

### Reverse-engineered, Google-free Glass APIs

Glass ships a local companion surface we can drive without any Google services
(decoded from `XE23-APK`):

| Action / component | Purpose |
| --- | --- |
| `com.google.glass.timezone.COMPANION_UPDATE_TIME_ZONE` (`time_zone`, `priority`) | Set the system timezone (no root) |
| `com.google.glass.companion.SETUP_WIFI` (`wifi_setup_string` = `SSID\nENC\nPSK`) | Provision Wi-Fi |
| `com.google.glass.setup.ACTION_SETUP_COMPLETE` | Setup-complete signal |
| `com.google.glass.bluetooth.COMPANION_SERVICE` (`GlassBluetoothService`, perm `com.google.glass.bluetooth.permission.COMPANION`) | Companion AIDL bus / `CompanionFeatureInfo` |
| `com.google.glass.location.LocationService` | Phone GPS → Glass location provider |
| `com.google.glass.action.COMPANION_PROPERTIES_CHANGE` / `COMPANION_APP_CONNECTIVITY_CHANGE` | Companion state |

**Setup is “finished” iff the `com.google.glass.setup/.SetupActivity` component is
disabled** (`SetupHelper.wasSetupFinished`). The official offline path is the setup
**barcode** (URL-encoded `version=1&email=…&ssid=…&enc=…&psk=…&token=…&dp=…&adb=…`),
scanned with Glass, which configures Wi-Fi and disables the setup activity. A normal
app can't flip that component itself (it needs `CHANGE_COMPONENT_ENABLED_STATE`).

A normal app can't change the Android system clock without root/system signature; the
NTP fallback keeps it close whenever Glass has network access.

## Building

### Glass app (`glass-app/`)

Requires:
- **JDK 11–17** (JDK 17 recommended). AGP 7.4.1 / Gradle 7.5 do **not** support
  JDK 18+, so a newer JDK will fail.
- Android SDK components `platforms;android-33`, `build-tools;33.0.1`,
  `platform-tools`.

Working Homebrew + SDKMAN setup:

```sh
brew install --cask android-commandlinetools        # provides sdkmanager
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT=$ANDROID_HOME
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-33" "build-tools;33.0.1"

export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.11-tem"
```

Build and install:

```sh
cd glass-app
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### iPhone companion (`ios-companion/`)

The Xcode project is generated by [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```sh
cd ios-companion
xcodegen generate          # regenerates MyGlassCompanion.xcodeproj from project.yml
open MyGlassCompanion.xcodeproj
```

Set your signing team, choose your iPhone and Run. (A free Apple ID works; the app
must be re-signed every 7 days.)

## Using it

1. **Pair the iPhone with Glass** (iOS Settings → Bluetooth, or Glass Settings →
   Bluetooth). This is required for ANCS.
2. On Glass, say **“ok glass, show me a demo”** (or launch
   `com.myglass.glass/.MainActivity` via adb). The service starts.
3. Open **MyGlass Companion** on the iPhone and tap **Start advertising** so Glass can
   read the clock.
4. Notifications from the iPhone now pop up on Glass. The clock is synced on connect.

### Verifying without an iPhone

Post a sample popup and drive the service over adb:

```sh
# start the app and post a test notification
adb shell am start -n com.myglass.glass/.MainActivity --ez test_notification true

# ask the running service to reconnect / sync time
adb shell am startservice -n com.myglass.glass/.MyGlassService \
  -a com.myglass.glass.action.RECONNECT
```

Watch what it’s doing:

```sh
adb logcat -s AncsClient NotificationPresenter MyGlassService
```

## Offline setup & Wi-Fi (no Google services)

Because Google's setup servers are gone, Glass reverts to its setup screen on boot. The
app drives Glass's **own** companion hooks to set it up locally:

- **Wi-Fi only** — the companion sends `{"cmd":"wifi","ssid":…,"psk":…,"enc":…}` over
  the BLE command characteristic; the Glass app broadcasts
  `com.google.glass.companion.SETUP_WIFI` with `wifi_setup_string` (`SSID\nENC\nPSK`).
- **Full setup** — the companion sends `{"cmd":"setup","email":…,"ssid":…,"psk":…,…}`;
  the Glass app broadcasts `com.google.glass.companion.SETUP_GLASS` with `setup_string`
  (the URL-encoded v1 setup string). This applies Wi-Fi, sets the clock and lets Glass
  disable its setup activity.
- **Setup QR** — the companion can render the setup string as a QR code in-app; scan it
  with Glass's own scanner.

In the **MyGlass Companion** app, fill in your Google email, Wi-Fi SSID and password
(and encryption), then tap **Set Wi-Fi only**, **Run full setup**, or **Show setup QR
code**.

> The command channel is a notifiable BLE characteristic (`A104`); the phone pushes
> JSON to Glass, and the Glass app dispatches it to `SetupActions`.

### Status of the remaining features

- **Notifications, time, timezone** — implemented and verified.
- **Wi-Fi / offline setup** — implemented (uses Glass's own hooks); needs your real
  Wi-Fi credentials to exercise.
- **Companion info** — phone name + battery are pushed over the BLE command channel
  when Glass subscribes.
- **Location passthrough** — the phone→Glass channel is implemented (`{"cmd":"location",…}`
  → `GlassLocation`), **but feeding Glass's system location providers is blocked**: this
  Glass build does not define `android.permission.ACCESS_MOCK_LOCATION` (only the
  signature-level `INSTALL_LOCATION_PROVIDER`), so a third-party app cannot inject
  locations. The official companion did this through Glass's encrypted companion socket
  (`CompanionSocketServer` + `SecureStream`/`PresharedKey` + `Proto$LocationMessage`),
  which iOS cannot speak (no RFCOMM/SPP) and which would be a large RE project.
- **Screencast, photo/video sync, contacts, directions, SMS** — these depended on
  Google's cloud (Mirror/Google account) or the same companion socket tunnel; they are
  not reimplementable without Google services or the proprietary protocol.

## Limitations

- **Voice command must be a predefined Glass command** — custom phrases like “my glass”
  are not accepted by Glass XE. The app uses `SHOW_ME_A_DEMO`.
- ANCS notifications require the iPhone to be **bonded** to Glass and the accessory
  (Glass) to be connected; iOS keeps the ANCS link alive in the background.
- Exact clock setting needs root or a system-signed app; NTP is the non-root fallback.
- The `ios-companion` app must be started once to advertise the clock service; the
  notifications themselves do not need it.
- This Glass build has no GDK, so GDK features (LiveCard, gestures, touch menus) are
  not used. Notification action buttons / replies are out of scope.

## Attribution & license

- Structure and APIs follow the [GlassKit project](https://github.com/glasskit)
  and the original [Google Glass samples](https://github.com/googleglass).
- ANCS and Current Time Service UUIDs are Apple / Bluetooth SIG specifications.
- Licensed under the Apache License 2.0 (see `LICENSE`).
