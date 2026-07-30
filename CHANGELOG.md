# Changelog

## 1.3.0

**Messages now interrupt like a text.** They were arriving silently in the notification
stream instead of popping up. Two things caused it, and both had to change: the
notification was built with `setSilent(true)`, and the channel had vibration disabled —
Android only raises a heads-up card for a notification that actually alerts, however high
its importance.

The message channel id had to move to `mesh_messages_v2` to fix it. A channel's importance
and vibration are frozen at creation; the system ignores later edits, and deleting and
recreating an id restores its old settings. Anyone upgrading would otherwise have kept the
silent channel forever. The old one is deleted so it doesn't linger in system settings.

While in there, messages became proper conversations: `MessagingStyle` with the sender as
a `Person`, so Wear stacks a thread into one card and shows who is talking — and a
**Reply action right on the notification**. Dictate a reply and it goes out over LoRa
without opening the app.

**New nodes stay quiet**, as asked: `DEFAULT` importance so they file into the stream as a
small card without seizing the screen, channel vibration off, and our own light haptic as
the only thing you feel. They remain on a separate channel, so messages can interrupt
while node discovery doesn't.

Note the message buzz now belongs to the notification channel rather than to `Haptics` —
the explicit `haptics.incoming()` call was removed, or one message would buzz twice.

**Motion.** The app moves on Material 3 Expressive springs now
(`MotionScheme.expressive()`), which re-times every component at once — buttons, cards,
dialogs, the edge button, and the item morphing in `TransformingLazyColumn`. On top of
that:

- the **map sweeps like radar** — a rotating sweep gradient with a bright leading edge and
  a comet tail, plus a beacon ring pulsing out of your own position
- **scanning for radios** shows three sonar rings expanding out of the app mark instead of
  a generic spinner
- **signal bars** spring up on staggered stiffness so a change in quality ripples across
  them, with colour crossfading separately
- the **connection dot breathes** only while something is actually in flight, so a steady
  dot honestly means settled rather than possibly stuck

## 1.2.0

**Updates from the shared Drive folder.** Settings → Software update lists the release
folder, compares versions, downloads, and hands the APK to the system installer.
Verified end to end on a watch: 1.0.1 → 1.1.0 pulled straight out of the folder.

The security model is the interesting part, because "download an APK and install it" is
otherwise remote code execution:

- **Signature pinning.** A download is only offered to the installer if its signing
  certificate is byte-identical to the running app's. Anyone who can drop a file in the
  folder still cannot make the watch install code they signed — that needs the private key.
- **Package pinning**, and **downgrades refused**, so an old build can't be used to put a
  device back onto a version with known problems.
- **Never silent.** Install goes through `PackageInstaller`, so Android asks the wearer.

No API key required: the folder is listed through Drive's public embedded-folder
endpoint. An optional key can be set in `AppGraph.UPDATE_API_KEY` to use the documented
Drive API instead, which is more durable if Google changes that HTML.

Note that installing needs Android's per-app "install unknown apps" permission, which the
manifest entry alone does not grant. The update screen detects this and offers a button
straight to the right settings page rather than letting you hit a dead end.

**New-node notifications.** A node the mesh has never shown you before now raises a
notification and a light haptic, on its own channel so it can be silenced separately.
Two filters keep it from being noise: nothing fires while the link is still syncing —
otherwise every reconnect would replay the whole node database at your wrist — and each
node number is only ever announced once.

Fixes:

- the staged update APK no longer lingers in the cache; a successful install replaces the
  process before cleanup can run, so it is collected at next startup instead

## 1.1.0

**Map.** A new offline map screen: every node and waypoint plotted by true bearing and
great-circle distance around you, on range rings the crown zooms from 250 m to 100 km.
Heading-up or north-up, out-of-range blips pinned to the rim as hollow markers, and tap a
blip to open that node. No tiles and no network — see the README for why that is
deliberate rather than a shortcut.

**Waypoints you drop now appear in your own list.** They were being broadcast to the mesh
and then vanishing: the radio doesn't echo our own transmissions back, and nothing wrote
a local copy. Waypoints are now recorded locally as they're sent, and attributed — "You"
or the sender's short name.

**Dropping a waypoint no longer requires continuous GPS sharing.** The map and waypoint
screens take a one-shot fix on demand (reusing a cached fix under 90 s old). Marking a
spot and broadcasting your position are separate decisions and are now treated that way.

**Open in Maps.** Node detail can hand a node's coordinates to whatever mapping app the
watch has, via a `geo:` intent. Hidden when nothing can handle it.

Fixes:

- map captions no longer collide with the centre position marker, or with each other —
  they de-collide nearest-first, nodes before waypoints
- added the `<queries>` declaration package visibility needs on API 30+, without which
  "Open in Maps" could never have appeared
- replaced the deprecated `rememberActiveFocusRequester` with
  `Modifier.requestFocusOnHierarchyActive`

## 1.0.0

First release. Standalone Wear OS Meshtastic client: BLE scan/pair/connect with
auto-reconnect, full config download, per-channel and direct messaging with delivery
status and tapbacks, node list and detail with telemetry, compass bearing to a node,
traceroute, waypoints, radio configuration, and a foreground service with an ongoing
activity chip that keeps the mesh alive with the screen off.
