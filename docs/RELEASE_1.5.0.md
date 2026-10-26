# Ascension 1.5.0

The server shop now has 150 products in ten categories, with 25 products per page.
Each product restocks independently every 2–4 hours. Elytra starts at 64 emerald
blocks and cannot fall below that price. Timers use HH:MM:SS for hour-long waits.
Page-aware refresh, stale-click rejection, and stable purchase confirmations work
through the shared Java/Bedrock menu model. Text is neutral; bound items keep glint.

All 30 titles require their configured quests before a normal or automatic claim.
Each title remains exclusive to one owner; players may hold five and equip one.
Production defaults disable testing. Explicit testing previews require live admin
permission; saved non-owner testing selections no longer grant powers or relics.
Existing ownership, title XP, cooldowns and durable market operations are preserved.

Stop and back up the server, replace the matching Ascension JAR and restart.
Install only one JAR. Keep your database and player data. The shop upgrade backs up
its YAML, adds missing goods, and preserves custom prices/products/timings. Existing
300–900 second default restock intervals upgrade to 7200–14400 seconds. Set
testing.unlock-all-ranks-and-titles to false in production; clear obsolete testing
title selections by unequipping. Custom titles must retain nonempty requirements.

The verification record accompanies this release. Automated checks cover configured
title quests, all 90 level profiles, gameplay protections, persistence, menu tokens,
150 native item definitions, exchanges and market recovery. A corrected Colossus
ownership fixture was rerun after the complete suite; aggregate XML records preserve
every suite. Isolated Paper checks verify the exact release JAR and real item data.
Real two-player Java/iPad gameplay has not been performed and is not claimed.

Copyright (c) 2026 Ayush Anand. Personal modifications are permitted under LICENSE.md;
public redistribution needs the owner's written permission, subject to GitHub's
platform viewing/fork rights and third-party component licenses.
