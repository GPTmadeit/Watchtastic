# Roadmap

No dates and no promises — this is a hobby project. What follows is an honest account of
what's likely next, what's deliberately excluded, and where the current build falls short.

## Known limitations

Things that are true of the app today, in rough order of how often they bite.

- **Channel creation and re-keying aren't supported.** Setting up a channel means
  handling pre-shared keys and QR codes, which needs a camera and a keyboard. Use the
  official Meshtastic phone or desktop client; Watchtastic uses whatever the radio already
  has. This is unlikely to change.
- **Channel mute is local to the watch.** It doesn't propagate to the radio, because
  writing a channel back without its PSK would destroy the key. See
  [ARCHITECTURE.md](ARCHITECTURE.md).
- **English only.** No translations, and no string extraction work has been done toward
  them.
- **The BLE layer has no automated tests.** Unit tests cover pure logic — versions,
  geometry, addressing, channel naming — but the transport is verified by running it
  against real radios. Testing it properly needs a fake GATT server that doesn't exist yet.
- **Battery cost is real.** Holding a Bluetooth connection so messages arrive with the
  screen off is not free. Settings → Disconnect exists for when you don't need it.
- **Messages cap at 200 characters.** That's the LoRa payload limit, not an app choice.
- **Store-and-forward history isn't requested.** If your mesh has a store-and-forward
  router, Watchtastic won't pull missed messages from it on reconnect.

## Likely next

Roughly in order of usefulness per unit of work.

- **A tile and a complication.** A glanceable unread count and mesh status on the watch
  face is the most obviously missing Wear-native surface.
- **Store-and-forward requests**, so reconnecting after being out of range can backfill
  what you missed.
- **Richer traceroute display** — the data is already parsed and stored; it currently gets
  one modest card.
- **A fake GATT server for tests**, which would make the transport layer testable in CI
  rather than only on hardware.
- **Message search**, once histories get long enough to need it.

## Deliberately not planned

Not because they're bad ideas, but because they're wrong for this app.

- **A tiled street map.** It needs network and an API key in exactly the situation
  Meshtastic exists for. The offline vector map is the considered answer, and node detail
  can hand coordinates to a real maps app when street context genuinely helps.
- **A phone companion app.** Watchtastic is standalone on purpose — the whole point is
  leaving the phone behind. [WatchPush](https://github.com/GPTmadeit/WatchPush) handles
  installation and stays a separate tool.
- **Accounts, sync, or a backend.** The app collects nothing and talks to nothing except
  your radio and GitHub's release API.
- **Analytics or crash reporting.** Same reason.

## Contributing to any of this

Everything above is up for grabs — see [CONTRIBUTING.md](../CONTRIBUTING.md). If you're
picking up something from "likely next", open an issue first so effort isn't duplicated.
