# Market balance — catalog v3 (source patch, not yet a release)

This describes the market after `patches/market-balance.patch`. The released 1.5.0 JAR still has the old
catalog and exchange described in [MARKET_GUIDE.md](MARKET_GUIDE.md) until you build from the patched source.

Prices are in ordinary **emerald blocks** (1 emerald block = 9 emeralds).

What was checked, and what is still unproven, is in [MARKET_AUDIT.md](MARKET_AUDIT.md). Tideborn's change is in [PATCH_NOTES.md](PATCH_NOTES.md).

## Verdict for a ~20 player SMP

Keep the market. The auction house, collection mailbox and journal recovery are the valuable part: players trade
with each other without Vault, even when the seller is offline. The 1.5.0 defaults had three real balance faults,
which this patch fixes:

| Problem in 1.5.0 | Why it breaks a survival server | Fix |
|---|---|---|
| Exchange worked in both directions at a fixed rate | Emeralds are endless through villager trading. 16 emerald blocks (144 emeralds, minutes in a trading hall) bought a netherite ingot, and 4 bought 9 diamonds. Mining and the Nether became optional. | Exchange only breaks currency down: netherite → diamond blocks → emerald blocks, same rate. |
| Shop sold Elytra, Totems of Undying, Tridents and raw diamonds, 16 of each per restock | Skips the End, raids and ocean monuments; buyable totems make PvP meaningless. | Removed. Replaced with spectral arrows, firework rockets, a chainmail chestplate and flint. |
| Every price could fall to 1 emerald block after restocks | A diamond chestplate, a trident or a totem could end up costing 9 emeralds. | The starting price is now the floor. Demand can raise a price to at most double it (or +2 for 1-block items), then restocks bring it back down to the floor. |

Other changes:
- **Diamond gear** is priced above crafting cost (roughly one emerald block per diamond in the recipe, a convenience
  premium) and has stock 4 (+2 per restock), so one player cannot empty it for the server.
- **Iron and gold** bundles are consistent: ingots 16 for 3, iron blocks 4 for 6, raw iron and raw gold 16 for 2.
- **Ender pearls, golden apples, golden carrots and blaze rods** cost slightly more.
- **Tools and armor** stock is 8 (+4 per restock). Bulk blocks, food and farm goods stay at 16 (+8).

Everything else is unchanged: 150 products, 15 per category, restocks every 2–4 hours per product, +1 price per
purchase and −1 per restock.

## Existing servers

On the first start after updating, `plugins/Ascension/shop.yml` is backed up as `shop.yml.before-catalog-v3-<time>`
and migrated:
- every product still exactly as 1.5.0 shipped it gets its v3 values, or is removed if v3 dropped it;
- the four replacement products are added;
- any product you edited, added or deleted stays exactly as you left it.

Persisted prices are clamped into the new bounds. Removed products disappear from the menu, and a purchase that
is mid-transaction still completes and delivers. The exchange change needs no migration. Deliveries already in
collection keep their recorded amounts.

## Full v3 catalog

### PvP & combat

| Product | v3: bundle, start price (floor–cap), stock +restock | v2 (shipped 1.5.0) |
|---|---|---|
| Iron sword | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Diamond sword | 1× for 3 (3–6), stock 4 +2 | 1× for 2 (1–4), stock 16 |
| Iron axe | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Diamond axe | 1× for 4 (4–8), stock 4 +2 | 1× for 3 (1–6), stock 16 |
| Bow | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Crossbow | 1× for 2 (2–4), stock 8 +4 | 1× for 2 (1–4), stock 16 |
| Arrows | 32× for 1 (1–3), stock 16 +8 | same |
| Shield | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Spectral arrows | 16× for 2 (2–4), stock 16 +8 | **new** |
| Snowballs | 16× for 1 (1–3), stock 16 +8 | same |
| Eggs | 16× for 1 (1–3), stock 16 +8 | same |
| Fishing rod | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Firework rockets | 16× for 2 (2–4), stock 8 +4 | **new** |
| Ender pearls | 4× for 3 (3–6), stock 8 +4 | 4× for 2 (1–4), stock 16 |
| Golden apples | 2× for 5 (5–10), stock 8 +4 | 2× for 3 (1–6), stock 16 |

