# Player Guide

Every player receives one Chronicle of Ascension. Right-click it to open the shared rank and title
interface. Java players receive an inventory menu; Floodgate players receive Bedrock forms when
Floodgate is available.

## Progression

1. Open the Chronicle.
2. Review the current combined rank quest.
3. Press Accept Quest.
4. Complete every objective after acceptance.
5. The rank advances automatically after the final objective commits.

Experience objectives count vanilla experience levels crossed after acceptance. Spending or losing
levels does not remove already recorded progress. Submission objectives require the Submit button;
dropping the item or merely possessing it is not enough.

## Weekly rank renewal

Seven days after a rank is granted, it becomes inactive and its rank abilities stop. The Chronicle
and `/rank progress` show the renewal objective. The player's normal rank quest pauses until the
renewal is finished, then resumes with its saved progress. Completing the renewal restores the same
rank and starts a new seven-day period. Previously earned title abilities remain available
while the rank renewal is pending.

Each renewal has an equal chance of requiring one assigned player kill or a random PvE objective
chosen for the held rank. A player kill must satisfy the server's normal PvP credit rules. Use
`/rank target` to see the assigned target and `/rank target reroll` if that target is offline. If no
eligible target is online, assignment waits until one is available. An unavailable renewal hunt
rerolls after three continuous days: half the time to a rank-appropriate PvE quest and half
the time to a new player hunt. Seeing the target online resets the absence timer.

## Useful Commands

- `/rank`, `/rank progress`, `/rank quests`
- `/rank target`, `/rank target reroll`
- `/titles`
- `/title claim <title>`
- `/title equip <title>`
- `/title unequip`
- `/title info <title>`
- `/title owner <title>`
- `/titlebook`

Every title requires its displayed title quest requirements. Production testing unlock must be disabled to enforce these locks. Only one player can own each global title, even when offline. A player can own up to five titles. A player may equip one owned title at a time. Ability
cooldowns remain active through death, relogging, restarts and title switching.

## Title Abilities

Titles with a manual power give their owner one protected Ascension Relic (an Amethyst Shard by
default) when equipped. Right-click the relic in the air, at a block, or at an entity to activate
the power. The relic updates when the
equipped title changes and cannot be dropped, stored, duplicated, displayed, or used by another
player. Free one inventory slot if delivery is pending.

Passive and reactive powers do not issue a relic. Vampire healing, extra hearts, potion-related
effects, fatal-damage rescue, guards, marked attacks, repairs, fishing and similar powers trigger
from their documented event. Vampire heals from every accepted hit on a mob or eligible PvP player
while you are below full health; it has no cooldown. Other powers keep their listed cooldowns.

## Shop and complete reference

The market contains 150 products, auctions, collection and currency exchange. Elytra starts at 64 emerald blocks and each product restocks on its own randomized 2–4 hour interval. See [MARKET_GUIDE.md](MARKET_GUIDE.md) for trading and [PLUGIN_REPORT.md](PLUGIN_REPORT.md) for every title level, rank quest and shop bundle.
