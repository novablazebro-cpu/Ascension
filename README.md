# Ascension 1.6.0

![Ascension project banner](assets/ascension-banner.png)

Minecraft progression and trading for Paper: 12 ranks, 30 exclusive titles with three power levels each, assigned hunts, rank renewal, auctions, item currency exchange and a 150-product shop. One title has one server-wide owner; each player can hold up to five and equip one. Title claims require their quests. Elytra starts at 64 emerald blocks; shop restocks take 2–4 hours. Menus use color that stays readable on every surface, and title abilities are animated.

Ascension 1.6.0 runs on Paper 26.3 with Java 25. Java players use inventory menus. iPad/Bedrock players connect through compatible Geyser and server-side Floodgate for forms; without Floodgate, the inventory fallback applies. Servers on Paper 1.21.4 or 26.2 keep using the 1.5.0 JARs in `releases/`.

Stop and back up the complete server before replacing the JAR, then restart. Disable testing.unlock-all-ranks-and-titles in existing production configuration. See [Player guide](docs/PLAYER_GUIDE.md), [Market guide](docs/MARKET_GUIDE.md), [Title levels](docs/TITLE_LEVEL_GUIDE.md), [Complete plugin report](docs/PLUGIN_REPORT.md), [Title verification](docs/TITLE_VERIFICATION.md) and [QA checklist](docs/QA_CHECKLIST.md). The report includes all rank quests, all 90 title power profiles and all 150 shop bundles/prices.

Release verification: 381 tests across 41 suites passed with zero failures, errors or skips on MockBukkit for Paper 26.3. The JAR has not yet been started on a live Paper 26.3 server, and live two-player/iPad gameplay remains unperformed. See [release record](docs/RELEASE_1.6.0.md).

Copyright (c) 2026 Ayush Anand. Source available under the custom restricted [LICENSE](LICENSE.md); this is not an open-source license. Personal modifications are allowed under those terms. Public redistribution requires the owner's written permission; platform viewing/fork rights remain governed by GitHub's terms.

Build with Java 25 using `./gradlew test jar`; the JAR is written to `build/libs/`.

## Downloads

- [Paper 26.3 / Java 25 JAR](releases/Ascension-1.6.0-paper26.3.jar)
- [Complete source archive](releases/Ascension-1.6.0-source.zip)
- [Verification and hashes](releases/VERIFICATION-1.6.0.json)
- Older servers: [Paper 1.21.4 JAR](releases/Ascension-1.5.0.jar), [Paper 26.2 JAR](releases/Ascension-1.5.0-paper26.jar)