### Armor & wearables

| Product | v3: bundle, start price (floor–cap), stock +restock | v2 (shipped 1.5.0) |
|---|---|---|
| Iron helmet | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Iron chestplate | 1× for 2 (2–4), stock 8 +4 | 1× for 2 (1–4), stock 16 |
| Iron leggings | 1× for 2 (2–4), stock 8 +4 | 1× for 2 (1–4), stock 16 |
| Iron boots | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Diamond helmet | 1× for 6 (6–12), stock 4 +2 | 1× for 3 (1–6), stock 16 |
| Diamond chestplate | 1× for 10 (10–20), stock 4 +2 | 1× for 5 (1–10), stock 16 |
| Diamond leggings | 1× for 9 (9–18), stock 4 +2 | 1× for 4 (1–8), stock 16 |
| Diamond boots | 1× for 5 (5–10), stock 4 +2 | 1× for 3 (1–6), stock 16 |
| Leather helmet | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Leather tunic | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Leather pants | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Leather boots | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Turtle shell helmet | 1× for 4 (4–8), stock 4 +2 | 1× for 4 (1–8), stock 16 |
| Chainmail chestplate | 1× for 2 (2–4), stock 8 +4 | **new** |
| Carved pumpkin | 1× for 1 (1–3), stock 16 +8 | same |

### Tools & utility

| Product | v3: bundle, start price (floor–cap), stock +restock | v2 (shipped 1.5.0) |
|---|---|---|
| Iron pickaxe | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Diamond pickaxe | 1× for 4 (4–8), stock 4 +2 | 1× for 3 (1–6), stock 16 |
| Iron shovel | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Diamond shovel | 1× for 2 (2–4), stock 4 +2 | 1× for 2 (1–4), stock 16 |
| Iron hoe | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Diamond hoe | 1× for 3 (3–6), stock 4 +2 | 1× for 2 (1–4), stock 16 |
| Shears | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Flint and steel | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Brush | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Compass | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Clock | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Spyglass | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Bucket | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Water bucket | 1× for 1 (1–3), stock 8 +4 | 1× for 1 (1–3), stock 16 |
| Lava bucket | 1× for 2 (2–4), stock 8 +4 | 1× for 2 (1–4), stock 16 |

### Food

| Product | v3: bundle, start price (floor–cap), stock +restock | v2 (shipped 1.5.0) |
|---|---|---|
| Cooked beef | 32× for 1 (1–3), stock 16 +8 | same |
| Cooked porkchops | 32× for 1 (1–3), stock 16 +8 | same |
| Cooked chicken | 32× for 1 (1–3), stock 16 +8 | same |
| Cooked mutton | 32× for 1 (1–3), stock 16 +8 | same |
| Cooked rabbit | 16× for 1 (1–3), stock 16 +8 | same |
| Cooked cod | 32× for 1 (1–3), stock 16 +8 | same |
| Cooked salmon | 32× for 1 (1–3), stock 16 +8 | same |
| Bread | 32× for 1 (1–3), stock 16 +8 | same |
| Baked potatoes | 32× for 1 (1–3), stock 16 +8 | same |
| Carrots | 32× for 1 (1–3), stock 16 +8 | same |
| Golden carrots | 16× for 3 (3–6), stock 16 +8 | 16× for 2 (1–4), stock 16 |
| Apples | 16× for 1 (1–3), stock 16 +8 | same |
| Melon slices | 64× for 1 (1–3), stock 16 +8 | same |
| Dried kelp | 64× for 1 (1–3), stock 16 +8 | same |
| Pumpkin pies | 16× for 1 (1–3), stock 16 +8 | same |

### Ores & resources

