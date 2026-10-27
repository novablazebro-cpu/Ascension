# Market audit

What was checked, how, what was wrong, and what is still unproven. "Verified" means it ran; "read" means a
person-style code read with no execution.

## Method

1. Read every market class: `MarketCommand`, `MarketListener`, `MarketService`, `MarketMenus`, `MarketRepository`,
   `MarketInventory`, `CurrencyExchange`, `DynamicShopService`, `DynamicShopRepository`, plus how `MenuService`,
   `AscensionPlugin` and plugin.yml wire them together.
2. Ran the real `MarketRepository` and `DynamicShopRepository` against real SQLite with the real V11 and V12
   migrations: **86 checks, all pass** (`bash tools/market-sql-harness/run.sh`).
3. Checked all 150 catalog materials against the Paper 26.3 `Material` enum: all exist (an unknown material would
   stop the whole plugin from enabling).
4. Updated the unit tests for the changes. **They have not been run**: the Paper API cannot be downloaded in the
   environment where this was written. Run `gradle test` before deploying.

## Results by area

| Area | Result |
|---|---|
| Currency exchange rows (3 routes) | Verified: prepare, commit, double commit (one delivery only), abort, one pending operation per player, zero price and non-currency refused |
| Exchange direction | Verified in menus and tests: only netherite to diamond blocks, diamond block to emerald blocks, netherite to emerald blocks. No upward route can be opened or confirmed |
| Server shop | Verified: price rises per purchase, capped at double; restock lowers it to the floor, never below; stock never negative or above max; last bundle cannot be sold twice; abort returns the bundle without raising the price; commit twice gives one delivery |
| Update from 1.5.0 | Verified on a used database: prices and stock clamp into the new bounds, removed products leave the menu, an in-flight Elytra purchase still completes and delivers, re-seeding is idempotent |
| Downtime | Verified: 150 overdue products restock exactly once each, over three 5-second scheduler ticks |
| Auction | Verified: 10-listing limit, no self-buying, wrong price or currency refused, reserved listing cannot be bought or cancelled, sold listing cannot be bought again, stranger cannot cancel or claim, partial claim keeps the remainder, abort restores the listing, paging 10 per page |
| Commands | Read: `/auction` with `buy`, `sell`, `mine`, `collect`, `shop`, `exchange`; admin `pending`, `recover <online player>`, `resolve <id> <commit\|abort>`. Aliases `/ah` and `/market`. Permissions `ascension.market` (everyone) and `ascension.admin.market` (ops) |
| Interrupted-trade recovery | Read only. Logic compares the live inventory with the saved before and after snapshots and refuses to guess. Needs `gradle test` (MarketFlowTest) or a server to prove |
| Menus, Bedrock forms, inventory locking | Read only. Menus are built generically, so the shorter exchange list needs no Bedrock change |

## Bugs found and fixed

1. The Exchange Currency button still said "Convert currency in either direction." Now "Break larger currency into smaller change."
2. Any damage froze trading for 10 seconds, including fire, falls, hunger and drowning. A starving or burning player
   could be locked out of the market. Only damage from another entity counts as combat now.
3. `/auction resolve <id> COMMIT` printed usage because the decision was case-sensitive. It is not now.

## Found, not changed

- Opening any market screen also checks the combat pause, so browsing is blocked for 10 seconds after a fight, not
  only buying. This matches the documented "trading pauses after combat" but is stricter than it sounds.
- If a restock countdown hits zero while a player in combat has the shop open, the refresh prints the combat message
  once per retry until it ends (at most about 10 lines).
- `/auction recover` needs the player online. `/auction resolve` changes the database only, never an inventory; that is
  intentional and printed to the admin.

## Not proven

Real client behaviour, Bedrock and iPad forms, two players racing for the same listing on a live server, and the
Java unit tests. The storage layer under all of it is proven.
