# API And Events

Ascension registers `AscensionApi` with Bukkit's ServicesManager. Other plugins should obtain it
through the service registry instead of reading Ascension's SQLite database.

Published Bukkit events:

- `QuestAcceptedEvent`
- `RankAdvancedEvent`

Handlers must follow Bukkit thread rules and must not perform blocking database work on the server
thread. The current API is intentionally small; global title ownership and administrative mutation
remain server-authoritative internal operations.

PlaceholderAPI expansion and LuckPerms metadata are not included in this build. No placeholder list
is advertised until an expansion exists.
