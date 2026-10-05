# Administrator Guide

## Main Commands

- `/rank progress <player>`
- `/rank set <player> <rank> <stage>`
- `/rank advance <player>`
- `/rank reset <player>`
- `/rank quest complete <player> <objective>`
- `/rank target set <hunter> <target>`
- `/rank target reroll <player>`
- `/rank target cancel <player>`
- `/title give <player> <title>`
- `/title revoke <title>`
- `/title transfer <title> <player>`
- `/title reset <player>`
- `/title cooldown reset <player> [title]`
- `/titlebook give <player>`
- `/titles reload`
- `/titles debug <player>`
- `/titles audit [count]`

Every administrative mutation is permission-gated and important mutations are written to the
database audit log. Permission nodes and defaults are declared in `plugin.yml`.

## Operational Rules

- Stop the server before replacing the JAR or restoring a database.
- Never use Bukkit `/reload` or plugin-manager hot reloads.
- Keep `ip-salt.txt` with the database. Replacing it weakens same-IP farming detection.
- Review the console after configuration reloads. Invalid configuration is rejected atomically.
- Keep title ownership changes deliberate; global ownership is database-enforced.

## Full Unlocked Testing

`testing.unlock-all-ranks-and-titles: true` lets every player test all ranks and titles without
overwriting their normal quest history or global title ownership. Players can use
`/rank test <bronze|silver|gold|platinum> <1|2|3>` and equip any title from the Chronicle or with
`/title equip <title-id>`. Equipped titles appear beside the player's name in their configured
title color.

Set `testing.unlock-all-ranks-and-titles: false` and run `/titles reload` whenever you want normal
ownership and progression locks. Unlocked testing is intentionally enabled in the supplied build.
