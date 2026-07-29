# Watchtastic

A standalone [Meshtastic](https://meshtastic.org) client for Wear OS. It talks to a
Meshtastic radio directly over Bluetooth LE — no paired phone, no companion app.

Built for the Pixel Watch 4 (Wear OS 6, round 408×408 / 456×456 domed display, rotating
crown, LRA haptics), and works on any Wear OS 4+ device with BLE.

---

## What it does

**Connection**
- BLE scan filtered on the Meshtastic service UUID, so only radios ever appear
- System pairing flow for PIN-protected radios
- Full config download (`want_config_id` → `config_complete_id`) with live progress
- Automatic reconnect with capped exponential backoff; last-known state stays on screen
- Foreground service + Wear *ongoing activity* chip keeps the link alive screen-off

**Messaging**
- Per-channel and direct conversations, unread tracking, notifications
- Send via dictation, the system keyboard, canned quick replies, or emoji tapbacks
- Delivery status per message: queued → sent → delivered, or failed with the reason
- Reactions rendered inline on the message they target
- Editable quick replies; per-channel mute; clear a thread with a long-press
- Broadcast an alert or your position to a channel

**Mesh**
- Node list ordered by usefulness — favourites, then live, then most recently heard
- Node detail: SNR, RSSI, hops, role, hardware, battery, voltage, channel utilisation,
  air-time, environment telemetry, position
- Favourite / mute / ignore / remove, request position, traceroute
- Compass screen that points at a node using the watch's rotation-vector sensor
- **Map** — every node and waypoint plotted by bearing and distance around you, crown to
  zoom the range rings, heading-up or north-up, tap a blip to open that node
- Waypoints: see what the mesh has shared, drop one at the watch's own GNSS fix
- "Open in Maps" hands a node's coordinates to whatever mapping app the watch has

**Radio control** (via `AdminMessage` to the local node)
- Region, modem preset, hop limit, transmit enable, device role
- Rename node (long + short name), channel mute, clear node DB, reboot, shut down

**Watch-specific**
- Feeds the watch's own GNSS fix to the radio as its position
- Ambient-mode dimming
- Haptic vocabulary distinct per outcome (see below)
- Notifications for incoming messages, and for nodes appearing on the mesh for the first
  time — each on its own channel so either can be silenced alone

**Self-update**
- Checks a shared Google Drive folder for newer release APKs, downloads and installs
- Signature-pinned to the running build, package-pinned, downgrades refused, and the
  install is always confirmed by the system installer — details in the
  [changelog](CHANGELOG.md)
- Works without an API key; set `AppGraph.UPDATE_API_KEY` to use the documented Drive
  API instead of the public folder listing

---

## Design notes

### Why it feels like a watch app, not a shrunken phone app

**The domed display.** The Pixel Watch 4's Actua 360 panel curves away at the rim, so
anything near the edge is optically distorted. Layout leans on `ScreenScaffold`'s
round-aware content padding, and the compass dial keeps its ticks ~8% inside the rim.

**The crown.** `TransformingLazyColumn` carries rotary scrolling with per-detent haptics
by default. Long enumerations that would be miserable to scrub with a fingertip — 37 LoRa
regions, 17 modem presets — open a full-screen snapping `Picker` instead.

**Gestures.** Navigation is a flat back stack driven by `SwipeDismissableNavHost`, so the
right-edge swipe pops exactly as it does in first-party apps. Long-press is the secondary
verb throughout: on a message it reacts, on a channel it mutes.

**Reply cost.** The three reply paths are ordered by how long they take: tapback (one
tap), canned phrase (two), dictation/keyboard (the edge button). If replying is slow,
people reach for their phone instead.

### Why the map has no tiles

The map is a vector plot — range rings, blips, bearings — not a street map. A tiled map
needs network and an API key in exactly the situation Meshtastic exists for: a valley
with no cell service. It would be dead weight precisely when it matters.

So the map draws what the mesh already told us, entirely offline: every node and waypoint
placed by true bearing and great-circle distance from your position, on rings the crown
zooms from 250 m to 100 km. Out-of-range blips pin to the rim as hollow markers, so you
can tell the difference between "nothing there" and "further than this scale". Labels
de-collide greedily, nearest first and nodes before waypoints, so a dense mesh degrades
to fewer readable captions rather than a pile of overlapping text.

When street context genuinely helps, node detail hands the coordinates to the watch's own
mapping app via a `geo:` intent — and hides the action when nothing can handle it.

### Haptics

The wrist is an output channel, so outcomes are distinguishable without looking. Effects
are composed from `VibrationEffect.Composition` primitives — the Pixel Watch's linear
resonant actuator renders real amplitude envelopes — with predefined-effect and waveform
fallbacks for devices that lack primitive support.

| Signature | Meaning |
|---|---|
| `tick` | value stepped, compass bearing reached |
| `select` / `heavy` | tap landed / destructive action armed |
| `sent` | quick rise + tick — message departing |
| `delivered` | two light ticks — mesh acknowledged |
| `failed` | quick fall + thud |
| `incoming` | tick + click, distinct from the system buzz |
| `alert` | slow rise + three clicks, meant to be felt through a sleeve |
| `connected` / `disconnected` | rise / fall |

Scrolling is deliberately left alone — the rotary scroller already provides detents, and
doubling up feels mushy.

### Icons

Original set, drawn as `ImageVector`s on a 24 dp grid with a 2 dp round-capped stroke.
Material's core set has no vocabulary for hops, SNR, traceroute or mesh nodes, and mixing
filled Material glyphs with a stroked app mark looks like two apps in one.
See `docs/icons.html` for the full sheet.

**The app icon** locks two ideas together: the outer form is a *watch* — round case, lit
dial, crown at three o'clock — and sitting on that dial is the *mesh* — twin LoRa-chirp
peaks with a node at each vertex. It reads as content on a watch face, which is what the
app is.

It stays in the Meshtastic visual family without reproducing the project's mark.
Meshtastic's own logo is twin **solid** chirp peaks forming an "M" (read variously as
mountains and tents); this is the same chirp-peak language drawn as an **open stroked
polyline with explicit node dots**, so it also reads as a mesh graph, on dark ink rather
than green. Published brand colours throughout (`#67EA94` green, `#2C2D3C` ink).
Meshtastic's actual logo is a project trademark and is **not** bundled with this app.

