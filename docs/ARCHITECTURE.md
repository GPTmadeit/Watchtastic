# Architecture

How Watchtastic is put together, and why the awkward parts are the way they are.

## The shape of it

```
com.watchtastic
├── mesh/
│   ├── ble/        GattLink · RadioSession · BleScanner · BondManager
│   ├── model/      Domain types (nodes, channels, messages, positions)
│   ├── MeshConstants   BLE UUIDs, broadcast address, payload limits
│   ├── PacketRouter    FromRadio frames → store mutations
│   ├── MeshRepository  Connection lifecycle and every outbound operation
│   └── MeshStore       All app state, as StateFlows + a JSON snapshot
├── platform/       Haptics · LocationProvider · Prefs
├── service/        MeshService (foreground) · Notifier · reply receiver
├── glance/         MeshGlance · tile · complication · GlanceUpdater
├── update/         GitHubReleaseClient · UpdateManager · Version
├── di/             AppGraph — hand-wired, application-scoped
└── ui/             theme · icons · components · nav · screens
```

Data flows one way: **radio → `RadioSession` → `PacketRouter` → `MeshStore` → Compose**.
The UI never touches Bluetooth. Everything it renders comes from a `StateFlow`.

Commands flow the other way through `MeshRepository`, which is the single door to the
radio.

## Bluetooth

### `GattLink` — one operation at a time

Android's GATT stack tolerates exactly **one** outstanding operation per connection and
reports completion on a callback thread. Issuing a second read before the first completes
is the classic cause of a BLE link that silently stops working.

Every operation is therefore funnelled through a mutex onto a `CompletableDeferred` that
the matching callback resolves. This makes the failure mode structurally impossible
rather than merely unlikely.

### `RadioSession` — an order-sensitive handshake

The Meshtastic BLE protocol is simple but strict about sequence:

1. Connect and discover services.
2. Raise the MTU to 512 — this turns a ~30 second config download into a couple of
   seconds.
3. Subscribe to `FromNum` **before** requesting anything, so no reply can be missed.
4. Drain whatever is already in the `FromRadio` FIFO.
5. Write `want_config_id`, then drain again.

`FromRadio` is a FIFO behind a single characteristic: you read it in a loop until a read
returns empty. `FromNum` only signals *that* something is waiting, never how much — so
every notification means "drain again".

Drains hold their own lock. A notification arriving mid-drain would otherwise interleave
reads, and each loop would mistake the other's empty read for its own terminator.

### Reconnection

A watch drifts in and out of range of a radio sitting in a pack all day, so a dropped
link is the normal case rather than an error. `MeshRepository` retries with capped
exponential backoff and keeps the last-known node database on screen throughout.

Switching radios is serialised through a mutex, and teardown checks session identity —
otherwise a cancelled attempt's cleanup can land after a newer session is already in
place and tear it down. That bug shipped once; the identity check is what prevents it.

A dropped link is signalled by a `CompletableDeferred`, not a flow. It is a once-per-link
fact, and it must be seen whenever anyone gets round to looking: as a flow without replay,
a drop during config sync went unheard and the sync sat out its full 90-second timeout on a
dead link. Sync now races the config gate against the drop and fails immediately. The
first connection attempt also waits for the snapshot to finish loading, so the radio's
config download can't be overwritten by the previous session's state landing late.

## State

`MeshStore` holds everything in `StateFlow`s and snapshots to a single debounced JSON
file, written atomically (write to temp, rename).

A watch mesh is a few hundred nodes and a bounded message history. A relational store
would cost more in build complexity and query indirection than it could repay, and the
app still opens fully populated before the radio reconnects.

History is bounded **per conversation** (200 each, 1,000 overall). A single global cap
let one busy public channel evict every quiet direct conversation within a day or two.

The store takes a `File`, not a `Context`, so it runs under plain JVM tests — which is
what lets `PacketRouterTest` drive real frames through the real decode path. A snapshot
that fails to decode is set aside as `mesh-store.json.unreadable` rather than deleted, so
a decoding bug in some future version can't destroy anyone's history.

## Sending: the outbox

The watch is regularly out of reach of its own radio, so "not connected" is a normal
moment to want to send, not an error. Every outgoing message goes through one path:

1. It is inserted into its thread as **Queued** — the bubble appears immediately.
2. If the link is up it is written to the radio; QueueStatus and the Routing ACK then
   move it to *Sent* and *Delivered*.
3. If the link is down, or the write fails, it stays Queued **in the outbox**. When the
   next connection finishes syncing, the outbox is flushed oldest-first.
4. Anything still unsent after 30 minutes is marked failed rather than delivered late —
   a stale "on my way" is worse than one that visibly didn't go. Failed messages can be
   tapped to resend, as a fresh packet.

"Queued" alone can't distinguish the outbox from a write that is still waiting for its
QueueStatus, so `MeshRepository` keeps the set of ids it has written this run; only
messages outside it are flushed. The set is claimed *before* writing, which is what stops
a flush and a direct send racing to send the same message twice.

## Time

A radio with no GPS has no wall clock until a client gives it one, and this app exists for
when no phone is around to do that. So after each sync the watch sends its own time
(`set_time_only`). The firmware applies it at NTP quality, below GPS, so a radio with a fix
keeps its own clock.

