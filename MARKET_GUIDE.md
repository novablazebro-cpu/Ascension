# Auction House and Server Shop — Ascension 1.5.0

Open the Chronicle of Ascension and choose **Auction House & Shop**, or use `/auction` (`/ah` and `/market` are aliases). Java players get a chest menu; Geyser players with Floodgate and `bedrock-forms` enabled get native Bedrock forms. Geyser without server-side Floodgate falls back to the chest menu.

## Player auctions

- **Buy Auctions:** browse other players' items and confirm a fixed-price purchase.
- **Sell Items:** select a stack from your inventory, choose emerald blocks, diamond blocks, or netherite ingots, set a price from 1 to 4096, then confirm. One entire stack is one listing; each player may have at most **10 active listings**. Items are removed only when the durable inventory operation is ready. Enchantments, names, damage, contents, and other item metadata are stored as Paper NBT.
- **My Listings:** cancel a listing you own. Its item returns to collection. Listings have no expiry or fee in this release.
- **Collect Items & Payments:** collect bought items, cancelled items, exchanged currency, and seller payments. Sellers can be offline when their items sell. A full inventory leaves the delivery in storage; large payments can be collected in parts. Purchases, cancellations, and currency exchanges require confirmation.

The Chronicle and Ascension's bound ability relics cannot be listed. Only ordinary, unmodified vanilla currency counts as payment; custom-named blocks or tagged relics are not spent. All purchases are server-validated, and a listing cannot be bought by its seller or sold twice. Trading pauses for ten seconds after combat.

## Currency exchange

The book's **Exchange Currency** section consumes real inventory items:

| Give | Receive in collection |
|---|---|
| 4 emerald blocks | 1 diamond block |
| 1 diamond block | 4 emerald blocks |
| 4 diamond blocks | 1 netherite ingot |
| 1 netherite ingot | 4 diamond blocks |
| 16 emerald blocks | 1 netherite ingot |
| 1 netherite ingot | 16 emerald blocks |

The value is **1 netherite ingot = 4 diamond blocks = 16 emerald blocks**, in either direction. Choose a route, then review **You give**, **You receive**, and the collection location before confirming. Confirmation rechecks the ordinary currency currently in your inventory; the output waits in **Collect Items & Payments**. This is an item economy; Vault or another money plugin is not required. Player auction prices are chosen by their sellers, independently of the server shop.

**Upgrade from 1.3.0:** buying a netherite ingot now costs four diamond blocks instead of one. Existing items and player-priced listings are retained. Previously prepared journal operations and existing deliveries keep their recorded amounts; this update does not reprice or rewrite them.

## Demand-priced server shop

Choose **Server Shop** in the market. The default catalog has 150 products in ten categories. See [PLUGIN_REPORT.md](PLUGIN_REPORT.md) for every bundle and starting price; Elytra starts at 64 emerald blocks. Each purchase buys one displayed bundle and pays its quoted price in emerald blocks. The bundle then waits in collection.

Each product has limited stock and its own randomly scheduled restock between **2 and 4 hours**. Every completed purchase raises that product's next price by one emerald block, up to its configured maximum. Each restock adds a configured number of bundles and lowers price by one, down to its minimum. This is a simple demand-based game economy, with configurable bounds. Prices, stock, and the next restock time survive restarts. A changed quote is rejected rather than charging an unseen price. Restarts do not refill the shop, and missed intervals produce one replenishment rather than unlimited catch-up stock.

**Next Restock** shows the soonest product deadline. Each product also shows its own **Restock: HH:MM:SS** countdown, price, and remaining bundles. Java chest-menu countdowns update in place once per second, with stock refreshed after a deadline; leaving that menu invalidates old refresh responses. On iPad forms, countdowns are the snapshot captured when the form opened: press **Refresh Stock & Timers** for current information. Purchase confirmation stays stable while you choose. **Restocking…** means a deadline has arrived, and **Purchase completing…** means the product is reserved by a transaction. Replenishment skips reserved stock until that transaction finishes.

Edit `plugins/Ascension/shop.yml` while the server is stopped to change goods, bundle size, price bounds, stock, and restock timings. Restart to apply shop changes; `/titles reload` does not reload the shop. Currency materials are excluded from server-shop goods so the fixed exchange does not conflict with demand prices. The bundled shop has 150 products across ten categories, with paginated browsing on both interfaces.

## Commands and permissions

`/auction buy`, `sell`, `mine`, `collect`, `shop`, and `exchange` open the corresponding section. `ascension.market` is enabled for players by default. Administrators use `ascension.admin.market`, included in `ascension.admin`.

## Interrupted transactions and recovery

Inventory and SQLite are separate stores. Each trade first records a durable operation and before/after inventory snapshots, then applies and saves the inventory, then atomically commits the market's listing, payment, or delivery. Normal inventory input is briefly blocked while the operation runs. Money is never refunded merely because a callback failed.

On reconnect, an exact before snapshot cancels an untouched operation; an exact after snapshot completes an applied operation. If neither snapshot matches—for example, another plugin changed the inventory during an interrupted write—Ascension pauses that player's market, logs the operation ID, and preserves the record for review. It does not guess and create duplicate items. Administrative inventory changes and restored backups can also require this review; the system cannot atomically commit vanilla player data and SQLite together.

1. `/auction pending` lists unresolved operation IDs, players, and kinds.
2. `/auction recover <online player>` retries automatic snapshot recovery.
3. For an ambiguous case, examine the player's inventory, logs, and backups first. If the journal's inventory change happened, `/auction resolve <operation-id> commit` completes the market side. If the inventory change did not happen, `abort` releases the reservation. **These commands do not change or refund the player's inventory.** A wrong administrator decision can duplicate or lose items, so do not use them as a blind reset. Decisions are recorded in the server log.

Back up the full server and `plugins/Ascension` together before upgrading or restoring. Restoring only the market database or only player data can break their agreement.

## Gameplay checks after installation

Use two players, including an iPad/Geyser player: list an enchanted item, buy it, collect it and the seller payment, then try cancellation, insufficient funds, competing buyers, a full inventory, and reconnecting. Check all six currency routes and confirm outputs match the preview. Buy one shop bundle and confirm its stock decreases and price rises; confirm a later restock stays within bounds. Keep the Java shop open across a restock, refresh an iPad form, and ensure an open confirmation is not replaced. The automated inventory tests use MockBukkit with a substitute codec for its missing NBT support. See the release verification record for fresh checks; do not interpret existing tests as a recorded passing run. Real-device controls and interactions with your other plugins still need a live gameplay check.
