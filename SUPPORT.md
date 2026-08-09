# Getting help

## Start here

Most problems fall into one of a few buckets, and the [README troubleshooting
table](README.md#troubleshooting) covers them: the app won't install, no radios show up,
pairing hangs, notifications don't appear, or the link keeps reconnecting.

## Where to ask

| I want to… | Go to |
|---|---|
| Ask a question or get setup help | [Discussions](https://github.com/GPTmadeit/Watchtastic/discussions) |
| Report a bug | [New issue](https://github.com/GPTmadeit/Watchtastic/issues/new?template=bug_report.yml) |
| Suggest a feature | [New issue](https://github.com/GPTmadeit/Watchtastic/issues/new?template=feature_request.yml) |
| Report a vulnerability | [Security advisory](https://github.com/GPTmadeit/Watchtastic/security/advisories/new) — not a public issue |
| Get help installing on the watch | [WatchPush](https://github.com/GPTmadeit/WatchPush) |
| Ask about the radio or firmware itself | [Meshtastic community](https://meshtastic.org/docs/community) |

## Is it Watchtastic or your radio?

Quickest way to tell: **try the official Meshtastic phone app against the same radio.** If
it misbehaves there too, the problem is the radio or its firmware, and the Meshtastic
community will resolve it far faster than we can.

## What to include

Four things make almost any report actionable, and without them most reports stall:

1. **Watchtastic version** — Settings, at the bottom.
2. **Watch model and Wear OS version.**
3. **Radio model and firmware version** — the Radio status screen shows both.
4. **Modem preset and region** — these affect channel naming and range more than people
   expect.

Logs help a lot when the watch has wireless debugging on:

```bash
adb logcat -s Watchtastic:* MeshRepository:* GattLink:* RadioSession:* UpdateManager:*
```

## Expectations

This is a hobby project maintained by one person in their spare time. Issues are read,
but there's no support rota and no response-time guarantee. Clear reports with the
details above get fixed fastest.
