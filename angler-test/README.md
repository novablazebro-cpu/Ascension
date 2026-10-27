# Angler test plugins

Two standalone plugins that contain only the Angler title. They are separate from the main
Ascension plugin, so a cheap test server can run them without the title system.

- `paper-26.2/`: built against Paper API 26.2 (`paper-api:26.2.build.128-stable`, same coordinate as the main build).
- `paper-26.3/`: a copy with the API version changed. Its coordinate is in `gradle.properties` and
  is a placeholder (`REPLACE-ME`). Look up the real 26.3 build on repo.papermc.io and set it before building.

## What it does

- `/equip angler` gives a relic stick and marks you Angler-equipped. Other titles are refused.
- Right-click the relic: primes Deep Lure for 30 seconds (cooldown 20 seconds after a treasure catch).
- The next cast while primed calls `FishHook#setWaitTime(wait-ticks)`, with `wait-ticks` from `config.yml` (default 60).
- Every cast logs `LURED` and `BITE` timing from cast, in ms and ticks, to console and chat.
- A treasure catch replaces the caught item with a `FISHING_TREASURE` loot roll and starts the cooldown.

## Build (on your machine)

```bash
cd angler-test/paper-26.2
gradle build            # JDK 25 toolchain; output in build/libs/AnglerTest-0.1.0-angler-test.jar
```

For 26.3, first set `paperApiVersion` in `paper-26.3/gradle.properties`, then `gradle build` there.

## Live test checklist

1. Put the jar in the test server's `plugins/` folder and start the server.
2. Join, run `/equip angler`, then right-click the relic.
3. Cast and watch chat: note the `BITE` ms. Repeat 5 times, and write down the numbers.
4. Compare with `wait-ticks` in `config.yml`. Set it to `1` and repeat to see how the time changes.
5. Turn Lure on the rod (enchantment) and repeat. Lure is applied by default in Paper.
6. Note whether cast-to-`LURED` moves with `wait-ticks`, and whether the bite still comes after `LURED`.

Send back the console lines and the five bite times. Those numbers decide whether the main plugin's
"60-tick wait" is a real shorter wait.

## Known limits

- Equip state is in memory. A restart clears it, so run `/equip angler` again.
- Paper's `setWaitTime(int)` sets the wait before a fish appears. Lure and the `LURED` phase run afterwards, so
  the time to bite is not simply `wait-ticks`. That is what step 5 tests.
- Nothing here has been compiled in the sandbox (the Paper repo is blocked there). The syntax check passed;
  a real build has not been run.
