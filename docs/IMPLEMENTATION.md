# Ascension Completion Record

Reference: the master requirements supplied on 2026-09-22.

## Current Build

Java 21, Paper 1.21.4, SQLite, Geyser-Spigot, Floodgate-Spigot and ViaVersion.
The clean build and 149 automated tests pass. The local Paper smoke test upgraded an M0
database through V7, detected Floodgate, ran administrator commands, and shut down with
no Ascension errors.

## Implementation Order

1. Preserve cumulative XP levels and accepted-quest boundaries (user decision, 2026-09-22).
2. Add combat validation, persisted victim cooldowns, boss participation and raids.
3. Add saved hunts and recovery-journaled item submissions, shared UI and commands.
4. Add atomic global titles, eligibility, equip state and daily deathless activity.
5. Add all 30 title abilities; validate protection, replay and persistent cooldown paths.
6. Add exact title counters, documentation, build tests and local server smoke verification.
7. Replace title crouch gestures with protected owner-bound activation sticks for the 19 manual
   abilities; preserve automatic activation for the other 11 title abilities.

## Architecture

Keep domain rules independent of Bukkit. Use the existing serialized storage executor
and main-thread callbacks. Database ownership and critical transitions are authoritative;
online caches only expose committed state. Extend the database through numbered migrations.
Java inventories and Bedrock forms use the same server-created, one-use action tokens.

## Release Gates

- Build and automated test suite pass: verified.
- Upgraded database boot smoke test passes: verified. Fresh database is covered by integration tests.
- Claims, submissions and cooldowns survive failure/restart tests.
- Actual Java and Bedrock client QA, protected-region PvP and load testing are required.
  Automated mocks or a successful server boot do not establish these results.
- No production-ready claim before every master requirement and release gate is verified.

## Remaining Limits

- SQLite is supported. MySQL/MariaDB is not implemented in this build.
- WorldGuard, party, clan, PlaceholderAPI and LuckPerms have no explicit adapters yet.
  Cancelled Bukkit/Paper events and scoreboard teams are respected.
- Actual Java/Bedrock animation, form replay, latency and protected-region QA remains manual.
