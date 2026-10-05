# Title levels in Ascension 1.5.0

Every owned title has three personal levels. Equip a title to earn its XP through validated gameplay. Existing owners begin at Level 1 with their current ability; Level 2 requires 1,000 total XP and Level 3 requires 3,000. Your XP remains after death, disconnecting, restarting, or switching titles. A transferred title uses its new owner's personal XP.

## Earning XP

| Approved action | Title XP |
|---|---:|
| Natural block mined | 1 |
| Smelted item extracted | 1 per item |
| Hostile mob killed | 5 |
| Fish caught | 5 |
| Valid player kill | 50 |
| Assigned hunt completed | 100 |
| Raid won | 100 |
| Credited boss defeat | 200 |

Bosses receive the boss reward rather than an additional ordinary mob reward. PvP and hunts use the existing participation and anti-farming rules. XP belongs to the title equipped when the valid action occurred, even when its verification finishes later. Title XP is separate from vanilla experience levels and weekly rank renewal.

The title must be owned and equipped. Creative/spectator play, standing idle, opening menus, using ability buttons, buying items, currency exchanges, and item submissions do not award title XP. The administrative testing unlock allows equipping titles but does not itself grant ownership or title XP.

## Reading the book

Open the Chronicle, choose **Titles**, and select your title. Its page shows **My Title Level**, current title XP, **Current Ability**, manual or passive activation instructions, **Next Upgrade**, and **Claim Requirements**. Claim requirements are the original requirements to own the title; XP upgrades an already owned title.

The title list, player title tag, and manual ability relic include your current level. Passive abilities work while equipped and do not need a relic. Manual abilities use the bound Ascension Relic. Increasing a title level preserves any cooldown already running.

### Thunder God example

Thunder God is the renamed Stormbringer title. Its internal ID remains `stormbringer`, so existing ownership and cooldowns carry forward. Commands also accept `thunder_god`.

| Level | Lightning targets |
|---|---|
| 1 | Nearest eligible player within 50 blocks |
| 2 | Nearest eligible player within 60 blocks |
| 3 | Every eligible player within 60 blocks, once per activation |

All levels retain the 35-second cooldown and current raw damage. Armor and protection still apply. The caster, teammates, and ineligible players are excluded. Cancelled damage is respected; visual lightning does not start fires. If no target accepts damage, the ability does not spend its cooldown.

## Administrator settings

Edit `plugins/Ascension/title-levels.yml` to tune the two total-XP thresholds, eight reward weights, and title upgrade profiles. Each profile contains Level 2 and Level 3 setting changes relative to the configured Level 1 ability; Level 3 changes are not added on top of Level 2. Existing custom Level 1 ability settings continue to provide the baseline.

Unchanged stock upgrade deltas stop at supported limits for a valid custom baseline: cooldowns cannot go below zero, and chances or lifesteal cannot exceed 100%. Level 1 remains unchanged. For example, a custom 10-second Chronomancer cooldown reaches zero at Levels 2 and 3; a 95% Bloodlord lifesteal reaches 100%. Custom deltas that produce unsupported values, invalid baselines, and malformed profiles still prevent reload.

Existing gameplay caps remain: Voidwalker has at most five charges and a 64-block aim range; Tempest Herald has at most 16 additional chain targets; Treasure Whisperer scans at most 24 blocks; Mycelial Drifter has at most eight cloud pulses. Legacy values above these caps keep their previous effective behavior, and the book describes that effective value.

Use `/titles reload` after editing. Invalid settings are reported and the previous valid configuration remains active. Shop settings in `shop.yml` continue to require a restart.

Title XP uses existing lifetime statistics under `title.xp.<title-id>` and does not require a new database table. Back up the full server and `plugins/Ascension` together before upgrading or restoring.

## Gameplay check

Use two players, including an iPad/Geyser player, to check book opening, plain text, level and XP text, a manual relic, and a passive ability. Check that XP follows the equipped owned title and persists through reconnect. For Thunder God, check single-target and all-target behavior, range boundaries, teammates, and a protected PvP area. Automated checks and the isolated Paper test supplement this real-device check.

## Complete power matrix

[PLUGIN_REPORT.md](PLUGIN_REPORT.md) lists all 30 claim quests, activation descriptions and effective settings/cooldowns at all three levels.
