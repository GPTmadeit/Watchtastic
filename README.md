<div align="center">

# Watchtastic

**A [Meshtastic](https://meshtastic.org) client that lives on your wrist.**

Talks to your radio directly over Bluetooth. No phone in the loop, no companion app,
no signal required.

[![Download](https://img.shields.io/github/v/release/GPTmadeit/Watchtastic?label=Download%20APK&style=for-the-badge&color=67EA94&labelColor=2C2D3C)](https://github.com/GPTmadeit/Watchtastic/releases/latest)
[![Wear OS](https://img.shields.io/badge/Wear%20OS-4%2B-2C2D3C?style=for-the-badge)](https://wearos.google.com/)
[![License](https://img.shields.io/badge/license-GPL--3.0-2C2D3C?style=for-the-badge)](LICENSE)

</div>

---

## What is this?

Meshtastic radios build a long-range mesh network over LoRa — off-grid text messaging
with no cell service, no wifi, no infrastructure. Normally you drive one from a phone.

Watchtastic puts the whole thing on your watch. The radio can stay in your pack while you
read messages, check who's on the mesh, and navigate to them from your wrist.

## See it

<div align="center">

<img src="docs/screenshots/home.png" width="200" alt="Home screen showing mesh status and navigation"/>
<img src="docs/screenshots/conversations.png" width="200" alt="Conversation list"/>
<img src="docs/screenshots/chat.png" width="200" alt="A channel conversation with delivery ticks"/>

<img src="docs/screenshots/map.png" width="200" alt="Offline map with radar sweep"/>
<img src="docs/screenshots/nodes.png" width="200" alt="Node list with signal strength"/>
<img src="docs/screenshots/node_detail.png" width="200" alt="Node detail with telemetry"/>

<sub>Home · Messages · Chat — Map · Nodes · Node detail</sub>

</div>

---

## What you can do with it

| | |
|---|---|
| 💬 **Message** | Channels and direct messages. Dictate, type, tap a saved phrase, or fire off an emoji. Every message shows whether it actually got there. |
| 🔔 **Get alerted** | Messages pop up like a text — and you can reply straight from the notification without opening the app. |
| 🗺️ **See the mesh** | An offline map of everyone around you, plotted by real bearing and distance. Twist the crown to zoom from 250 m to 100 km. |
| 🧭 **Navigate** | Point the watch at any node and walk. A compass needle, a distance, and a tick on your wrist when you're facing the right way. |
| 📍 **Drop waypoints** | Mark a spot using the watch's own GPS and share it with everyone on the mesh. |
| 📡 **Check your radio** | Battery, signal, air time, hop counts, firmware — and change region, preset or hop limit without digging out your phone. |
| 🔄 **Update itself** | Watchtastic can check for and install its own updates. |

Built for the **Pixel Watch 4** — the domed screen, the rotating crown, and the haptic
motor all get used properly — but it runs on any **Wear OS 4+** watch with Bluetooth.

---

## Getting it on your watch

### The easy way — WatchPush 📲

Installing anything on a watch is normally a laptop-and-terminal job. It isn't anymore.

**[WatchPush](https://github.com/GPTmadeit/WatchPush)** is a companion app that sideloads
APKs onto your watch straight from your phone. No computer, no ADB, no cables.

1. **[Download WatchPush](https://github.com/GPTmadeit/WatchPush/releases/latest)** and
   install it on your Android phone.
2. **[Download the Watchtastic APK](https://github.com/GPTmadeit/Watchtastic/releases/latest)**
   — grab `Watchtastic-<version>-release.apk`.
3. On your watch, turn on **Settings → Developer options → Wireless debugging**.
4. Open WatchPush, tap **Scan network**, pair with the watch, pick the APK, and hit
   **Install on watch**.

That's it. Watchtastic appears in your watch's app list.

> **Don't see Developer options?** On the watch go to **Settings → System → About** and
> tap **Build number** seven times.

### The manual way — ADB

If you'd rather use a computer:

```bash
adb connect <watch-ip>:<port>
adb install -r Watchtastic-1.4.1-release.apk
```

---

## First run

1. Open Watchtastic and tap **Grant access** — it needs Bluetooth to reach your radio.
2. It scans automatically. Only Meshtastic radios show up, so the list stays short.
3. Tap yours. If it asks to pair, the PIN is on the radio's own screen.
4. Wait for the sync — it's pulling down the node database and your channels.

Done. You're on the mesh.

### Worth turning on

- **Settings → Share watch GPS** — hands the watch's own GPS to your radio, so a radio
  without a GPS module can still report an accurate position.
- **Settings → Quick replies** — edit the phrases you can send in two taps.
- **Long-press a channel** to mute it on the watch, without affecting anyone else.

---

## Good to know

**It works with any Meshtastic radio.** Heltec, RAK, T-Beam, LilyGo, Station G — if it
speaks the Meshtastic Bluetooth API, it works.

**The map has no streets, on purpose.** A tile map needs a data connection, and the whole
reason you own a Meshtastic radio is being somewhere without one. Instead the map draws
what the mesh already told you — who's out there, which direction, how far — and works
just as well in a valley with no signal. For street context on a specific node, node
detail can hand its coordinates to Google Maps.

**Messages are capped at 200 characters.** That's a LoRa limit, not ours.

**Battery.** Watchtastic holds a Bluetooth connection in the background so it can hear
messages with the screen off. That costs some battery. Settings → **Disconnect** drops the
link without forgetting your radio.

**Some things stay on your phone.** Creating or re-keying channels needs QR codes and
encryption keys, which a 1.2" screen isn't the place for. Set those up in the official
Meshtastic app; Watchtastic uses whatever your radio already has.

---

## Something not working?

| Problem | Try this |
|---|---|
| **App won't install** | Your watch needs "install unknown apps" allowed. WatchPush walks you through it. |
| **No radios found** | Bluetooth on? Radio powered and in range? Bluetooth enabled in the radio's own settings? |
| **Stuck on "Pairing"** | The PIN is displayed on the radio. If it never appears, forget the device in your watch's Bluetooth settings and retry. |
| **Messages not popping up** | Watch **Settings → Apps → Watchtastic → Notifications**, and check the *Messages* channel isn't silenced. |
| **Keeps reconnecting** | Normal at the edge of range — it backs off and retries. Check the radio's battery. |

Still stuck? [Open an issue](https://github.com/GPTmadeit/Watchtastic/issues) and include
your watch model and radio.

---

<details>
<summary><h2 style="display:inline">For developers</h2></summary>

### Build

```bash
./gradlew :app:assembleDebug
```

JDK 17 and Android SDK 36. Output is named for what it is —
`Watchtastic-1.4.1-release.apk`.

Release builds sign only if a `keystore.properties` exists at the repo root (gitignored,
along with `*.jks`). Without one the release variant still builds but comes out unsigned,
and Android refuses to install it with `INSTALL_PARSE_FAILED_NO_CERTIFICATES`.

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias watchtastic
```

```properties
storeFile=release.jks
storePassword=…
keyAlias=watchtastic
keyPassword=…
```

Back that keystore up. The self-updater only installs an APK whose signing certificate
matches the running app's, so losing the key means you can never ship an update that
existing installs will accept.

### Architecture

```
com.watchtastic
├── mesh/      BLE transport, packet routing, domain state
│   └── ble/   GattLink · RadioSession · BleScanner · BondManager
├── platform/  Haptics · LocationProvider · Prefs
├── service/   Foreground service, notifications, ongoing activity
├── update/    Drive-hosted update check and installer
├── di/        AppGraph — hand-wired, application-scoped
└── ui/        theme · icons · components · nav · screens
```

**`GattLink`** funnels every GATT operation through a mutex onto a `CompletableDeferred`.
Android tolerates exactly one outstanding operation per connection, and issuing a second
read before the first completes is the classic cause of silent BLE stalls.

**`RadioSession`** implements the order-sensitive handshake: connect → discover → MTU 512
→ subscribe to `FromNum` *before* asking for anything → drain the stale `FromRadio` FIFO →
`want_config_id` → drain again. `FromNum` only says *that* something is waiting, so every
notification means "drain again".

**`MeshStore`** keeps state in `StateFlow`s and snapshots to one debounced JSON file. A
watch mesh is a few hundred nodes and a bounded history; a relational store would cost
more in build complexity than it repays.

No DI framework, no Room, no Play Services — so it installs on any Wear OS device.

### Config safety

Two rules worth knowing before touching radio settings:

- **Config edits start from the radio's own last-sent proto.** `set_config` replaces
  rather than merges, so an edit rebuilt from the values this app displays would reset
  `tx_power`, `channel_num`, `override_frequency` and everything else.
- **Channel mute is local to the watch.** Muting device-side means a `set_channel` write,
  and `ChannelSettings` is likewise replace-not-merge — sending one back without the PSK
  (which this app never stores) would wipe the channel's encryption key.

### Protobufs

Vendored under `app/src/main/proto/meshtastic/` from
[meshtastic/protobufs](https://github.com/meshtastic/protobufs), compiled to
`protobuf-javalite` at build time. To update, copy in the newer `.proto` files and rebuild.

### Toolchain

AGP is held at 8.13.2 and AndroidX at the compileSdk 36 generation. Newer `core` and
`lifecycle` require AGP 9.1, and AGP 9 removes the `BaseVariant` API that
`protobuf-gradle-plugin` still uses. Moving up means replacing the protobuf codegen first.

</details>

---

## Licence

GPL-3.0 — see [LICENSE](LICENSE). The vendored `.proto` files are © Meshtastic under the
same licence.

"Meshtastic" and the Meshtastic logo are trademarks of the Meshtastic project. This is an
independent client, not affiliated with or endorsed by it. The Watchtastic mark is an
original design in the same visual family, not a reproduction.
