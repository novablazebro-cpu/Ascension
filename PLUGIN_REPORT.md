# Ascension 1.5.0 plugin report

Copyright (c) 2026 Ayush Anand. Source available under the custom restricted [LICENSE.md](LICENSE.md); this is not an open-source license. Personal modification is permitted by the owner's terms; public redistribution requires written permission. GitHub's platform viewing and fork rights remain subject to GitHub's terms. Read the license for the controlling terms.

Ascension provides 12 rank stages, 30 globally exclusive titles with three personal power levels each, a Chronicle interface, assigned hunts, weekly rank renewal, player auctions, item currency exchange, and a 150-product demand-priced shop. Text in menus, messages, title tags, and relic descriptions is presented without decorative text colors. Ability particles and sound are separate gameplay feedback.

## Platforms and installation

| Server / client | Runtime and artifact | Interface | Verification limit |
|---|---|---|---|
| Paper 1.21.4, Java clients | Java 21, standard Paper artifact | Java chest menus | Automated suite passed; a native run is a separate check |
| Paper 26.2, Java clients | Java 25, Paper 26 artifact | Java chest menus | Automated suite and isolated exact-JAR native check passed |
| iPad / Bedrock through Geyser | Same Paper server plus compatible Geyser and server-side Floodgate; bedrock forms enabled | Native Bedrock forms | Real iPad gameplay was not performed in this documentation review |
| Geyser without server-side Floodgate | Same Paper server | Chest-menu fallback | Not a native-form guarantee |

Use the artifact matching the server version. Stop the server, back up the complete server and plugins/Ascension together, replace the old JAR, and restart. Do not use Bukkit /reload. /titles reload applies supported progression configuration; shop.yml and storage changes require restart. An upgrade must also review existing config.yml: testing.unlock-all-ranks-and-titles must be false to enforce normal locks. Existing custom configurations are not silently equivalent to the bundled defaults.

## Title ownership and claim rules

Every title requires all its displayed title quest requirements before claiming. These are validated lifetime gameplay requirements, distinct from the rank quest accepted in the Chronicle. One owner may hold a given title server-wide, including while offline; inactivity release is disabled. A player may own at most five titles and equip one. Competing claims resolve through durable ownership storage. Undying is automatically awarded to an eligible player, subject to the same exclusive ownership and limit rules. Titles are retained through rank renewal; rank powers pause when renewal is due.

Manual titles use one protected owner-bound Ascension Relic. Automatic titles use the event described below. Cooldowns survive title changes and server restarts. Damage values are raw health points (2 HP = one heart); armor, absorption, Resistance, and protection cancellations affect the result. Eligible PvP targets exclude the owner, teammates, creative/spectator players, and worlds where PvP is disabled.

## All 30 titles and all 90 personal power levels

This matrix is derived from titles.yml, config.yml, title-levels.yml and TitleAbilityResolver's canonical Level 1 settings. Values describe bundled defaults. Level 2 and Level 3 deltas each apply to Level 1, not cumulatively. Range/radius values are blocks; duration/recharge values are seconds; damage/healing/max-health values are HP; force is a velocity parameter; potion levels use Minecraft roman-numeral strength; ratios/chances are fractions. Chain targets are additional targets after the first. Void Step charges recharge independently. A listed cooldown of zero means no cooldown, not a missing power.

### 1. Ascendant — Ascension

ID: `ascendant`. Activation: owner-bound relic click. Right-click the Ascension Relic for Strength V, Speed III and Resistance IV for 30 seconds.

Claim quest requirements (all required):

- Complete Platinum mastery (rank index 11; mastery required)
- Complete 100 assigned hunts (`hunts` ≥ 100; summed across listed keys)
- Complete 500 valid player kills (`pvp.kills` ≥ 500; summed across listed keys)
- Defeat multiple Wardens, Withers, and Elder Guardians (`bosses.WARDEN + bosses.WITHER + bosses.ELDER_GUARDIAN` ≥ 6; summed across listed keys)
- Reach a 50-day deathless streak (50 credited active deathless days)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 300 seconds | `buff-duration-seconds` = 30; `strength-level` = 5; `speed-level` = 3; `balanced-resistance-level` = 4 |
| 2 | 1000 | 300 seconds | `buff-duration-seconds` = 36; `strength-level` = 5; `speed-level` = 3; `balanced-resistance-level` = 4 |
| 3 | 3000 | 300 seconds | `buff-duration-seconds` = 42; `strength-level` = 5; `speed-level` = 3; `balanced-resistance-level` = 4 |

### 2. Conqueror — War Cry

ID: `conqueror`. Activation: owner-bound relic click. Right-click the Ascension Relic to gain Strength and give nearby players and hostile mobs Weakness II and Slowness IV.

Claim quest requirements (all required):

