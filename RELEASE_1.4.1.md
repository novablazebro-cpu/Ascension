# Ascension 1.4.1

The Chronicle, its pages, and its Java and Bedrock menus now use plain text. Java item names
and lore use neutral white; Bedrock forms contain no formatting codes. Existing books are
updated without changing their owner or nonce, duplicating the book, or disturbing pending
inventory transactions.

The book keeps its enchantment glint. Player rank/title colors and ability relic colors remain.
Title levels, abilities, auctions, shop timers, and currency rates retain their 1.4.0 behavior.

Stop the server, back up `plugins/Ascension`, replace the old Ascension JAR with the build for
your Paper/Java version, and restart. Install only one Ascension JAR. Players can reconnect
and open the Chronicle to receive the plain appearance. No configuration or database reset
is needed. See [INSTALLATION.md](INSTALLATION.md) for the supported server stacks.

Verification: all 335 existing regression tests passed, with no failures or skips. Both JAR
builds passed; version, Paper API metadata, and Java bytecode versions were verified.
Live Java/iPad gameplay and a new native Paper startup check are not part of this cosmetic
update's verification; the recorded 1.4.0 native check applies to the previous JARs.
