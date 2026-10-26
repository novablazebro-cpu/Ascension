# Ascension 1.2.6 — Season 4

Target: Paper 26.2 / Java 25. This is a focused follow-up to 1.2.5. No player-data schema migration or balance change is required.

## Changes

- Treasure Whisperer now attributes natural chest loot to the player who actually opened that chest, even when Paper's loot event has no entity. Each opening is consumed once.
- Tempest Herald's Chain Lightning applies the configured `chain-multiplier` to secondary targets.
- Raidbreaker enters raid mode only when an active raider is within the ability radius; a distant raid no longer suppresses its grouped-hostile mode.
- Title switching, unloading, and reconciliation remove plugin-tagged relics from the cursor and open container as well as the player's inventory. Ordinary items are untouched.
- Duplicate interaction events in the same server tick cannot activate the relic twice. Cooldown expiry arithmetic saturates instead of overflowing on extreme values.

## Upgrade and rollback

1. Stop the server completely. Back up the current Ascension JAR and the entire `plugins/Ascension` folder, including its database and any WAL/SHM files.
2. Put only `Ascension-1.2.6-paper26.jar` in `plugins`; remove the previous Ascension JAR. Keep your existing configuration and player data.
3. Start Paper 26.2 with Java 25. Confirm `Ascension v1.2.6` enables in the log. Use a full restart for this JAR upgrade; `/titles reload` only reloads configuration.
4. Check relic activation after title cycling and reconnecting, Treasure Whisperer on a naturally generated chest, Chain Lightning secondary damage, and Raidbreaker near grouped mobs and actual raiders. Check Java and Bedrock clients if both play on your server.

To roll back, stop the server, restore the backed-up JAR and matching `plugins/Ascension` folder together, then start the server. Restoring data also restores progression to backup time.

## Verification

- Automated suite: 216 tests, zero failures and zero skips. It includes focused regressions for the fixes above.
- Paper 26.2 / Java 25 build and isolated server enable/reload/shutdown: passed. Log: `build/verify-1.2.6-paper26.log`. Paper logged an unrelated Windows OSHI performance-counter error before plugin loading; Ascension enabled and shut down cleanly.
- SHA-256: `02657e8863f8fa0356580e1c8e2eafbb3fb6b2ab6a795ac0428142389270808c`.
- Real Java/Bedrock gameplay, natural-chest bonus RNG, and interactions with third-party protection plugins were not exercised. The isolated server had no Floodgate.
