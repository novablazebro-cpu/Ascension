# Recovery And Known Limitations

## Recovery

If Ascension fails to enable, preserve the full log and do not repeatedly restart. Stop the server,
copy `plugins/Ascension/data/ascension.db`, and inspect the newest backup under `backups/`.

To restore, keep the server stopped, archive the failed database, copy the selected backup to the
configured SQLite path, and start once. Never merge SQLite files manually. Keep configuration files
and `ip-salt.txt` with the restored database.

Submission journals are reconciled when a player session loads. Critical title claims and ownership
changes are atomic. `/titles debug <player>` and `/titles audit` provide first-line diagnosis.

## Known Limitations

- MySQL and MariaDB are not supported yet.
- No explicit WorldGuard, party, clan, PlaceholderAPI, LuckPerms or combat-tag adapter is included.
- Scoreboard teams and cancelled Bukkit/Paper events are respected, but each protection plugin must
  still be verified on the actual host.
- Bedrock form appearance and every ability animation require real-client QA.
- Title eligibility begins tracking after this build is installed; historical server statistics are
  not imported automatically.
- Distinct End Cities are identified from Paper's generated-structure bounding boxes.
