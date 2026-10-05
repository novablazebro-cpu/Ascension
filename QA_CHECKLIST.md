# Release QA checklist — Ascension 1.5.0

Status: manual checklist, not a record of passed tests. Real two-player Java/Bedrock and physical iPad checks remain pending unless a separate dated execution record says otherwise.




Automated tests and a clean Paper boot are necessary but cannot verify client rendering or every
cross-plugin interaction. Run this checklist on the temporary server before production use.

## Java And Bedrock

- Join once and confirm exactly one valid Chronicle appears.
- On Bedrock, confirm the Chronicle opens the form menu instead of the written-book reading screen.
- Equip several titles and confirm each plain-text title appears beside the gamertag on both clients.
- With temporary testing unlocks enabled, test every stage with `/rank test <tier> <stage>` and every title through the Chronicle.
- Open every Chronicle section; verify no clipped text or dead buttons.
- On Bedrock, verify all 30 titles are reachable through the title interface and every label renders normally.
- Accept a quest, relog, restart, and confirm progress remains.
- Test a full inventory and `/titlebook` pending delivery.
- Test death with keepInventory both enabled and disabled.
- Attempt drop, offhand, shift-click, number-key, drag, bundle, shulker, container and creative clones.
- Replay or double-click claim, submission and form actions under high latency.
- Equip each of the 19 manual titles and confirm exactly one matching owner-bound Ascension Relic.
- Switch from a manual title to Bloodlord or another automatic title and confirm the relic disappears.
- Attempt to drop, store, hopper-move, dispense, frame, armor-stand or creative-clone an Ascension Relic.

## Combat And Hunts

- Verify cancelled/protected PvP gives no kill or hunt credit.
- Verify same-team and same-IP farming gives no credit.
- Verify projectile and delayed kills attribute the correct player.
- Verify 25 percent participation for PvP and bosses.
- Verify hunt persistence, offline reroll, admin set/reroll/cancel and target final hit.

## Health And Damage

- Gold II starts below 40 percent effective health, not after losing 40 percent. With 10 red hearts,
  4 remaining hearts is the boundary and must not activate; less than 4 can activate.
- Give a Gold II player 3 red hearts and 2 yellow absorption hearts. Damage that removes only yellow
  hearts must activate regeneration only after red plus yellow falls below 4 hearts. A fully blocked
  hit must not activate it. Recheck when the absorption effect expires without a direct hit.
- Equip Colossus or another max-health modifier and repeat the threshold check using the live maximum.
- Bloodlord heals on every accepted hit against mobs (including passive mobs) and eligible PvP
  players while below full red health. Test rapid consecutive hits with no cooldown. It must not
  heal from cancelled or zero-final-damage hits, ineligible PvP, full health, or overkill beyond
  the victim's remaining HP; healing stays capped at 3 HP per hit.
- Beastbane must count a target's yellow hearts in its 8-HP threshold and must not reduce a weapon
  hit already stronger than its finisher.
- Berserker should gain no rage from an absorption-only hit. Undying must not consume its long
  cooldown while absorption prevents a fatal red-heart hit.
- Repeat title damage checks with armor, Resistance, absorption, PvP disabled, same-team targets,
  protected regions and cancelled damage events.

## Ability Presentation

For all 30 titles, verify activation, sound, particles, damage/healing, cooldown display and cleanup
on both clients. Pay special attention to Stormbringer lightning, Bloodlord particle travel,
Undying resurrection, chain lightning, Ground Slam, War Cry, Chronomancer rewind, Frost Nova,
Molten Edge, Rage, Colossus hearts, Spore Cloud, Void Step, Flame Purge, Corruption Purge and
Treasure Pulse. Confirm there is no fire, entity transformation,
stuck glow/invisibility, repeated activation, visual desync or protected-region damage.
- For Titan, right-click near passive mobs and hostiles; verify damage, knockback, sound and all three
  expanding dust rings. Verify the owner is never hit and cancelled/protected damage causes no knockback.
- For every manual ability, verify right-clicking air, a block and an entity activates at most once.
- For every automatic ability, verify its documented event activates it without an Ascension Relic.

## Weekly Rank Renewal

- On a copied database, set `rank_granted_at` to more than seven days ago. Confirm the held rank
  becomes inactive on join or within the scheduler interval, rank powers stop, and `/rank progress`
  shows a renewal objective.
- Check both renewal branches: a validated kill of the assigned player, and the displayed PvE
  objective. Confirm other players and unrelated mobs do not count. If the assigned player leaves,
  use `/rank target reroll` and verify a new eligible target is assigned.
- Leave a renewal target offline for just under three days, restart, and check that the
  hunt remains. At exactly three continuous days, check a new quest appears; exercise both
  PvE and hunt outcomes. Bring the target online before expiry and verify the timer resets.
  If no target was ever available, count three days from quest assignment.
- Use Bounty Hunter during an inactive renewal hunt and verify it reveals the renewal target.
  After renewal, verify it uses the normal assigned hunt again.
- Put two Huntsmen and two Mycelial Drifter clouds against one victim. Each owner must get
  their own mark or cloud hit. Test Raidbreaker against one eligible PvP player, a teammate,
  and a PvP-disabled world. Test Alchemist harmful splash against those same exclusions.
- Place lootable containers on a Treasure Pulse axis boundary and cube corner, then change
  worlds mid-scan. Only containers inside the original cast sphere should be highlighted.

- Finish the objective and verify the same rank and powers return, the old normal quest resumes
  with its saved progress, and a fresh seven-day clock starts. Repeat after a server restart.

## Failure And Load

- Restart during submission preparation and verify recovery returns no duplicate.
- Race two title claims and verify one owner.
- Disconnect during an asynchronous title ability.
- Run with 25 or more players while monitoring tick time and storage health.
- Test a database write failure on a copy of the server, never on production data.

## Production rules and expanded shop

- Confirm testing unlock is false in both a fresh installation and the actual migrated configuration.
- Attempt title claim before quest completion; reject it. Race two eligible players; exactly one owns the title. Verify offline ownership, maximum five owned titles and one equipped.
- Verify text is plain in chat, name tags, menus, forms, books and relic lore.
- Browse all ten shop categories and 150 products on Java and iPad, including page boundaries and category Back controls. Confirm Elytra bundle one, starting price 64 emerald blocks.
- Verify each new restock interval is 7,200–14,400 seconds, state persists over restart, stock does not refill on restart and old saved deadlines migrate as documented.
- Exercise all 90 title profiles against the complete report, including threshold XP, passive powers and cooldown persistence.