The full lockup is used for the launcher and splash. The notification glyph and the
in-app `WtIcons.Mesh` drop back to the peaks alone — at 18–24 px the case and crown
collapse into noise, and the peaks carry the identity on their own.

---

## Architecture

```
com.watchtastic
├── mesh/
│   ├── ble/    GattLink · RadioSession · BleScanner · BondManager
│   ├── MeshConstants · PacketRouter · MeshRepository · MeshStore
│   └── model/  domain types
├── platform/   Haptics · LocationProvider · Prefs
├── service/    MeshService (foreground) · Notifier (+ ongoing activity)
├── di/         AppGraph — hand-wired, application-scoped
└── ui/         theme · icons · components · nav · screens
```

**`GattLink`** funnels every GATT operation through a mutex onto a `CompletableDeferred`.
Android's stack tolerates exactly one outstanding operation per connection, and issuing a
second read before the first completes is the classic cause of silent BLE stalls — the
lock makes that structurally impossible.

**`RadioSession`** implements the order-sensitive handshake: connect → discover → MTU 512
→ subscribe to `FromNum` *before* asking for anything → drain the stale `FromRadio` FIFO →
`want_config_id` → drain again. `FromRadio` is a FIFO behind one characteristic, read in a
loop until a read returns empty; `FromNum` only says *that* something is waiting, so every
notification means "drain again". Drains hold their own lock so a notification arriving
mid-drain can't interleave reads and mistake the other loop's terminator for its own.

**`MeshStore`** keeps state in `StateFlow`s and snapshots to one debounced JSON file
(atomic write-then-rename). A watch mesh is a few hundred nodes and a bounded message
history — a relational store would cost more in build complexity than it repays, and the
app still opens fully populated before the radio reconnects.

**No DI framework, no Room, no Play Services.** One of each collaborator, one lifetime,
so `AppGraph` *is* the dependency graph. Keeps annotation processors out of the build and
the app installable on any Wear OS device.

---

## Building

```bash
./gradlew :app:assembleDebug
```

Requires JDK 17 and Android SDK 36. Artefacts are named for what they are:

```
app/build/outputs/apk/debug/Watchtastic-1.0.0-debug.apk
app/build/outputs/apk/release/Watchtastic-1.0.0-release.apk
```

The debug APK is signed with the standard debug key and installs straight away:

```bash
adb -s <watch> install -r app/build/outputs/apk/debug/Watchtastic-1.0.0-debug.apk
```

### Release signing

Release builds are signed only if a `keystore.properties` exists at the repo root. It is
gitignored along with `*.jks`, so credentials never enter version control. Without it the
release variant still builds — but it comes out unsigned, and Android refuses to install
an unsigned APK with `INSTALL_PARSE_FAILED_NO_CERTIFICATES`.

A **development** keystore is already present (`watchtastic.jks`, all passwords
`watchtastic`) so sideloading works out of the box. It is fine for testing on your own
watch and nothing else.

