# Title runtime verification

The bundled titles.yml definitions contain 30 titles. TitleAbilityRegistry and
TitleAbilityListenerTest verify that each has exactly one activation mode: 19 use the
owner-bound Ascension Relic and 11 activate automatically. A relic must belong to its
owner and equipped title. Simply entering a radius does not trigger an ability.

## Thirty-title Level 1 activation matrix

Ascension 1.5.0 retains these Level 1 abilities and adds two personal upgrades to
every title. See [TITLE_LEVEL_GUIDE.md](TITLE_LEVEL_GUIDE.md) for XP, levels, and
upgrade configuration. Runtime menus and relic lore use the shared resolver to
show the currently equipped level's values.

Ranges are spherical unless stated otherwise. Damage is **raw health points**
(2 HP = 1 heart); armor, Resistance, absorption, and protection plugins can lower
the final damage. An eligible player is a different Survival or Adventure player
in a PvP-enabled world who is not on the owner's scoreboard team.

| # / title | Trigger / mode | Eligible target | Range | Damage or effect | Cooldown | No target / unmet condition |
| --- | --- | --- | --- | --- | --- | --- |
| 1 Ascendant | Relic click | Self | Self | Strength V, Speed III, Resistance IV for 30s | 300s | Buff still casts |
| 2 Conqueror | Relic click | Self; hostile mobs and eligible players | 10 | Strength I self; Weakness II, Slowness IV to targets for 8s | 60s | Self buff still casts |
| 3 Thunder God (ID: stormbringer) | Relic click | Nearest eligible player | 50 | 26 HP and cosmetic lightning | 35s | No cast |
| 4 Tempest Herald | Relic click while aiming | Visible hostile mob or eligible player; then up to two more | 20 primary, 8 chain | 8 HP per strike | 45s | No cast without primary |
| 5 Voidwalker | Relic click while aiming | Self, safe landing block | 32 | Teleport; three charges | 60s per charge | No teleport or charge use |
| 6 Bloodlord | Automatic on every accepted damage hit while below full health | Mobs and eligible players | Combat hit | Heal 20% of final damage, at most 3 HP per hit | None | No heal at full health or on blocked damage |
| 7 Chronomancer | Relic click, then second click | Self | Saved position | Restore saved health and position within 3s | 120s | First click saves; expired second click starts new save |
| 8 Titan | Relic click | Other living mobs and eligible players | 3 | 26 HP and knockback | 30s | No cast |
| 9 Undying | Automatic on otherwise fatal damage | Self | Self | Survive fatal hit | 24h | No activation on nonfatal hit |
| 10 End City Conqueror | Automatic on dangerous fall and landing | Self | Self | Fall protection, Slow Falling 5s, Speed II for 2s on landing | 45s | No activation below 6-block fall |
| 11 Warden's Bane | Automatic on sonic boom or heavy hit | Self | Self | Sonic boom x0.2; hit of at least 16 HP x0.5 | 60s | No activation on smaller hit |
| 12 Frostbound | Relic click | Hostile mobs and eligible players | 5 | 6 HP, Slowness II for 4s | 45s | No cast |
| 13 Witherbound | Relic click | Self; hostile mobs and eligible players | 5 | Clear own Wither; 9 HP to targets | 45s | No cast without Wither or damage |
| 14 Soul Reaper | Valid hostile kill stores soul; relic click spends one | Self | Self | Heal 4 HP; store at most 10 souls | 20s | No cast at full health or without soul |
| 15 Bounty Hunter | Relic click | Assigned normal or renewal hunt target, online and eligible | 50 | Glowing for 5s | 45s | No cast |
| 16 Raidbreaker | Relic click | Raid raiders, two grouped hostiles, or one eligible player | 8 | 12 HP to raiders; 8 HP to others; knockback; Strength during raid | 35s | No cast |
| 17 Tideborn | Relic click | Self | Self | Underwater rush and oxygen; half-force land dash | 30s | Dash still casts |
| 18 Beastbane | Automatic on combat hit against low-health target | Hostile mob or eligible player | Hit | Raise raw hit to 14 HP at 8 or fewer effective HP | 15s | Ordinary hit remains |
| 19 Emberforge | Relic click, then combat hits | Self; hit target | Hit | For 8s, +5 HP attack damage and fire | 60s | Buff still casts |
| 20 Alchemist | Automatic on thrown potion | Potion recipients; harmful expanded splash excludes ineligible players | 5 splash | Noninstant effects last 1.25x; widened splash | 10s | No activation without applicable effects |
| 21 Treasure Whisperer | Relic click; natural loot event | Lootable containers; opener | 16 from cast | Highlight treasure; 5% bonus treasure roll on eligible loot | 60s pulse | No pulse cooldown if none found |
| 22 Berserker | Automatic after taking health damage, then attacking | Hostile mob or eligible player | Hit | Up to +6 HP, fades over 10s | None | No bonus without recent health loss |
| 23 Colossus | Automatic while equipped | Self | Self | +4 max HP (two hearts) | None | Always active while equipped |
| 24 Ashborn | Relic click | Self; hostile mobs or eligible players | 5 | Extinguish self; 8 HP and 4s fire to targets | 45s | No cast if neither burning nor hit |
| 25 Trailblazer | Relic click | Self | Self | Forward dash, fall reset | 25s | Dash still casts |
| 26 Angler | Relic click, then fishing rod cast within 30s | Fishing catch | Rod | Shorter 60-tick wait; treasure catch | 20s | No catch until fishing succeeds |
| 27 Mycelial Drifter | Relic click | Hostile mobs and eligible players | 4 from cast | Four one-second pulses of 2 HP; heal self 2 HP once | 45s | Cloud still casts |
| 28 Huntsman | Automatic on first valid combat hit | Hostile mob or eligible player | Hit | Owner-specific mark for 8s; later hits x1.5 | 20s | No mark on invalid or cancelled hit |
| 29 Stonebreaker | Automatic on natural stone/deepslate break | Connected stone cluster | Adjacent blocks | Up to five extra breaks; 10% auto-smelt chance | 30s | No extra breaks without connected stone |
| 30 New Arrival | Automatic on valid hostile mob kill | Self | Kill | Heal 2 HP and Speed I for 2s | 12s | No effect on other kills |

