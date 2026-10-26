# Ascension 1.6.0

Ascension now targets **Paper 26.3 on Java 25**. This release fixes title ability bugs, brings
color back to menus without the unreadable labels that made 1.4.1 remove it, redesigns the chest
menus and animates every manual title ability. No database migration is needed: ranks, titles,
title XP, cooldowns and market records carry over from 1.5.0.

## Install

Stop and back up the server, remove the old Ascension JAR, copy
`releases/Ascension-1.6.0-paper26.3.jar` into `plugins/` and start once. Servers that stay on
Paper 1.21.4 or 26.2 keep using the 1.5.0 JARs in `releases/`.

## Paper 26.3

- Built against `paper-api 26.3.build.200-stable` with `api-version: 26.3` and Java 25 bytecode.
- Alchemist used `PotionEffectTypeCategory`, which 26.3 marks for removal (a later Paper build
  would have broken the title). It now uses `PotionEffectType#getEffectCategory()`.
- PvP checks read the `pvp` gamerule instead of the deprecated `World#getPVP()`.
- The build compiles with zero deprecation or removal warnings.
- The Gradle wrapper moves from 8.8 to 9.8.2. Gradle 8.8 cannot run on Java 25, so the
  documented Java 25 build could not start.

## Bug fixes

- **Treasure Pulse** answered up to 23 seconds after the click (about 7 seconds at the default
  range) because it scanned every block. It now reads only container block entities in loaded
  chunks and answers immediately.
- **Cooldown messages** said "Ability ready in 12s"; they now name the ability.
- **Refuse Death** (Undying) spent its 24-hour cooldown even when the player held a Totem of
  Undying. It now lets the totem work, and it no longer blocks `/kill` or void damage.
- **Warden's Bane** reduced heavy hits at the same priority as Refuse Death checked for lethal
  damage, in no fixed order. Sonic Ward now applies first.
- **Help** was printed to chat and opened in the menu at the same time. It is now one book whose
  tooltip lists every step.
- Removed the unreachable abilities of the six titles retired in 1.2.0. Their listeners still ran
  on every damage, XP and vibration event.
- `gradlew` is executable again (the 1.5.0 source archive had lost the bit).

## Colors and menus

Gray "Help" and "Titles" labels disappeared on the light chest title bar and on light Bedrock
form buttons, so 1.4.1 removed every color. 1.6.0 keeps the colors from `messages.yml`, title
colors and rank colors, and adjusts each one for its surface:

| Surface | Background | Rule |
| --- | --- | --- |
| Item names and lore | near-black tooltip | lightened to at least 4.5:1 contrast |
| Chat and action bar | translucent black | lightened to at least 3:1 |
| Chest window title | light gray | darkened to at least 4.5:1 |
| Bedrock form buttons | light gray | only named colors with at least 3:1 are kept |
| Bedrock form title and body | client default | plain text |

A test renders every bundled message on every surface, so a color that would be unreadable fails
the build. Custom colors in `messages.yml`, `titles.yml` and `ranks.yml` are adjusted the same way.

Chest menus now have a black frame, a framed seven-wide area when the content fits, centered
rows and wrapped descriptions. Larger screens, such as shop pages and the Bedrock fallback title
list, use the full width. Quest objectives and title XP show progress bars; Bedrock forms show
the percentage. Title states are colored, equipped titles show a nether star and claimable ones
an experience bottle. Stats have icons, the Ascension Relic tooltip is colored and menu buttons
click.

## Animations

Every effect is capped at 72 particles per frame and 40 frames, whatever radius a config sets.

| Title | Effect |
| --- | --- |
| Frostbound, Ashborn, Witherbound, Conqueror, Raidbreaker | ring expanding to the real radius |
| Tempest Herald | lightning arcs from you to each chained target |
| Stormbringer | spark column on each struck player |
| Voidwalker, Chronomancer | trail along the path travelled (Rewind marks its anchor) |
| Treasure Whisperer | beam to the chest it found |
| Bounty Hunter | tracer pointing at the hunted player |
| Ascendant, Emberforge, Soul Reaper, Undying | spiral rising around you |
| Trailblazer, Tideborn | trail behind the dash |

Rank-ups, Mastery and title level-ups show an on-screen title with a sound. Six new
`messages.yml` keys control the text (`rank-up-title`, `rank-up-subtitle`, `mastery-title`,
`mastery-subtitle`, `title-level-title`, `title-level-subtitle`); existing configurations
receive them in memory until you add them to the file.

## Verification

- 381 automated tests across 41 suites passed with no failures, errors or skips, running on
  MockBukkit for Paper 26.3. They include 362 of the 363 tests from 1.5.0 (the other one tested
  a removed helper of a retired title), adjusted where the API or the intended behavior changed,
  and 19 new tests for contrast, layout, wrapping, progress bars, animation limits, cooldown names
  and Refuse Death.
- The JAR declares `api-version: 26.3`, version 1.6.0 and contains only Java 25 class files.

Not verified:

- The JAR has not been started on a real Paper 26.3 server. The build environment could not
  download Paper or Mojang server files. Start it on a test server before production.
- Particle animations, menu layout and colors have not been seen in a real Java or Bedrock client.
- The Bedrock button rule assumes the standard light gray form buttons. Other resource packs or UI
  themes may draw them differently.

## Check in game

1. Open the Chronicle on Java and on iPad: every button label is readable, including Help and Titles.
2. Cast each manual title and confirm the animation matches its radius.
3. Treasure Pulse near a dungeon chest answers immediately.
4. Hold a Totem of Undying while wearing Undying and take a lethal hit: the totem saves you and
   Refuse Death stays ready.
5. Complete a rank quest and level a title: the splash appears once.

Copyright (c) 2026 Ayush Anand. Personal modifications are permitted under LICENSE.md;
public redistribution needs the owner's written permission.