Radio timestamps are still treated with suspicion. Until it has a clock the firmware stamps
packets with *uptime* seconds, and some node-DB "last heard" values are boot-relative.
`MeshTime` rejects anything before 2020 (the firmware's own `MIN_PLAUSIBLE_EPOCH`) or well
ahead of the watch, and reads `fixed32` times unsigned so they survive 2038.

**Unread is judged by when the watch received a message, not when the radio did.** The
radio holds traffic while the watch is out of range; on reconnect that backlog arrives with
its true, earlier timestamps — older than the last time the wearer opened the thread, yet
never seen. Each inbound message therefore records `receivedAtMs` on the watch's clock, and
that is what `ChatMessage.isUnread` compares.

Deliberately **not** persisted: channel pre-shared keys, and the raw config protos (kept
in memory only, for the reason below).

## Writing config to the radio

`set_config` and `set_channel` **replace** the entire message rather than merging.

An edit rebuilt from the handful of values the UI displays would silently reset
`tx_power`, `channel_num`, `override_frequency` and everything else — potentially moving
a radio off its frequency slot. So the store keeps the radio's last-sent protos verbatim
and every edit goes out as `existing.toBuilder()`.

The same reasoning makes **channel mute local to the watch**. Muting device-side means a
`set_channel` write, and since this app never stores the PSK, writing one back would wipe
the channel's encryption key. Local muting is also what a wearer actually wants: quiet on
the wrist, unchanged for every other client on the radio.

## Glanceable surfaces: the tile and the complication

Both are *pulled*: the system binds the service and asks for content, and only asks again
when told something changed. So the work splits three ways:

- **`MeshGlance`** reduces store state to exactly what the surfaces show — link status,
  unread count, nodes online, and the one conversation a tap should open, if there is
  only one. It is a pure function, so its rules are unit-tested, and both surfaces render
  from it, so they cannot disagree with each other.
- **`GlanceUpdater`** watches the store and requests a refresh only when the glance
  *changes*. Config download emits a link state per frame; none of those alter what the
  tile shows, so none of them cost a refresh. Bursts are debounced, because the system
  throttles tiles that ask too often.
- **`MeshTileService`** and **`MeshComplicationService`** answer the system's requests.
  Either can be what starts the process, so they wait (briefly) for the snapshot to load
  rather than rendering a confident "0 unread" that isn't true.

Unread counting lives in one place, `ChatMessage.isUnread`. The home screen, the ongoing
chip, the tile and the complication all go through it, and `MeshStore.unreadTotal` is a
flow, so reading a conversation lowers every count as promptly as a new message raises it.

## Why there's no map tile layer

The map is a vector plot — range rings, blips, bearings — not a street map.

A tiled map needs network and an API key in exactly the situation Meshtastic exists for:
somewhere with no cell service. It would be dead weight precisely when it matters most.

So the map draws what the mesh already told us, entirely offline: every node and waypoint
placed by true bearing and great-circle distance, on rings the crown zooms from 250 m to
100 km. Out-of-range blips pin to the rim as hollow markers, so "nothing there" and
"further than this scale" look different. Labels de-collide greedily — nearest first,
nodes before waypoints — so a dense mesh degrades to fewer readable captions rather than
a pile of overlapping text.

When street context genuinely helps, node detail hands the coordinates to whatever
mapping app the watch has via a `geo:` intent, and hides the action when nothing can
handle it.

## Dependency choices

**No DI framework, no Room, no Play Services.** (The tile and complication are AndroidX
`wear.tiles` and `wear.watchface` libraries, not Play Services, so this still holds.) One of each collaborator and one lifetime
for all of them, so `AppGraph` *is* the dependency graph — readable top to bottom. This
keeps annotation processors out of the build and the app installable on any Wear OS
device, not just one with Google services.

**Protobufs are vendored**, not fetched: the `.proto` files live under
`app/src/main/proto/meshtastic/` and compile to `protobuf-javalite` at build time. Only
the transitive closure the client API needs is included.

## Toolchain pinning

AGP is held at 8.13.2 and AndroidX at the compileSdk 36 generation.

The newer `androidx.core` 1.19 and `lifecycle` 2.11 lines require AGP 9.1, and AGP 9
removes the `BaseVariant` API that `protobuf-gradle-plugin` still uses. Moving up means
replacing the protobuf codegen path first — which is why Dependabot is configured to
ignore those specific bumps rather than opening PRs that can't merge.

## Wear-specific design

- **The domed display.** The Pixel Watch panel curves away at the rim, so layout leans on
  `ScreenScaffold`'s round-aware padding and the compass keeps its ticks inside it.
- **The crown.** `TransformingLazyColumn` carries rotary scrolling with per-detent
  haptics. Long enumerations — 37 LoRa regions, 17 modem presets — get a full-screen
  snapping `Picker` instead of a list.
- **One layout node per list item.** A `TransformingLazyColumn` item that emits two
  siblings silently collapses. This caused an entire navigation menu to render as nothing.
- **Haptics as an output channel.** Distinct signatures per outcome, composed from
  `VibrationEffect.Composition` primitives, so results are distinguishable without
  looking.