## Activation and no-target rules

- The 19 manual titles use the owner-bound Ascension Relic (an Amethyst Shard by
  default). A renamed ordinary shard, another player's relic, a stacked relic, or
  an old relic for a different equipped title cannot activate.
- Bounty Hunter uses the assigned renewal hunt while the rank is inactive, then
  returns to the normal hunt. The target must be online, eligible, and in range
  when the asynchronous lookup completes.
- Raidbreaker accepts one eligible player, a raid participant, or two grouped
  hostile mobs. A mob-only cast retains its original raid/group condition.
- Treasure Pulse measures its sphere from the cast position and stops if the owner
  changes worlds. A protection plugin can cancel a damaging ability; a blocked
  hit does not count toward activation.
- Titles already earned remain usable while a rank renewal is pending. Rank
  abilities stop until the renewal restores the same rank.

## Regression coverage and live checks

Automated tests cover relic/automatic classification, radius boundaries,
owner-specific Huntsman marks and Spore Cloud timers, PvP and team filters,
renewal target resolution, Void Step charges, Rewind timing, title-runtime
invalidation, and treasure opener attribution. The full suite and Paper boot
smoke test should be run after a code change.

Live Paper verification remains necessary for relic clicks in air, on blocks,
and on entities; armor and absorption damage; protection-plugin cancellation;
line of sight; fishing and loot; and Java and Bedrock feedback. Paper can
pre-cancel a right-click-air interaction, so distinguish that from an explicit
protection denial.

## Bloodlord on Java and Geyser

Equip Bloodlord, use Survival or Adventure, and reduce the attacker to 15 of
20 HP (7.5 red hearts). Hit an unarmored mob while holding an ordinary weapon.
An accepted 2-HP hit restores 0.4 HP; two accepted hits restore 0.8 HP. This
works above half health and requires no relic, crouch input, or cooldown.
Repeat from the Geyser client, then against a different player in a PvP-enabled
world with no shared scoreboard team. Check server health values as well as
the client heart display, because healing can be less than half a heart.

Repeat at full health and with a protection plugin cancelling damage: neither
case restores health. Damage absorbed entirely by the victim's yellow hearts
has zero final health damage and does not restore red hearts to the attacker.

Verify the deployed JAR as well as its version string. The older
`Ascension-1.2.6-paper26.jar` found in this workspace during the October 4 audit
still contained the PvP-only, below-half-health, four-second-cooldown handler,
although the current source and the Java JAR already contained the every-hit
handler. Rebuild and deploy the JAR for the server's Paper version, restart,
and confirm the loaded plugin before repeating these checks. This local
artifact finding does not establish which JAR a remote server is running.

## Ascension 1.5.0 verification scope

See [PLUGIN_REPORT.md](PLUGIN_REPORT.md) for all 90 default power profiles and title claim requirements. This document describes required scenarios and source coverage; a test name is not evidence of a fresh passing execution. Fresh automated/native results must be recorded in the release verification record. Live two-player/iPad gameplay is pending unless separately evidenced.
