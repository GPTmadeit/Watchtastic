## What does this change?

<!-- A sentence or two. Link the issue if there is one: Fixes #123 -->

## Why?

<!-- The problem being solved. For a bug, what the wrong behaviour was. -->

## How was it tested?

<!--
Be specific about what you actually ran. "Builds" is not testing.
If you tested against real hardware, say which watch and which radio — that is the
part nobody else can easily reproduce.
-->

- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `./gradlew lintDebug` clean
- [ ] Ran on a watch or emulator
- [ ] Tested against a physical Meshtastic radio (say which)

## Checklist

- [ ] No secrets, keystores or `local.properties` in the diff
- [ ] CHANGELOG.md updated if this is user-visible
- [ ] Comments explain *why* where the reason isn't obvious from the code

## Anything reviewers should look at closely?

<!--
Especially worth flagging:
- changes to BLE ordering in GattLink / RadioSession
- anything writing config to the radio (set_config and set_channel replace, not merge)
- anything touching the updater's signature checks
-->
