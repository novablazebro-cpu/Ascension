# Ascension 1.4.0

Adds three gameplay levels to all 30 titles and clearer Chronicle menus, with consistent currency exchanges and visible server-shop restock timers.

## Included

- Equipped titles earn validated gameplay XP. Level 2 requires 1,000 total title XP; Level 3 requires 3,000. Existing owners begin at Level 1. Death and unequipping preserve progress; transferred titles use their new owner's personal progress.
- `title-levels.yml` configures thresholds, XP rewards, and Level 2/3 upgrades relative to configured Level 1 abilities. XP is stored under `title.xp.<title-id>` in the existing lifetime-stat store; this update needs no new database table.
- Stormbringer is displayed as **Thunder God**, preserving its internal ID, ownership, and cooldowns. Level 1 strikes one eligible player within 50 blocks, Level 2 one within 60 blocks, and Level 3 every eligible player within 60 blocks. Its 35-second cooldown remains shared across the activation.
- All titles show level, XP, current ability, next upgrade, and manual/passive instructions in the book. Equipped title tags and relic lore include the level. Running cooldowns and already scheduled abilities retain their state.
- The bound Chronicle gains an enchantment glint and clearer colored text. Bound-item cosmetic updates wait for inventory transactions and interrupted-operation recovery, preserving the book's existing owner/nonce identity.
- Shop menus show **Next Restock**, per-product `MM:SS`, price, and bundles. Java countdowns update in the same chest; iPad forms offer **Refresh Stock & Timers**. Confirmation screens stay stable while a player chooses.
- Six reviewed currency conversions in both directions: **1 netherite ingot = 4 diamond blocks = 16 emerald blocks**. Preview labels and transaction amounts use the same definitions. Currency outputs wait in collection.

## Install and upgrade

1. Stop the server completely. Back up server player data and the entire `plugins/Ascension` folder together.
2. Remove older Ascension JARs. Install exactly one build: `Ascension-1.4.0.jar` for Paper 1.21.4 / Java 21, or `Ascension-1.4.0-paper26.jar` for Paper 26.2 / Java 25.
3. Preserve configuration and player data. Start the server and confirm it enables Ascension 1.4.0. New `title-levels.yml` defaults are generated; existing title ownership and market data remain.
4. Open the Chronicle and check title levels and XP; use `/auction` for auction, shop, collection, and exchange navigation. Use Geyser-Spigot with server-side Floodgate and Bedrock forms enabled for native iPad forms.
5. Complete the gameplay checks below before treating device and third-party-plugin compatibility as verified.

**Currency rate change:** 1.3.0 exchanged one diamond block for one netherite ingot. 1.4.0 requires four diamond blocks. Existing inventory items, player listing prices, prepared journal operations, and stored deliveries are retained with their original amounts. They are not repriced or rewritten. Announce the new rate to existing players before the upgrade.

Shop configuration changes require a restart. Back up player data and Ascension data together when rolling back; restoring only one side can make interrupted inventory journals ambiguous. See `MARKET_GUIDE.md` for recovery and administrator review.

## Gameplay checks

- With two players, including one on iPad/Geyser, verify colored book navigation and level/XP/upgrade instructions. Test earning XP while equipped, switching titles, death, reconnecting, and title transfer.
- Check Thunder God's three ranges and target counts with teammates and protected players. Test Blood Lord healing while below full health. Check that Colossus upgrades do not stack health or grant healing and that active cooldowns survive level increases.
- Check all six exchange previews, insufficient ordinary currency, cancelled confirmation, repeated clicks, collection with a full inventory, and a complete currency round trip.
- Hold the Java shop open across a restock, refresh the iPad shop, and keep a purchase confirmation open while timers change. Check unavailable/reserved stock and changed-price rejection.
- Verify auction metadata, payment collection, interrupted-operation recovery, and that book/relic updates cannot alter the snapshots used by a pending market transaction.

## Verification

- **335 automated tests passed across 33 suites**, with zero failures, errors, or skipped tests. Coverage includes title profiles and thresholds, captured asynchronous XP, persistence, cast-time settings and cooldowns, Thunder God targeting, health modifiers, currency transactions and recovery, menu timers, compatible Bedrock colors, and bound-book cosmetics.
- Both supported JARs built successfully: Paper 1.21.4 / Java 21 and Paper 26.2 / Java 25. Release packaging checks their version, API descriptor, bytecode version, and required resources.
- The exact final Paper 26.2 JAR passed an isolated real-server check on `127.0.0.1:25574`: all 90 title-level profiles, default XP rules and aliases, effective Java/Bedrock book glint, idempotent restyling, owner/nonce identity, and native NBT round trips.
- The native check also passed all six currency plans, large payments and legal stack sizes, inventory/currency filtering, migrations V1–V12, five seeded shop products, and version/pending-operation/configuration-reload commands. The server exited with code 0, the test port closed, and the full log contained no ERROR or SEVERE messages. Runtime warnings remain in the log.
- `VERIFICATION.json` and `SHA256SUMS.txt` in the release package record the final build evidence and checksums. Local native evidence is preserved in `build/test-market26-140-final/native-smoke-result.json` and `market-nbt-smoke.log`.

**Live gameplay compatibility is not yet confirmed.** No real players connected during the isolated check. Complete the two-player checklist above, including an actual iPad/Geyser client and your server's other plugins, before treating that compatibility as verified.
