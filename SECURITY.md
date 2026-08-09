# Security Policy

## Reporting a vulnerability

Please report privately through
[GitHub Security Advisories](https://github.com/GPTmadeit/Watchtastic/security/advisories/new)
rather than opening a public issue.

This is a small hobby project maintained by one person, so please don't expect a
same-day response. Expect an acknowledgement within about a week.

## Supported versions

Only the latest release receives fixes. There are no maintained release branches.

| Version | Supported |
|---|---|
| Latest release | ✅ |
| Anything older | ❌ — please update |

## What this app trusts

Understanding the trust boundaries makes it clearer what counts as a vulnerability.

### The self-updater

Watchtastic can download and install its own updates, which is remote code execution by
definition. Three controls constrain it, and weaknesses in any of them are serious:

- **Signature pinning.** A downloaded APK is handed to the installer only if its signing
  certificate is byte-identical to the running app's. Someone who can publish a release
  still cannot make a watch install code they signed — that requires the private key.
- **Package pinning.** The APK must declare this app's own package name.
- **Never silent.** Installation goes through Android's `PackageInstaller`, so the system
  — not this app — asks the wearer to confirm. No code path installs anything without an
  explicit yes.

Downgrades are refused, so an older build can't be used to move a device back onto a
version with known problems.

### The Bluetooth link

The app talks to a Meshtastic radio over a **bonded** BLE connection. Pairing uses
Android's own flow; the app never handles or transmits the PIN. It does not attempt to
decrypt mesh traffic — the radio does that and hands over already-decoded packets.

### Mesh traffic

Content arriving from the mesh is untrusted input. It is parsed with protobuf and
rendered as text; it is never executed or evaluated. Malformed frames are dropped rather
than propagated.

### What leaves the device

- Mesh packets go to your radio over Bluetooth and onward as you'd expect.
- Update checks make anonymous, unauthenticated HTTPS requests to the GitHub Releases
  API for this repository.
- Your position is sent to the radio **only** if you turn on *Share watch GPS*.
- Messages carry an MQTT-relay consent flag, controllable in *Settings → Allow MQTT
  relay*. Turning it off asks gateways not to forward your traffic off-mesh.

There is no analytics, telemetry, crash reporting, or third-party SDK of any kind. The
app has no account system and collects nothing.

### Data at rest

Messages, node records and channel metadata are cached in the app's private storage as a
JSON snapshot. It is not encrypted beyond Android's own full-disk encryption, and channel
pre-shared keys are **never** stored — which is why channel muting is local-only.

## Out of scope

- Vulnerabilities in Meshtastic firmware or the LoRa protocol itself — report those to
  the [Meshtastic project](https://meshtastic.org).
- Anything requiring physical access to an unlocked watch.
- The development signing key published in this repository's release history is
  deliberately public in the sense that its *certificate* is; the private key is not, and
  is not in the repository.
