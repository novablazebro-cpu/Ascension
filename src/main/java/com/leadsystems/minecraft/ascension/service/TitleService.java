package com.leadsystems.minecraft.ascension.service;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.title.Requirement;
import com.leadsystems.minecraft.ascension.domain.title.RequirementContext;
import com.leadsystems.minecraft.ascension.domain.title.RequirementEvaluator;
import com.leadsystems.minecraft.ascension.domain.title.RequirementProgress;
import com.leadsystems.minecraft.ascension.domain.title.TitleDefinition;
import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityResolver;
import com.leadsystems.minecraft.ascension.domain.title.TitleState;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.session.SessionHook;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.storage.StorageHealth;
import com.leadsystems.minecraft.ascension.text.Messages;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Atomic global title ownership and one-equipped-title state. */
public final class TitleService implements SessionHook {

    private static final String OP_CLAIM = "title.claim";
    private static final String OP_EQUIP = "title.equip";

    private final SessionManager sessions;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final StorageHealth health;
    private final MainThread main;
    private final Supplier<ConfigSnapshot> config;
    private final Messages messages;
    private final AtomicReference<Map<String, UUID>> owners = new AtomicReference<>(Map.of());
    private final Map<UUID, String> tagTeams = new HashMap<>();
    private final TitleAbilityResolver levelResolver;

    public TitleService(SessionManager sessions, StorageExecutor storage, PlayerRepository repo,
                        StorageHealth health, MainThread main, Supplier<ConfigSnapshot> config,
                        Messages messages) {
        this.sessions = sessions;
        this.storage = storage;
        this.repo = repo;
        this.health = health;
        this.main = main;
        this.config = config;
        this.messages = messages;
        this.levelResolver = new TitleAbilityResolver(config);
    }

    public void refreshOwners() {
        main.onComplete(storage.submit("titles.owners", repo::titleOwners), (loaded, error) -> {
            if (error == null && loaded != null) {
                owners.set(Map.copyOf(loaded));
            }
        });
    }

    public UUID owner(String titleId) {
        return owners.get().get(titleId);
    }

    public TitleState state(Player player, PlayerSession session, TitleDefinition title) {
        if (testingAllowed(player)) {
            return TitleState.OWNED;
        }
        UUID owner = owner(title.id());
        boolean eligible = RequirementEvaluator.claimable(title, context(player, session));
        return TitleState.of(eligible, owner, session.uuid(), session.inFlight(OP_CLAIM));
    }

    public List<RequirementProgress> progress(Player player, PlayerSession session, TitleDefinition title) {
        RequirementContext ctx = context(player, session);
        return title.requirements().stream().map(r -> RequirementEvaluator.evaluate(r, ctx)).toList();
    }

    public void claim(Player player, String reference, Runnable after) {
        PlayerSession session = sessions.get(player);
        TitleDefinition title = config.get().titles().resolve(reference);
        if (session == null || !session.usable() || title == null) {
            messages.send(player, "title-invalid");
            return;
        }
        if (!RequirementEvaluator.claimable(title, context(player, session))) {
            messages.send(player, "title-not-eligible");
            return;
        }
        if (!health.isHealthy()) {
            messages.send(player, "storage-degraded");
            return;
        }
        if (!session.tryBegin(OP_CLAIM)) {
            messages.send(player, "quest-busy");
            return;
        }
        sessions.mutate(session.uuid(), "title.claim", c -> repo.claimTitle(c, title.id(), session.uuid(),
                config.get().titleOwnershipLimit(), System.currentTimeMillis()), (claimed, error) -> {
            session.end(OP_CLAIM);
            refreshOwners();
            if (error != null) {
                messages.send(player, "storage-degraded");
            } else if (Boolean.TRUE.equals(claimed)) {
                messages.send(player, "title-claimed", Messages.ph("title", title.name()));
                after.run();
            } else {
                messages.send(player, "title-claim-lost");
            }
        });
    }

    public void equip(Player player, String reference, Runnable after) {
        changeEquip(player, reference, false, after);
    }

    public void unequip(Player player, Runnable after) {
        changeEquip(player, null, true, after);
    }

    /** Awards a configured automatic title; the database unique key chooses exactly one winner. */
    public void autoAward(Player player, String titleId) {
        PlayerSession session = sessions.get(player);
        TitleDefinition title = config.get().titles().byId(titleId);
        if (session == null || !session.usable() || title == null || !title.autoAward()
                || owner(titleId) != null || !health.isHealthy()
                || !RequirementEvaluator.claimable(title, context(player, session))
                || !session.tryBegin(OP_CLAIM)) {
            return;
        }
        sessions.mutate(session.uuid(), "title.auto-award", c -> repo.claimTitle(c, title.id(), session.uuid(),
                config.get().titleOwnershipLimit(), System.currentTimeMillis()), (claimed, error) -> {
            session.end(OP_CLAIM);
            refreshOwners();
            if (error == null && Boolean.TRUE.equals(claimed)) {
                messages.send(player, "title-auto-awarded", Messages.ph("title", title.name()));
            }
        });
    }

