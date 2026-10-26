# Ascension 1.2.5 — Season 4

Target: Paper 26.2, Java 25. No player-data schema migration is required.

## Changes

- Replace manual title sticks with an owner-bound, glowing Amethyst Shard named **Ascension Relic**. The equipped title and ability appear in its lore. Automatic abilities remain automatic.
- Configure the relic with `activation-item.material`, `activation-item.display-name`, and `activation-item.glint` in `config.yml`. Missing settings use the defaults above.
- Existing plugin-tagged sticks are replaced by inventory reconciliation. Ordinary sticks/shards are not activation items. A full inventory defers delivery until space is available.
- Fix online admin cooldown resets: deleted database timers are now removed from the live session. Failed-save recovery still merges pending cooldowns.
- Add cooldown-cycle, save/reload and item regressions, plus clearer manual activation feedback and stale-callback protection. See `TITLE_VERIFICATION.md` for the title audit and verification limits.

## Upgrade

1. Stop the server completely. Back up the current Ascension JAR and the entire `plugins/Ascension` folder, including the SQLite database and any WAL/SHM files still present.
2. Remove the old Ascension JAR from `plugins` and install **only** `Ascension-1.2.5-paper26.jar`. Do not install the Java 21/default build on this target.
3. Keep existing player data and custom YAML files. Missing new settings use defaults; recognized stock stick wording is migrated (title YAML is backed up before saving; message migration is in memory). Custom descriptions remain yours to edit.
4. Start Paper with Java 25. Confirm Ascension 1.2.5 enables without exceptions.
5. Equip a manual title and confirm exactly one relic arrives. Cycle Ascendant → Conqueror → Stormbringer → Ascendant after casting; Ascendant must remain unavailable until its original five-minute timer expires.
6. Check both Java and Bedrock clients: item appearance, air/block/entity interaction, cooldown feedback, title menus, death/respawn and reconnect. Passive titles should not issue a relic.

Use a full restart for the JAR upgrade. `/titles reload` is only for configuration. Existing testing-unlock settings are preserved; verify that they match your intended Season 4 rules.

## Rollback

Stop the server, remove the 1.2.5 JAR and restore the backed-up JAR and matching Ascension folder together. Start the server and check enable logs and player progression. Restoring the backup also restores progression to the backup time.

## Validation record

The final build/test and Paper 26.2 smoke-test results are recorded below after execution. A server console smoke test does not prove real Java/Bedrock gameplay or every interaction with third-party protection plugins.

- Automated regression suite: 211 tests passed, zero failures. Actual Ascendant recast after A/B/C/A was refused; the reported reset did not reproduce against this source. Admin-reset, reconnect/death and relic checks passed.
- Real Java/Bedrock client gameplay and third-party protection-plugin checks remain unverified. Not all 30 abilities have end-to-end gameplay tests; the title table separates source review from automated coverage.

- Paper 26.2 / Java 25 release JAR: built successfully. Isolated server boot, Ascension enable, configuration reload and clean shutdown passed. Log: `build/verify-1.2.5-paper26.log`. Floodgate was not installed in that isolated server.
- SHA-256: `21c077a4ed6b4864f08673207b10cab2fdf524e7337d6efa443c56a83ba508f1`.