- Complete Platinum mastery (rank index 11; mastery required)
- Complete 50 assigned hunts (`hunts` ≥ 50; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 60 seconds | `radius` = 10; `duration-seconds` = 8; `balanced-weakness-level` = 2; `balanced-slowness-level` = 4 |
| 2 | 1000 | 60 seconds | `radius` = 12; `duration-seconds` = 10; `balanced-weakness-level` = 2; `balanced-slowness-level` = 4 |
| 3 | 3000 | 60 seconds | `radius` = 14; `duration-seconds` = 12; `balanced-weakness-level` = 2; `balanced-slowness-level` = 4 |

### 3. Thunder God — Heaven's Judgment

ID: `stormbringer`. Activation: owner-bound relic click. Right-click the Ascension Relic to strike the closest eligible player within 50 blocks for 13 hearts of raw damage. Armor and protection still apply.

Claim quest requirements (all required):

- Complete Platinum III and mastery (rank index 11; mastery required)
- Defeat a Warden (`bosses.WARDEN` ≥ 1; summed across listed keys)
- Submit a Dragon Head (`submitted.DRAGON_HEAD` ≥ 1; summed across listed keys)
- Complete 100 valid player kills (`pvp.kills` ≥ 100; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 35 seconds | `all-targets` = 0; `burst-damage-health-points` = 26; `range` = 50 |
| 2 | 1000 | 35 seconds | `all-targets` = 0; `burst-damage-health-points` = 26; `range` = 60 |
| 3 | 3000 | 35 seconds | `all-targets` = 1; `burst-damage-health-points` = 26; `range` = 60 |

### 4. Tempest Herald — Chain Lightning

ID: `tempest_herald`. Activation: owner-bound relic click. Look at a target and right-click the Ascension Relic to chain lightning.

Claim quest requirements (all required):

- Possess a Channeling Trident (checked in inventory at claim time)
- Complete 25 storm kills (`pvp.storm_kills` ≥ 25; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 45 seconds | `range` = 20; `chain-range` = 8; `extra-chain-targets` = 2; `chain-damage-health-points` = 8; `chain-multiplier` = 1 |
| 2 | 1000 | 45 seconds | `range` = 24; `chain-range` = 8; `extra-chain-targets` = 3; `chain-damage-health-points` = 8; `chain-multiplier` = 1 |
| 3 | 3000 | 45 seconds | `range` = 28; `chain-range` = 8; `extra-chain-targets` = 4; `chain-damage-health-points` = 8; `chain-multiplier` = 1 |

### 5. Voidwalker — Void Step

ID: `voidwalker`. Activation: owner-bound relic click. Point at a safe landing spot and right-click the Ascension Relic to teleport. Three charges; each returns after 60 seconds.

Claim quest requirements (all required):

- Loot five End City containers (`loot.end_cities` ≥ 5; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 60 seconds | `aim-range` = 32; `charges` = 3; `charge-recharge-seconds` = 60 |
| 2 | 1000 | 60 seconds | `aim-range` = 40; `charges` = 4; `charge-recharge-seconds` = 60 |
| 3 | 3000 | 60 seconds | `aim-range` = 48; `charges` = 5; `charge-recharge-seconds` = 60 |

### 6. Bloodlord — Vampire

ID: `bloodlord`. Activation: automatic / reactive; no relic. Every damaging hit against a mob or eligible PvP player heals 20% of final damage, up to 3 HP per hit. No relic or cooldown required.

Claim quest requirements (all required):

- Complete 200 valid player kills (`pvp.kills` ≥ 200; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | None | `lifesteal-ratio` = 0.2; `max-heal-health-points` = 3 |
| 2 | 1000 | None | `lifesteal-ratio` = 0.25; `max-heal-health-points` = 3.5 |
| 3 | 3000 | None | `lifesteal-ratio` = 0.3; `max-heal-health-points` = 4 |

### 7. Chronomancer — Rewind

ID: `chronomancer`. Activation: owner-bound relic click. Right-click the Ascension Relic to save your position and health, then click again within 3 seconds to rewind.

Claim quest requirements (all required):

- Complete 100 valid nighttime player kills (`pvp.nighttime_kills` ≥ 100; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 120 seconds | `window-seconds` = 3 |
| 2 | 1000 | 110 seconds | `window-seconds` = 4 |
| 3 | 3000 | 100 seconds | `window-seconds` = 5 |

### 8. Titan — Ground Slam

ID: `titan`. Activation: owner-bound relic click. Right-click the Ascension Relic to slam eligible targets within 3 blocks for 13 hearts of raw damage and knockback. Armor and protection still apply.

Claim quest requirements (all required):

- Reach Platinum (rank index 11; mastery not required)
- Defeat multiple Wardens (`bosses.WARDEN` ≥ 2; summed across listed keys)
- Defeat multiple Withers (`bosses.WITHER` ≥ 2; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 30 seconds | `slam-radius` = 3; `slam-damage-health-points` = 26; `slam-knockback` = 1.4 |
| 2 | 1000 | 30 seconds | `slam-radius` = 4; `slam-damage-health-points` = 26; `slam-knockback` = 1.4 |
| 3 | 3000 | 30 seconds | `slam-radius` = 5; `slam-damage-health-points` = 26; `slam-knockback` = 1.4 |

### 9. Undying — Refuse Death

ID: `undying`. Activation: automatic / reactive; no relic. Survive one otherwise fatal attack, with a 24-hour cooldown.

Claim quest requirements (all required):

- Be the first player to complete a 25-day deathless streak (25 credited active deathless days)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 86400 seconds | Fatal-damage rescue; surviving health 1 HP, immunity 3 seconds from config.yml |
| 2 | 1000 | 75600 seconds | Fatal-damage rescue; surviving health 1 HP, immunity 3 seconds from config.yml |
| 3 | 3000 | 64800 seconds | Fatal-damage rescue; surviving health 1 HP, immunity 3 seconds from config.yml |

### 10. End City Conqueror — Sky Rescue

ID: `end_city_conqueror`. Activation: automatic / reactive; no relic. Prevent dangerous fall damage, gain Slow Falling, then Speed II for 2 seconds on landing.

Claim quest requirements (all required):

- Obtain an Elytra from an End structure (`loot.end_city.ELYTRA` ≥ 1; summed across listed keys)
- Obtain a Dragon Head from an End structure (`loot.end_city.DRAGON_HEAD` ≥ 1; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 45 seconds | `minimum-fall-distance` = 6; `slow-falling-seconds` = 5; `landing-speed-seconds` = 2 |
| 2 | 1000 | 45 seconds | `minimum-fall-distance` = 6; `slow-falling-seconds` = 6; `landing-speed-seconds` = 3 |
| 3 | 3000 | 45 seconds | `minimum-fall-distance` = 6; `slow-falling-seconds` = 7; `landing-speed-seconds` = 4 |

### 11. Warden's Bane — Sonic Ward

ID: `wardens_bane`. Activation: automatic / reactive; no relic. Greatly reduce a sonic boom or halve one incoming hit of at least 8 hearts.

Claim quest requirements (all required):

- Kill a Warden after dealing at least 25% of its health (`bosses.WARDEN` ≥ 1; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 60 seconds | `damage-multiplier` = 0.2; `heavy-hit-threshold-health-points` = 16; `heavy-hit-multiplier` = 0.5 |
| 2 | 1000 | 60 seconds | `damage-multiplier` = 0.16; `heavy-hit-threshold-health-points` = 16; `heavy-hit-multiplier` = 0.45 |
| 3 | 3000 | 60 seconds | `damage-multiplier` = 0.12; `heavy-hit-threshold-health-points` = 16; `heavy-hit-multiplier` = 0.4 |

### 12. Frostbound — Frost Nova

ID: `frostbound`. Activation: owner-bound relic click. Right-click the Ascension Relic to deal 6 damage and apply Slowness II to nearby enemies.

Claim quest requirements (all required):

- Loot 10 Ancient City containers (`loot.ancient_city_containers` ≥ 10; summed across listed keys)
- Kill 250 hostile mobs (`kills.hostile` ≥ 250; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 45 seconds | `radius` = 5; `damage-health-points` = 6; `slow-level` = 2; `slow-seconds` = 4 |
| 2 | 1000 | 45 seconds | `radius` = 6; `damage-health-points` = 6; `slow-level` = 2; `slow-seconds` = 5 |
| 3 | 3000 | 45 seconds | `radius` = 7; `damage-health-points` = 6; `slow-level` = 2; `slow-seconds` = 6 |

### 13. Witherbound — Corruption Purge

ID: `witherbound`. Activation: owner-bound relic click. Right-click the Ascension Relic to clear Wither and pulse damage to nearby eligible players and hostile mobs.

Claim quest requirements (all required):

- Defeat 3 Withers (`bosses.WITHER` ≥ 3; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 45 seconds | `radius` = 5; `combat-damage-health-points` = 9 |
| 2 | 1000 | 40 seconds | `radius` = 6; `combat-damage-health-points` = 9 |
| 3 | 3000 | 35 seconds | `radius` = 7; `combat-damage-health-points` = 9 |

### 14. Soul Reaper — Soul Storage

ID: `soul_reaper`. Activation: owner-bound relic click. Valid kills store souls; right-click the Ascension Relic to consume one and heal.

Claim quest requirements (all required):

- Kill 1,500 hostile mobs (`kills.hostile` ≥ 1500; summed across listed keys)
- Complete 50 valid player kills (`pvp.kills` ≥ 50; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 20 seconds | `maximum-souls` = 10; `heal-health-points` = 4 |
| 2 | 1000 | 20 seconds | `maximum-souls` = 15; `heal-health-points` = 5 |
| 3 | 3000 | 20 seconds | `maximum-souls` = 20; `heal-health-points` = 6 |

### 15. Bounty Hunter — Hunter's Sense

ID: `bounty_hunter`. Activation: owner-bound relic click. Right-click the Ascension Relic to reveal a nearby assigned target.

Claim quest requirements (all required):

- Complete 10 assigned hunts (`hunts` ≥ 10; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 45 seconds | `range` = 50; `duration-seconds` = 5 |
| 2 | 1000 | 45 seconds | `range` = 60; `duration-seconds` = 7 |
| 3 | 3000 | 45 seconds | `range` = 70; `duration-seconds` = 9 |

### 16. Raidbreaker — Repulsion Wave

ID: `raidbreaker`. Activation: owner-bound relic click. Right-click the Ascension Relic to repel eligible players, raiders during a raid, or grouped hostile mobs outside raids.

Claim quest requirements (all required):

- Complete 10 raids (`raids` ≥ 10; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 35 seconds | `radius` = 8; `combat-damage-health-points` = 8; `illager-damage-health-points` = 12; `knockback` = 1.2; `raid-strength-seconds` = 8 |
| 2 | 1000 | 35 seconds | `radius` = 9; `combat-damage-health-points` = 8; `illager-damage-health-points` = 12; `knockback` = 1.2; `raid-strength-seconds` = 10 |
| 3 | 3000 | 35 seconds | `radius` = 10; `combat-damage-health-points` = 8; `illager-damage-health-points` = 12; `knockback` = 1.2; `raid-strength-seconds` = 12 |

### 17. Tideborn — Tidal Rush

ID: `tideborn`. Activation: owner-bound relic click. Right-click the Ascension Relic to rush underwater and restore oxygen, or dash at half strength on land.

Claim quest requirements (all required):

- Defeat an Elder Guardian (`bosses.ELDER_GUARDIAN` ≥ 1; summed across listed keys)
- Activate a Conduit (`conduits.activated` ≥ 1; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 30 seconds | `horizontal-force` = 1.8; `land-force-multiplier` = 0.5; `vertical-force` = 0.25 |
| 2 | 1000 | 30 seconds | `horizontal-force` = 2; `land-force-multiplier` = 0.5; `vertical-force` = 0.25 |
| 3 | 3000 | 30 seconds | `horizontal-force` = 2.2; `land-force-multiplier` = 0.5; `vertical-force` = 0.25 |

### 18. Beastbane — Executioner

ID: `beastbane`. Activation: automatic / reactive; no relic. Automatically strike a hostile mob or eligible player at 8 effective HP or less for 14 damage.

Claim quest requirements (all required):

- Kill 1,000 hostile mobs (`kills.hostile` ≥ 1000; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 15 seconds | `health-threshold-health-points` = 8; `execute-damage-health-points` = 14 |
| 2 | 1000 | 15 seconds | `health-threshold-health-points` = 9; `execute-damage-health-points` = 14 |
| 3 | 3000 | 15 seconds | `health-threshold-health-points` = 10; `execute-damage-health-points` = 14 |

### 19. Emberforge — Molten Edge

ID: `emberforge`. Activation: owner-bound relic click. Right-click the Ascension Relic for 8 seconds of +5 attack damage and Fire Aspect II.

Claim quest requirements (all required):

- Repair or combine 100 items (`anvil.operations` ≥ 100; summed across listed keys)
- Kill 100 Nether mobs (`kills.nether` ≥ 100; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 60 seconds | `duration-seconds` = 8; `bonus-damage-health-points` = 5; `fire-seconds` = 8 |
| 2 | 1000 | 60 seconds | `duration-seconds` = 10; `bonus-damage-health-points` = 6; `fire-seconds` = 8 |
| 3 | 3000 | 60 seconds | `duration-seconds` = 12; `bonus-damage-health-points` = 7; `fire-seconds` = 8 |

### 20. Alchemist — Catalyst

ID: `alchemist`. Activation: automatic / reactive; no relic. Thrown potions last longer and splash across a wider area without duplicating effects.

Claim quest requirements (all required):

- Brew 100 potions (`potions.brewed` ≥ 100; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 10 seconds | `duration-multiplier` = 1.25; `splash-radius` = 5 |
| 2 | 1000 | 10 seconds | `duration-multiplier` = 1.4; `splash-radius` = 6 |
| 3 | 3000 | 10 seconds | `duration-multiplier` = 1.55; `splash-radius` = 7 |

### 21. Treasure Whisperer — Treasure Pulse

ID: `treasure_whisperer`. Activation: owner-bound relic click. Right-click the Ascension Relic to highlight nearby treasure; eligible natural containers may grant one bonus treasure roll.

Claim quest requirements (all required):

- Open 30 naturally generated loot containers (`loot.structure_containers` ≥ 30; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 60 seconds | `range` = 16; `bonus-chance` = 0.05 |
| 2 | 1000 | 60 seconds | `range` = 20; `bonus-chance` = 0.075 |
| 3 | 3000 | 60 seconds | `range` = 24; `bonus-chance` = 0.1 |

### 22. Berserker — Rage

ID: `berserker`. Activation: automatic / reactive; no relic. Automatically gain up to 6 bonus damage from health recently lost; rage fades over 10 seconds.

Claim quest requirements (all required):

- Complete 25 valid player kills (`pvp.kills` ≥ 25; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | None | `maximum-bonus-damage` = 6; `decay-seconds` = 10; `health-lost-per-bonus-damage` = 2 |
| 2 | 1000 | None | `maximum-bonus-damage` = 7; `decay-seconds` = 12; `health-lost-per-bonus-damage` = 2 |
| 3 | 3000 | None | `maximum-bonus-damage` = 8; `decay-seconds` = 14; `health-lost-per-bonus-damage` = 2 |

### 23. Colossus — Giant Heart

ID: `colossus`. Activation: automatic / reactive; no relic. While equipped, permanently gain two extra maximum hearts. No stick required.

Claim quest requirements (all required):

- Complete 3 raids (`raids` ≥ 3; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | None | `bonus-max-health-points` = 4 |
| 2 | 1000 | None | `bonus-max-health-points` = 6 |
| 3 | 3000 | None | `bonus-max-health-points` = 8 |

### 24. Ashborn — Flame Purge

ID: `ashborn`. Activation: owner-bound relic click. Right-click the Ascension Relic to extinguish yourself, damage nearby enemies and ignite them for 4 seconds.

Claim quest requirements (all required):

- Kill 100 Nether mobs (`kills.nether` ≥ 100; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 45 seconds | `radius` = 5; `combat-damage-health-points` = 8; `burn-seconds` = 4 |
| 2 | 1000 | 45 seconds | `radius` = 6; `combat-damage-health-points` = 8; `burn-seconds` = 5 |
| 3 | 3000 | 45 seconds | `radius` = 7; `combat-damage-health-points` = 8; `burn-seconds` = 6 |

### 25. Trailblazer — Dash

ID: `trailblazer`. Activation: owner-bound relic click. Right-click the Ascension Relic to dash safely forward.

Claim quest requirements (all required):

- Travel 25,000 blocks (`travel.blocks` ≥ 25000; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 25 seconds | `horizontal-force` = 1.6; `vertical-force` = 0.25 |
| 2 | 1000 | 22 seconds | `horizontal-force` = 1.8; `vertical-force` = 0.25 |
| 3 | 3000 | 20 seconds | `horizontal-force` = 2; `vertical-force` = 0.25 |

### 26. Angler — Deep Lure

ID: `angler`. Activation: owner-bound relic click. Right-click the Ascension Relic, then fish within 30 seconds for a shorter wait and guaranteed treasure catch.

Claim quest requirements (all required):

- Catch 100 fish (`fish.caught` ≥ 100; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 20 seconds | `wait-ticks` = 60 |
| 2 | 1000 | 18 seconds | `wait-ticks` = 50 |
| 3 | 3000 | 16 seconds | `wait-ticks` = 40 |

### 27. Mycelial Drifter — Spore Cloud

ID: `mycelial_drifter`. Activation: owner-bound relic click. Right-click the Ascension Relic to create a four-second damaging cloud that heals you once.

Claim quest requirements (all required):

- Harvest 500 mature crops (`crops.harvested` ≥ 500; summed across listed keys)
- Kill 100 hostile mobs (`kills.hostile` ≥ 100; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 45 seconds | `radius` = 4; `ticks` = 4; `heal-health-points` = 2; `damage-per-tick-health-points` = 2 |
| 2 | 1000 | 45 seconds | `radius` = 5; `ticks` = 4; `heal-health-points` = 3; `damage-per-tick-health-points` = 2 |
| 3 | 3000 | 45 seconds | `radius` = 6; `ticks` = 4; `heal-health-points` = 4; `damage-per-tick-health-points` = 2 |

### 28. Huntsman — Marked Prey

ID: `huntsman`. Activation: automatic / reactive; no relic. The first attacked hostile mob or eligible player is marked and takes increased owner damage for 8 seconds.

Claim quest requirements (all required):

- Kill 250 hostile mobs (`kills.hostile` ≥ 250; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 20 seconds | `duration-seconds` = 8; `combat-damage-multiplier` = 1.5 |
| 2 | 1000 | 20 seconds | `duration-seconds` = 10; `combat-damage-multiplier` = 1.6 |
| 3 | 3000 | 20 seconds | `duration-seconds` = 12; `combat-damage-multiplier` = 1.7 |

### 29. Stonebreaker — Shatter

ID: `stonebreaker`. Activation: automatic / reactive; no relic. Periodically break a small connected stone cluster; some extra drops are automatically smelted.

Claim quest requirements (all required):

- Mine 500 stone or deepslate (`mined.STONE + mined.DEEPSLATE` ≥ 500; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 30 seconds | `maximum-extra-blocks` = 5; `auto-smelt-chance` = 0.1 |
| 2 | 1000 | 30 seconds | `maximum-extra-blocks` = 7; `auto-smelt-chance` = 0.15 |
| 3 | 3000 | 30 seconds | `maximum-extra-blocks` = 9; `auto-smelt-chance` = 0.2 |

### 30. New Arrival — Second Wind

ID: `new_arrival`. Activation: automatic / reactive; no relic. Killing a hostile mob restores one heart and briefly grants Speed I.

Claim quest requirements (all required):

- Gain 30 experience levels (`levels` ≥ 30; summed across listed keys)

| Personal level | Total title XP | Cooldown | Effective power settings |
|---|---:|---|---|
| 1 | 0 | 12 seconds | `restorative-heal-health-points` = 2; `speed-seconds` = 2 |
| 2 | 1000 | 12 seconds | `restorative-heal-health-points` = 3; `speed-seconds` = 3 |
| 3 | 3000 | 12 seconds | `restorative-heal-health-points` = 4; `speed-seconds` = 4 |

## Rank ladder, quests and mastery

Open the Chronicle, accept the current rank quest, then complete all its objectives. Only progress after acceptance counts for rank objectives. Submit objectives consume items through the Submit control. Natural mining/acquisition and PvP credit are validated. Only the current rank ability package applies by default. New players start Bronze I; finishing Platinum III's quest grants Platinum Mastery, not a thirteenth rank.

| Rank held | Accepted quest to advance (last row grants mastery) | Current rank power |
|---|---|---|
| Bronze I | Gain 30 experience levels; Kill 10 hostile mobs; Mine 32 stone or deepslate | Haste I for 5 seconds every 25 seconds |
| Bronze II | Gain 60 experience levels; Kill 10 zombies; Kill 10 skeletons; Smelt 16 iron ingots | Speed I for 5 seconds every 20 seconds |
| Bronze III | Gain 100 experience levels; Kill 35 hostile mobs; Obtain 3 diamonds; Complete 1 valid player kill | Resistance I for 3 seconds after taking damage (30 s cooldown) |
| Silver I | Gain 150 experience levels; Kill 6 different hostile mob types; Obtain 5 diamonds; Complete 1 assigned-player hunt | Permanent Night Vision (no particles) |
| Silver II | Gain 200 experience levels; Kill 10 piglins; Kill 5 blazes; Obtain 5 blaze rods; Kill 2 different players | Haste I for 10 seconds, then a 15-second cooldown |
| Silver III | Gain 275 experience levels; Win 1 raid; Kill 75 hostile mobs; Complete 2 assigned-player hunts | Strength I for 3 seconds after hitting a hostile mob (20 s cooldown) |
| Gold I | Gain 350 experience levels; Kill 1 ravager; Kill 15 illagers; Mine 4 Ancient Debris; Kill 5 players (at least 3 different) | Speed I for 10 seconds, then a 10-second cooldown |
| Gold II | Gain 425 experience levels; Defeat an Elder Guardian; Kill 10 blazes; Kill 5 wither skeletons; Complete 2 assigned-player hunts | Regeneration I for 5 seconds below 40% effective health (45 s cooldown) |
| Gold III | Gain 500 experience levels; Kill a Warden (land the final hit); Submit 1 Dragon Head; Kill 10 players (at least 5 different) | Resistance I for 6 seconds when entering combat (35 s cooldown) |
| Platinum I | Gain 600 experience levels; Summon and defeat a Wither; Win 2 raids; Complete 3 assigned-player hunts | Strength I for 5 seconds after a valid player kill (30 s cooldown) |
| Platinum II | Gain 675 experience levels; Kill a Warden (land the final hit); Submit 2 Dragon Heads; Kill 20 players (at least 10 different) | Speed I and Haste I for 10 seconds, then a 15-second cooldown |
| Platinum III | Gain 750 experience levels; Kill 500 hostile mobs; Defeat a Warden; Defeat a Wither; Defeat an Elder Guardian; Complete 5 assigned-player hunts | +1 heart; crouch while looking down for Resistance I (10 s, 60 s cooldown) |

Rank powers become inactive seven days after grant until renewal is completed. The normal accepted quest pauses and resumes with its saved progress. Renewal may require an assigned player kill or rank-appropriate PvE objective; the Chronicle shows the actual selected objective. An unavailable assigned renewal target may reroll after three continuous days. See PLAYER_GUIDE.md for controls and behavior.

## Title XP

Own and equip a title to earn personal title XP. Level 1 starts at zero; Level 2 requires 1,000 total XP; Level 3 requires 3,000 total XP. Rewards: natural block 1, smelted item 1, hostile mob 5, fishing catch 5, valid player kill 50, hunt 100, raid 100, credited boss 200. Boss credit replaces ordinary mob credit. Shop purchases, currency exchanges, submissions, idle time and ability clicks award no title XP. XP survives death, reconnect, restarts and switching titles. Testing unlock does not grant title ownership or XP.

## Shop, auctions and economy

The 150-item approved preview has ten categories of 15 products. Every row below is one purchase bundle and its starting price in ordinary emerald blocks. Elytra costs one stack (64 emerald blocks). Equipment is ordinary unenchanted vanilla equipment. Demand prices can change within configured bounds after purchases/restocks; confirmation displays the current quote. Stock and demand prices persist across restart. Default randomized restock intervals are 2–4 hours per product. A restart does not refill stock; missed intervals do not create unlimited catch-up stock. Existing persisted short deadlines may complete once before their next hours-long schedule.

Java uses paginated chest menus; Floodgate uses paginated forms. Java countdowns refresh while browsing; Bedrock forms are snapshots and require Refresh Stock & Timers. Bundle delivery waits in Collect Items & Payments. The auction section permits player-priced listings in emerald blocks, diamond blocks or netherite ingots, up to ten active listings per player. Confirm before purchase, cancellation or exchange. Full inventory leaves delivery in collection.

Exchange value: 1 netherite ingot = 4 diamond blocks = 16 emerald blocks in either direction. Only ordinary unmodified currency is spent. Vault is unnecessary. Trading pauses after combat; journal recovery protects interrupted operations and ambiguous snapshots require administrator review rather than guessing.

### PvP & combat

| Product | Bundle per purchase | Starting emerald blocks |
|---|---:|---:|
| Iron sword | 1 | 1 |
| Diamond sword | 1 | 2 |
| Iron axe | 1 | 1 |
| Diamond axe | 1 | 3 |
| Bow | 1 | 1 |
| Crossbow | 1 | 2 |
| Arrows | 32 | 1 |
| Shield | 1 | 1 |
| Trident | 1 | 12 |
| Snowballs | 16 | 1 |
| Eggs | 16 | 1 |
| Fishing rod | 1 | 1 |
| Totem of Undying | 1 | 8 |
| Ender pearls | 4 | 2 |
| Golden apples | 2 | 3 |

### Armor & wearables

| Product | Bundle per purchase | Starting emerald blocks |
|---|---:|---:|
| Iron helmet | 1 | 1 |
| Iron chestplate | 1 | 2 |
| Iron leggings | 1 | 2 |
| Iron boots | 1 | 1 |
| Diamond helmet | 1 | 3 |
| Diamond chestplate | 1 | 5 |
| Diamond leggings | 1 | 4 |
| Diamond boots | 1 | 3 |
| Leather helmet | 1 | 1 |
| Leather tunic | 1 | 1 |
| Leather pants | 1 | 1 |
| Leather boots | 1 | 1 |
| Turtle shell helmet | 1 | 4 |
| Elytra | 1 | 64 |
| Carved pumpkin | 1 | 1 |

### Tools & utility

| Product | Bundle per purchase | Starting emerald blocks |
|---|---:|---:|
| Iron pickaxe | 1 | 1 |
| Diamond pickaxe | 1 | 3 |
| Iron shovel | 1 | 1 |
| Diamond shovel | 1 | 2 |
| Iron hoe | 1 | 1 |
| Diamond hoe | 1 | 2 |
| Shears | 1 | 1 |
| Flint and steel | 1 | 1 |
| Brush | 1 | 1 |
| Compass | 1 | 1 |
| Clock | 1 | 1 |
| Spyglass | 1 | 1 |
| Bucket | 1 | 1 |
| Water bucket | 1 | 1 |
| Lava bucket | 1 | 2 |

### Food

| Product | Bundle per purchase | Starting emerald blocks |
|---|---:|---:|
| Cooked beef | 32 | 1 |
| Cooked porkchops | 32 | 1 |
| Cooked chicken | 32 | 1 |
| Cooked mutton | 32 | 1 |
| Cooked rabbit | 16 | 1 |
| Cooked cod | 32 | 1 |
| Cooked salmon | 32 | 1 |
| Bread | 32 | 1 |
| Baked potatoes | 32 | 1 |
| Carrots | 32 | 1 |
| Golden carrots | 16 | 2 |
| Apples | 16 | 1 |
| Melon slices | 64 | 1 |
| Dried kelp | 64 | 1 |
| Pumpkin pies | 16 | 1 |

### Ores & resources

| Product | Bundle per purchase | Starting emerald blocks |
|---|---:|---:|
| Iron block | 1 | 2 |
| Iron ingots | 8 | 2 |
| Iron nuggets | 64 | 1 |
| Coal | 32 | 1 |
| Charcoal | 32 | 1 |
| Redstone dust | 32 | 1 |
| Lapis lazuli | 32 | 1 |
| Diamond | 1 | 1 |
| Gold ingots | 8 | 2 |
| Gold nuggets | 64 | 1 |
| Raw iron | 16 | 2 |
| Raw gold | 16 | 2 |
| Raw copper | 32 | 1 |
| Copper ingots | 32 | 1 |
| Amethyst shards | 16 | 1 |

### Wood & storage

| Product | Bundle per purchase | Starting emerald blocks |
|---|---:|---:|
| Oak logs | 32 | 1 |
| Spruce logs | 32 | 1 |
| Birch logs | 32 | 1 |
| Jungle logs | 32 | 1 |
| Acacia logs | 32 | 1 |
| Dark oak logs | 32 | 1 |
| Mangrove logs | 32 | 1 |
| Cherry logs | 32 | 1 |
| Bamboo | 64 | 1 |
| Oak planks | 64 | 1 |
| Spruce planks | 64 | 1 |
| Birch planks | 64 | 1 |
| Sticks | 64 | 1 |
| Chests | 8 | 1 |
| Crafting tables | 8 | 1 |

### Stone & masonry

| Product | Bundle per purchase | Starting emerald blocks |
|---|---:|---:|
| Cobblestone | 64 | 1 |
| Stone | 64 | 1 |
| Smooth stone | 32 | 1 |
| Stone bricks | 32 | 1 |
| Mossy stone bricks | 32 | 2 |
| Cracked stone bricks | 32 | 1 |
| Andesite | 64 | 1 |
| Diorite | 64 | 1 |
| Granite | 64 | 1 |
| Cobbled deepslate | 64 | 1 |
| Deepslate bricks | 32 | 1 |
| Tuff | 64 | 1 |
| Calcite | 32 | 1 |
| Blackstone | 32 | 1 |
| Basalt | 32 | 1 |

### Building & decoration

| Product | Bundle per purchase | Starting emerald blocks |
|---|---:|---:|
| Glass | 32 | 1 |
| Glass panes | 64 | 1 |
| Sand | 64 | 1 |
| Sandstone | 32 | 1 |
| Red sand | 32 | 1 |
| Red sandstone | 32 | 1 |
| Gravel | 64 | 1 |
| Clay balls | 64 | 1 |
| Brick items | 32 | 1 |
| Brick blocks | 32 | 2 |
| Terracotta | 32 | 1 |
| White wool | 32 | 1 |
| White concrete | 32 | 1 |
| Snow blocks | 32 | 1 |
| Ice | 32 | 1 |

### Farming & mob drops

| Product | Bundle per purchase | Starting emerald blocks |
|---|---:|---:|
| Wheat | 32 | 1 |
| Wheat seeds | 32 | 1 |
| Beetroot seeds | 32 | 1 |
| Pumpkin seeds | 32 | 1 |
| Melon seeds | 32 | 1 |
| Sugar cane | 32 | 1 |
| Cactus | 32 | 1 |
| Kelp | 32 | 1 |
| Sugar | 32 | 1 |
| Leather | 16 | 1 |
| Feathers | 32 | 1 |
| String | 32 | 1 |
| Bones | 32 | 1 |
| Bone meal | 64 | 1 |
| Slimeballs | 16 | 2 |

### Nether & End

| Product | Bundle per purchase | Starting emerald blocks |
|---|---:|---:|
| Obsidian | 16 | 4 |
| Quartz blocks | 16 | 2 |
| Glowstone | 8 | 2 |
| Netherrack | 64 | 1 |
| Soul sand | 32 | 1 |
| Soul soil | 32 | 1 |
| Nether brick blocks | 32 | 1 |
| Nether quartz | 32 | 1 |
| Nether wart | 16 | 2 |
| Blaze powder | 8 | 2 |
| Blaze rods | 4 | 2 |
| Magma cream | 8 | 2 |
| End stone | 32 | 1 |
| Purpur blocks | 32 | 1 |
| Chorus fruit | 16 | 1 |

## Verification evidence and limits

Documentation review: read bundled configuration, TitleAbilityResolver, TitleAbilityRegistry, TitleLevels, RequirementEvaluator and TitleService. Confirmed by source inspection: 30 definitions, 19 manual/11 automatic modes, three levels, twelve stages, and stat requirements summed across their configured keys. The catalog is taken from the approved 150-item preview and all 150 labels, bundle quantities and base prices match the bundled shop.yml exactly. This is source review, not an assertion that a deployed server or all client interactions passed.

Ownership source audit: both manual claims and automatic awards check the quest requirements before requesting a database mutation. TitleServiceTest includes incomplete/exact-threshold automatic quests, configured New Arrival auto-award, manual claims during testing configuration, ordinary-player testing restrictions, already-owned titles and degraded storage. Explicit administrator testing mode can preview/equip titles without normal ownership, but it does not make an unfinished quest claimable. Production testing unlock should remain disabled. The title_ownership primary key enforces one row per title; repository ownership checks enforce the per-player limit. These source and automated checks supplement, rather than replace, the pending live simultaneous-claim scenario.

Final verification: 363 tests in 37 suites passed with zero failures, errors or skips, using the complete suite plus a rerun of the corrected Colossus ownership fixture. Both supported JARs were built and their metadata/Java bytecode verified. The exact Paper 26.2 release passed the isolated native startup check on October 5, 2026: all 90 title profiles, book identity/glint/restyling, six exchanges, 150 native shop goods and their 2–4-hour schedules, item metadata, migrations, console commands and clean shutdown. See [RELEASE_1.5.0.md](RELEASE_1.5.0.md). The native check does not verify real client controls, other production plugins, multiplayer races or physical iPad rendering.

Live two-player Java/Bedrock gameplay and a physical iPad test were not performed in this documentation review. Before production use, execute QA_CHECKLIST.md, including simultaneous claims, full inventories, protected PvP, every title at all three levels, rank acceptance/renewal, transactions, restart recovery and both client interfaces. Do not label pending checks as passed.