    @Override
    public void onLoaded(Player player, PlayerSession session, boolean reload) {
        updateTitleTag(player, session);
    }

    @Override
    public void onUnload(Player player, PlayerSession session) {
        clearTitleTag(player);
    }

    private void changeEquip(Player player, String reference, boolean clear, Runnable after) {
        PlayerSession session = sessions.get(player);
        TitleDefinition title = clear ? null : config.get().titles().resolve(reference);
        if (session == null || !session.usable() || (!clear && title == null)) {
            messages.send(player, "title-invalid");
            return;
        }
        if (!health.isHealthy()) {
            messages.send(player, "storage-degraded");
            return;
        }
        if (!session.tryBegin(OP_EQUIP)) {
            messages.send(player, "quest-busy");
            return;
        }
        boolean testing = testingAllowed(player);
        sessions.mutate(session.uuid(), clear ? "title.unequip" : "title.equip", c -> {
            if (clear) {
                repo.unequipTitle(c, session.uuid());
                return true;
            }
            return testing
                    ? repo.equipTitleForTesting(c, session.uuid(), title.id())
                    : repo.equipTitle(c, session.uuid(), title.id());
        }, (changed, error) -> {
            session.end(OP_EQUIP);
            if (error != null) {
                messages.send(player, "storage-degraded");
            } else if (Boolean.TRUE.equals(changed)) {
                messages.send(player, clear ? "title-unequipped" : "title-equipped",
                        Messages.ph("title", clear ? "" : title.name()));
                after.run();
            } else {
                messages.send(player, "title-not-owned");
            }
        });
    }

    private void updateTitleTag(Player player, PlayerSession session) {
        clearTitleTag(player);
        if (session.equippedTitle() == null || (!session.ownedTitles().contains(session.equippedTitle())
                && !testingAllowed(player))) {
            return;
        }
        TitleDefinition title = config.get().titles().byId(session.equippedTitle());
        if (title == null) {
            return;
        }
        Component suffix = Component.text(" [" + title.name() + " Lv." + levelResolver.level(session, title.id()) + "]", NamedTextColor.WHITE);
        player.playerListName(Component.text(player.getName()).append(suffix));

        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team current = scoreboard.getEntryTeam(player.getName());
        if (current != null && !current.getName().startsWith("asc_")) {
            return; // preserve party/clan scoreboards; the title still appears in the player list
        }
        String teamName = "asc_" + player.getUniqueId().toString().replace("-", "").substring(0, 12);
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        team.suffix(suffix);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        team.addEntry(player.getName());
        tagTeams.put(player.getUniqueId(), teamName);
    }

    public void refreshLevel(Player player, PlayerSession session) {
        if (player.isOnline() && sessions.get(player) == session) updateTitleTag(player, session);
    }

    /** Testing selection is an explicit administrator tool, never a player quest bypass. */
    private boolean testingAllowed(Player player) {
        return config.get().testingUnlockAll() && player.hasPermission("ascension.admin");
    }

    private void clearTitleTag(Player player) {
        player.playerListName(null);
        String teamName = tagTeams.remove(player.getUniqueId());
        if (teamName == null) {
            return;
        }
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(teamName);
        if (team != null) {
            team.removeEntry(player.getName());
            if (team.getEntries().isEmpty()) {
                team.unregister();
            }
        }
    }

    private static RequirementContext context(Player player, PlayerSession session) {
        return new RequirementContext() {
            @Override public long stat(String key) { return session.stat(key); }
            @Override public int rankIndex() { return session.rankUsable(System.currentTimeMillis()) ? session.rankIndex() : -1; }
            @Override public boolean mastery() { return session.rankUsable(System.currentTimeMillis()) && session.mastery(); }
            @Override public int bestStreakDays() { return (int) session.stat("deathless.best_days"); }
            @Override public Boolean possesses(String material, String enchantment) {
                Material wanted = Material.matchMaterial(material);
                if (wanted == null) return false;
                Enchantment wantedEnchant = enchantment == null ? null
                        : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                                .get(NamespacedKey.minecraft(enchantment.toLowerCase()));
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && item.getType() == wanted
                            && (wantedEnchant == null || item.containsEnchantment(wantedEnchant))) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
