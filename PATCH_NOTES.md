# Patch notes (source patches on top of Ascension 1.5.0)

None of these are built or released. They are source changes. Build with `gradle build` and run `gradle test` first.

Apply in this order to `Ascension-1.5.0-source-undying-stonebreaker.zip`, or just use the newest zip, which already
contains all of them.

| # | Patch | Newest zip containing it | What it does |
|---|---|---|---|
| 1 | `patches/undying-stonebreaker.patch` | `Ascension-1.5.0-source-undying-stonebreaker.zip` | Undying ignores void and `/kill`; Stonebreaker extras respect protection plugins |
| 2 | `patches/angler-fix-celebrations.patch` | `Ascension-1.5.0-source-angler-celebrations.zip` | Angler wait time now covers both Paper fishing timers; equip, level-up and rank-up animations |
| 3 | `patches/market-balance.patch` | `Ascension-1.5.0-source-market-balance.zip` | One-way currency exchange; shop catalog v3 (no Elytra, Totem, Trident, raw diamonds); price floor |
| 4 | `patches/market-audit-tideborn.patch` | `Ascension-1.5.0-source-market-audit-tideborn.zip` | Tideborn is water-only with a land alternative; market audit fixes; runnable SQL harness |

```bash
unzip Ascension-1.5.0-source-undying-stonebreaker.zip -d ascension && cd ascension
git init -q && git apply ../patches/angler-fix-celebrations.patch ../patches/market-balance.patch ../patches/market-audit-tideborn.patch
```

## Tideborn (patch 4)

Tidal Rush used to dash at half strength on land, which was a weaker copy of Trailblazer's Dash.

- In water: unchanged. Rush forward and refill breath.
- On land: grants Water Breathing and Dolphin's Grace for `land-effect-seconds` (default 30) so you can dive
  prepared. It uses the same 30-second cooldown.
- Servers updating from 1.5.0 get the new description and the `land-effect-seconds` setting added to
  `titles.yml` automatically. A description you edited yourself is left alone, and an old `land-force-multiplier`
  line is harmless and ignored.

See [MARKET_BALANCE.md](MARKET_BALANCE.md) and [MARKET_AUDIT.md](MARKET_AUDIT.md) for the market work.