| Product | v3: bundle, start price (floor–cap), stock +restock | v2 (shipped 1.5.0) |
|---|---|---|
| Iron block | 4× for 6 (6–12), stock 8 +4 | 1× for 2 (1–4), stock 16 |
| Iron ingots | 16× for 3 (3–6), stock 16 +8 | 8× for 2 (1–4), stock 16 |
| Iron nuggets | 64× for 1 (1–3), stock 16 +8 | same |
| Coal | 32× for 1 (1–3), stock 16 +8 | same |
| Charcoal | 32× for 1 (1–3), stock 16 +8 | same |
| Redstone dust | 32× for 1 (1–3), stock 16 +8 | same |
| Lapis lazuli | 32× for 1 (1–3), stock 16 +8 | same |
| Flint | 32× for 1 (1–3), stock 16 +8 | **new** |
| Gold ingots | 16× for 3 (3–6), stock 16 +8 | 8× for 2 (1–4), stock 16 |
| Gold nuggets | 64× for 1 (1–3), stock 16 +8 | same |
| Raw iron | 16× for 2 (2–4), stock 16 +8 | 16× for 2 (1–4), stock 16 |
| Raw gold | 16× for 2 (2–4), stock 16 +8 | 16× for 2 (1–4), stock 16 |
| Raw copper | 32× for 1 (1–3), stock 16 +8 | same |
| Copper ingots | 32× for 1 (1–3), stock 16 +8 | same |
| Amethyst shards | 16× for 1 (1–3), stock 16 +8 | same |

### Wood & storage

| Product | v3: bundle, start price (floor–cap), stock +restock | v2 (shipped 1.5.0) |
|---|---|---|
| Oak logs | 32× for 1 (1–3), stock 16 +8 | same |
| Spruce logs | 32× for 1 (1–3), stock 16 +8 | same |
| Birch logs | 32× for 1 (1–3), stock 16 +8 | same |
| Jungle logs | 32× for 1 (1–3), stock 16 +8 | same |
| Acacia logs | 32× for 1 (1–3), stock 16 +8 | same |
| Dark oak logs | 32× for 1 (1–3), stock 16 +8 | same |
| Mangrove logs | 32× for 1 (1–3), stock 16 +8 | same |
| Cherry logs | 32× for 1 (1–3), stock 16 +8 | same |
| Bamboo | 64× for 1 (1–3), stock 16 +8 | same |
| Oak planks | 64× for 1 (1–3), stock 16 +8 | same |
| Spruce planks | 64× for 1 (1–3), stock 16 +8 | same |
| Birch planks | 64× for 1 (1–3), stock 16 +8 | same |
| Sticks | 64× for 1 (1–3), stock 16 +8 | same |
| Chests | 8× for 1 (1–3), stock 16 +8 | same |
| Crafting tables | 8× for 1 (1–3), stock 16 +8 | same |

### Stone & masonry

| Product | v3: bundle, start price (floor–cap), stock +restock | v2 (shipped 1.5.0) |
|---|---|---|
| Cobblestone | 64× for 1 (1–3), stock 16 +8 | same |
| Stone | 64× for 1 (1–3), stock 16 +8 | same |
| Smooth stone | 32× for 1 (1–3), stock 16 +8 | same |
| Stone bricks | 32× for 1 (1–3), stock 16 +8 | same |
| Mossy stone bricks | 32× for 2 (2–4), stock 16 +8 | 32× for 2 (1–4), stock 16 |
| Cracked stone bricks | 32× for 1 (1–3), stock 16 +8 | same |
| Andesite | 64× for 1 (1–3), stock 16 +8 | same |
| Diorite | 64× for 1 (1–3), stock 16 +8 | same |
| Granite | 64× for 1 (1–3), stock 16 +8 | same |
| Cobbled deepslate | 64× for 1 (1–3), stock 16 +8 | same |
| Deepslate bricks | 32× for 1 (1–3), stock 16 +8 | same |
| Tuff | 64× for 1 (1–3), stock 16 +8 | same |
| Calcite | 32× for 1 (1–3), stock 16 +8 | same |
| Blackstone | 32× for 1 (1–3), stock 16 +8 | same |
| Basalt | 32× for 1 (1–3), stock 16 +8 | same |

### Building & decoration

