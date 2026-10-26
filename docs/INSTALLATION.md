# Installation

## Supported Stack

- Paper 26.3 with Java 25 (`Ascension-1.6.0-paper26.3.jar`)
- Older servers: Paper 1.21.4 or 26.2 keep using the 1.5.0 JARs in `releases/`
- Geyser-Spigot and Floodgate-Spigot for Bedrock players
- ViaVersion when required by the installed Geyser build
- SQLite storage (default and currently supported database)

Do not install Ascension on Fabric, Forge, NeoForge, Bukkit `/reload`, or a proxy's plugin folder.

## Install

1. Stop the Paper server.
2. Back up the server and `plugins/Ascension` if upgrading.
3. Remove older Ascension jars, then copy `Ascension-1.6.0-paper26.3.jar` into `plugins/`. Install only one build.
4. Install Geyser-Spigot, Floodgate-Spigot and ViaVersion when Bedrock access is needed.
5. Start the server once so Ascension generates its configuration and database.
6. Confirm the log contains `Ascension enabled: 12 rank stages`.
7. Do not edit the generated SQLite database while the server is running.

For Floodgate, set Geyser's remote authentication type to `floodgate`. Bedrock connections
also require a host-provided UDP port.

## Upgrade

Ascension applies numbered database migrations at startup. Before each migration it creates
a timestamped database backup under `plugins/Ascension/backups`. Existing YAML files are not
overwritten. New settings therefore use safe parser defaults until an administrator adds them.

Use `/titles reload` for validated YAML changes. Storage changes require a full restart.

Ascension 1.6.0 targets Paper 26.3 and Java 25. It adds no database migration, so an upgrade
from 1.5.0 keeps every rank, title, cooldown and market record. Colors return to menus and
messages; each one is adjusted to stay readable on the surface it is drawn on. Six new
`messages.yml` keys (rank-up and title level-up splashes) are added in memory when missing.
See [RELEASE_1.6.0.md](RELEASE_1.6.0.md).

Ascension 1.4.1 removes text colors from the Chronicle and its Java/Bedrock menus. Existing
books update safely after inventory recovery. The enchantment glint and player title colors
remain. See [RELEASE_1.4.1.md](RELEASE_1.4.1.md) for this update.

Ascension 1.4.0 adds three gameplay levels to every title, an enchanted Chronicle with clearer
menus, shop countdowns, and six currency exchanges. Existing title owners start at Level 1;
XP is saved per player and per title. Existing market journals and deliveries retain their
recorded amounts. The new rate is **1 netherite ingot = 4 diamond blocks = 16 emerald blocks**.
Shop settings in `shop.yml` require a restart. See [MARKET_GUIDE.md](MARKET_GUIDE.md) for currency,
collection, stock pricing and recovery, and [RELEASE_1.4.0.md](RELEASE_1.4.0.md) for upgrade instructions.
