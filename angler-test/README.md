# Angler test plugins

Two standalone plugins that contain only the Angler title. They are separate from the main
Ascension plugin, so a cheap test server can run them without the title system.

- `paper-26.2/`: built against Paper API 26.2 (`paper-api:26.2.build.128-stable`, same coordinate as the main build).
- `paper-26.3/`: a copy with the API version changed. Its coordinate is in `gradle.properties` and
  is a placeholder (`REPLACE-ME`). Look up the real 26.3 build on repo.papermc.io and set it before building.

## What it does

- `/equip angler` gives a relic stick and marks you Angler-equipped. Other titles are refused.
- Right-click the relic: primes Deep Lure for 30 seconds (cooldown 20 seconds after a treasure catch).
- The next cast while primed pins both Paper fishing timers so the bite comes about `wait-ticks` ticks
  (default 60) after the bobber lands: `setLureTime(20, 20)`, `setWaitTime(wait-ticks - 20)`, and
  `setSkyInfluenced(false)` so a roof does not slow it down. A missed bite re-applies the same timers.
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
- Paper 26.3 source: `setWaitTime(int)` sets only the first timer (`timeUntilLured`). After it, a separate
  lure stage of 20-80 ticks runs before `BITE`. The old code set only the first timer, so a "60 tick" cast
  really took about 80-140 ticks. The fix pins both. Times are counted from when the bobber lands in water,
  not from the cast, so expect `BITE` a little later than `wait-ticks` in the chat timer.
- Rain still speeds the countdown up (Paper's rain bonus is left on).
- Nothing here has been compiled in the sandbox (the Paper repo is blocked there). The syntax check passed;
  a real build has not been run.