| Product | v3: bundle, start price (floor–cap), stock +restock | v2 (shipped 1.5.0) |
|---|---|---|
| Glass | 32× for 1 (1–3), stock 16 +8 | same |
| Glass panes | 64× for 1 (1–3), stock 16 +8 | same |
| Sand | 64× for 1 (1–3), stock 16 +8 | same |
| Sandstone | 32× for 1 (1–3), stock 16 +8 | same |
| Red sand | 32× for 1 (1–3), stock 16 +8 | same |
| Red sandstone | 32× for 1 (1–3), stock 16 +8 | same |
| Gravel | 64× for 1 (1–3), stock 16 +8 | same |
| Clay balls | 64× for 1 (1–3), stock 16 +8 | same |
| Brick items | 32× for 1 (1–3), stock 16 +8 | same |
| Brick blocks | 32× for 2 (2–4), stock 16 +8 | 32× for 2 (1–4), stock 16 |
| Terracotta | 32× for 1 (1–3), stock 16 +8 | same |
| White wool | 32× for 1 (1–3), stock 16 +8 | same |
| White concrete | 32× for 1 (1–3), stock 16 +8 | same |
| Snow blocks | 32× for 1 (1–3), stock 16 +8 | same |
| Ice | 32× for 1 (1–3), stock 16 +8 | same |

### Farming & mob drops

| Product | v3: bundle, start price (floor–cap), stock +restock | v2 (shipped 1.5.0) |
|---|---|---|
| Wheat | 32× for 1 (1–3), stock 16 +8 | same |
| Wheat seeds | 32× for 1 (1–3), stock 16 +8 | same |
| Beetroot seeds | 32× for 1 (1–3), stock 16 +8 | same |
| Pumpkin seeds | 32× for 1 (1–3), stock 16 +8 | same |
| Melon seeds | 32× for 1 (1–3), stock 16 +8 | same |
| Sugar cane | 32× for 1 (1–3), stock 16 +8 | same |
| Cactus | 32× for 1 (1–3), stock 16 +8 | same |
| Kelp | 32× for 1 (1–3), stock 16 +8 | same |
| Sugar | 32× for 1 (1–3), stock 16 +8 | same |
| Leather | 16× for 1 (1–3), stock 16 +8 | same |
| Feathers | 32× for 1 (1–3), stock 16 +8 | same |
| String | 32× for 1 (1–3), stock 16 +8 | same |
| Bones | 32× for 1 (1–3), stock 16 +8 | same |
| Bone meal | 64× for 1 (1–3), stock 16 +8 | same |
| Slimeballs | 16× for 2 (2–4), stock 16 +8 | 16× for 2 (1–4), stock 16 |

### Nether & End

| Product | v3: bundle, start price (floor–cap), stock +restock | v2 (shipped 1.5.0) |
|---|---|---|
| Obsidian | 16× for 4 (4–8), stock 8 +4 | 16× for 4 (2–8), stock 8 |
| Quartz blocks | 16× for 2 (2–4), stock 12 +6 | 16× for 2 (1–5), stock 12 |
| Glowstone | 8× for 2 (2–4), stock 12 +6 | 8× for 2 (1–5), stock 12 |
| Netherrack | 64× for 1 (1–3), stock 16 +8 | same |
| Soul sand | 32× for 1 (1–3), stock 16 +8 | same |
| Soul soil | 32× for 1 (1–3), stock 16 +8 | same |
| Nether brick blocks | 32× for 1 (1–3), stock 16 +8 | same |
| Nether quartz | 32× for 1 (1–3), stock 16 +8 | same |
| Nether wart | 16× for 2 (2–4), stock 16 +8 | 16× for 2 (1–4), stock 16 |
| Blaze powder | 8× for 2 (2–4), stock 16 +8 | 8× for 2 (1–4), stock 16 |
| Blaze rods | 4× for 3 (3–6), stock 8 +4 | 4× for 2 (1–4), stock 16 |
| Magma cream | 8× for 2 (2–4), stock 16 +8 | 8× for 2 (1–4), stock 16 |
| End stone | 32× for 1 (1–3), stock 16 +8 | same |
| Purpur blocks | 32× for 1 (1–3), stock 16 +8 | same |
| Chorus fruit | 16× for 1 (1–3), stock 16 +8 | same |