**Before publishing, replace it.** Generate a key only you hold:

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias watchtastic
```

Point `keystore.properties` at it, and back the file up somewhere durable — Play requires
every future update to be signed with the same key, and losing it means you cannot ship
an update to existing installs. Better still, enrol in Play App Signing so Google holds
the app signing key and this one is only an upload key.

Signing schemes are v2 + v3 with v1 disabled: v1 (JAR signing) is only needed below
API 24, and this app is minSdk 33. `apksigner` will report v3 alone for that reason —
with a min SDK of 33 it omits the older blocks because every target device supports v3.

```bash
./gradlew :app:assembleRelease
```

Release is ~3.8 MB minified. The version lives in one place, `appVersionName` in
`app/build.gradle.kts`, and drives both the manifest and the APK filename.

### Toolchain pinning

AGP is held at 8.13.2 and AndroidX at the Android-16 (compileSdk 36) generation. The
newer `androidx.core` 1.19 / `lifecycle` 2.11 lines require AGP 9.1 + compileSdk 37, and
AGP 9 removes the `BaseVariant` API that `protobuf-gradle-plugin` still uses. Moving up
means replacing the protobuf codegen path first.

### Protobufs

The Meshtastic `.proto` definitions are vendored under `app/src/main/proto/meshtastic/`
from [meshtastic/protobufs](https://github.com/meshtastic/protobufs) and compiled to
`protobuf-javalite` at build time into `org.meshtastic.proto`. Only the transitive closure
needed by the client API is included. To update, copy in the newer `.proto` files and
rebuild.

---

## Status

Everything above is implemented and reachable from the UI — no feature exists in the
repository without a way to trigger it. Both variants compile clean with zero Kotlin
warnings and zero lint errors.

### Verified by running it

On a Wear OS 4 (API 34) emulator, 454×454 round, in **both** the debug and the minified
release build:

- cold launch, the runtime permission flow, and the foreground service reaching
  `isForeground=true` with `types=connectedDevice`
- every screen rendering with seeded data: connect, home, conversations, chat, nodes,
  node detail, compass, map, channels, waypoints, radio status, settings, radio config
- dropping a waypoint end to end — through the real Wear text-input surface — and seeing
  it land in your own list attributed to "You"
- tapping a map blip opening the right node's detail (hit-testing math)
- swipe-to-dismiss back navigation, unread counts clearing on read, ambient dimming
- derived values against known inputs — haversine distance, compass bearing, uptime
  formatting, and battery 101 rendering as "External power"
- R8 leaves the release build working; protobuf and kotlinx.serialization survive
  minification

### Not verified

**No physical radio has been connected.** The BLE layer is written to the published
[client API](https://meshtastic.org/docs/development/device/client-api/) and the current
protobuf schema, and the emulator has no Meshtastic device to talk to — so the GATT
handshake, PIN bonding, config download and admin writes remain unexercised. That is the
part to watch on first real use.

**Crown zoom on the map is untested.** The Wear emulator has no way to inject rotary
events (`adb shell input` has no rotary source), so the handler is wired and compiles but
has only ever been exercised by reading it. Everything else on that screen is verified.

Two decisions worth knowing about, both made to avoid destroying radio state:

- **Config edits start from the radio's own last-sent proto.** `set_config` replaces the
  whole message rather than merging, so an edit rebuilt from the handful of values this
  app displays would silently reset `tx_power`, `channel_num`, `override_frequency` and
  everything else. Edits go out as `existing.toBuilder()`.
- **Channel mute is local to the watch.** Muting device-side means a `set_channel` write,
  and `ChannelSettings` is likewise replace-not-merge — sending one back without the PSK
  (which this app deliberately never stores) would wipe the channel's encryption key.
  Muting on the wrist is also what a wearer actually wants: quiet here, unchanged for
  every other client on the radio.

Deliberately out of scope: channel creation and re-keying (needs PSK entry and QR/URL
exchange — a camera and a keyboard the watch doesn't have), MQTT proxying, and firmware
OTA. Use the phone or desktop client for those; Watchtastic uses whatever the radio
already has.

## Licence

**GPL-3.0.** See [LICENSE](LICENSE).

That choice isn't arbitrary. The `.proto` files under `app/src/main/proto/meshtastic/`
are vendored from [meshtastic/protobufs](https://github.com/meshtastic/protobufs), which
is GPL-3.0, and they are compiled into this app — so the resulting binary is a derivative
work and inherits those terms. The official Meshtastic Android client is GPL-3.0 for the
same reason. Anything permissive here would be a licence violation rather than a
preference.

Practically, that means: if you distribute a build of this app, you must also offer the
corresponding source under GPL-3.0.

"Meshtastic" and the Meshtastic logo are trademarks of the Meshtastic project. This is an
independent client, not affiliated with or endorsed by it, and the app icon is an
original mark rather than a reproduction of theirs — see the design notes above.
