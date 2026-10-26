# Ascension 1.3.0

Adds an Auction House and Server Shop to the Chronicle of Ascension, with shared Java chest menus and Bedrock/Floodgate forms. The release includes the current Blood Lord every-hit healing implementation and its legacy configuration migration; an older local Paper 26 release JAR still contained the previous restricted version.

## Included

- Up to ten active player listings, with seller-selected prices in emerald blocks, diamond blocks, or netherite ingots.
- Server-side item escrow, cancellation, and persistent collection for purchases and offline seller payments. Large payments can be collected in parts.
- Item currency exchanges: four emerald blocks for one diamond block; one diamond block for one netherite ingot.
- A configurable server shop with limited stock, random restocks, and bounded prices that rise with purchases and fall with restocks.
- Durable operation journals, inventory snapshot recovery, and administrator review for ambiguous interrupted operations.
- `/auction`, `/ah`, and `/market` navigation.

## Install

1. Stop the server completely. Back up the server and the entire `plugins/Ascension` folder.
2. Remove older Ascension JARs and install exactly one matching build: `Ascension-1.3.0.jar` for Paper 1.21.4 / Java 21, or `Ascension-1.3.0-paper26.jar` for Paper 26.2 / Java 25.
3. Keep existing configuration and player data. Start the server; migrations V11 and V12 add market tables, with automatic database backups. New `shop.yml` defaults are created.
4. Confirm the log enables Ascension 1.3.0, then open the book's Auction House & Shop section or `/auction`.
5. With Blood Lord equipped, injure the player below full health, hit a living mob or an eligible PvP player, and confirm healing. This ability is passive; a full-health player has no missing health to restore. Follow the live procedure in `TITLE_VERIFICATION.md`.

Use Geyser-Spigot and Floodgate-Spigot on the Paper server for iPad forms. This update is a Paper plugin, installed on the Java server behind Geyser. See `MARKET_GUIDE.md` for shop settings and recovery commands.

To roll back, stop the server and restore the backed-up JAR, player data, and Ascension data together. A schema downgrade is not automatic.

## Verification

- Automated suite: **273 tests, zero failures, errors, or skips**. It covers Blood Lord healing, auction caps and ownership, competing purchases, offline payments, partial collection, exchanges, stale item selection, failed inventory saves, ambiguous recovery, and rapid reconnect recovery.
- Both Java 21 / Paper 1.21.4 and Java 25 / Paper 26.2 release JARs compile successfully. The version and bytecode level were checked in each final artifact.
- The final Paper 26.2 JAR passed an isolated server startup and shutdown check with no error lines. Native Paper NBT preserved item names, enchantments, damage, and custom data; checks also covered large payments, legal stack splitting, inventory snapshots, currency filtering, V11/V12 migrations, five shop products, and market commands.
- Logs: `build/verify-1.3.0-tests.log`, `build/verify-1.3.0-paper26-build.log`, and `build/test-market26-clean/market-nbt-smoke.log`. The native check can be repeated with `scripts/verify_market_paper26.py` using the locally available server files.
- Live player testing on the user's server and iPad is not included in local automated verification.
