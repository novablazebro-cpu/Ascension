package com.leadsystems.minecraft.ascension;

import com.leadsystems.minecraft.ascension.ability.AbilityListener;
import com.leadsystems.minecraft.ascension.ability.RankAbilityService;
import com.leadsystems.minecraft.ascension.ability.TitleAbilityListener;
import com.leadsystems.minecraft.ascension.ability.TitleAbilityItems;
import com.leadsystems.minecraft.ascension.api.AscensionApi;
import com.leadsystems.minecraft.ascension.audit.AuditLog;
import com.leadsystems.minecraft.ascension.book.BookItems;
import com.leadsystems.minecraft.ascension.book.BookListener;
import com.leadsystems.minecraft.ascension.book.BookService;
import com.leadsystems.minecraft.ascension.command.RankCommand;
import com.leadsystems.minecraft.ascension.command.TitleBookCommand;
import com.leadsystems.minecraft.ascension.command.TitlesCommand;
import com.leadsystems.minecraft.ascension.command.TitleCommand;
import com.leadsystems.minecraft.ascension.config.ConfigLoader;
import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.gui.BedrockFormRenderer;
import com.leadsystems.minecraft.ascension.gui.JavaMenuRenderer;
import com.leadsystems.minecraft.ascension.gui.MenuListener;
import com.leadsystems.minecraft.ascension.gui.MenuService;
import com.leadsystems.minecraft.ascension.integration.PlatformDetector;
import com.leadsystems.minecraft.ascension.integration.RegistryNameResolver;
import com.leadsystems.minecraft.ascension.market.MarketService;
import com.leadsystems.minecraft.ascension.market.MarketRepository;
import com.leadsystems.minecraft.ascension.market.MarketInventory;
import com.leadsystems.minecraft.ascension.market.MarketListener;
import com.leadsystems.minecraft.ascension.market.MarketCommand;
import com.leadsystems.minecraft.ascension.market.DynamicShopService;
import com.leadsystems.minecraft.ascension.progress.MobClassifier;
import com.leadsystems.minecraft.ascension.progress.CombatProgressListener;
import com.leadsystems.minecraft.ascension.progress.PlacedBlockTracker;
import com.leadsystems.minecraft.ascension.progress.ProgressListener;
import com.leadsystems.minecraft.ascension.progress.ProgressTracker;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.scheduler.TickScheduler;
import com.leadsystems.minecraft.ascension.service.AdminService;
import com.leadsystems.minecraft.ascension.service.QuestService;
import com.leadsystems.minecraft.ascension.service.SubmissionService;
import com.leadsystems.minecraft.ascension.service.HuntService;
import com.leadsystems.minecraft.ascension.service.TitleService;
import com.leadsystems.minecraft.ascension.service.TitleXpService;
import com.leadsystems.minecraft.ascension.service.DeathlessService;
import com.leadsystems.minecraft.ascension.service.RenewalService;
import com.leadsystems.minecraft.ascension.session.IpHasher;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionListener;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.MigrationRunner;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.SqlDatabase;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.storage.StorageHealth;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ascension - rank quests, the permanent Chronicle of Ascension book, and globally exclusive titles.
 * <p>
 * This class only wires services together; all game logic lives in the domain/service packages.
 * Not final: MockBukkit proxies the plugin class in tests.
 */
public class AscensionPlugin extends JavaPlugin {

    private final AtomicReference<ConfigSnapshot> config = new AtomicReference<>();
    private final Messages messages = new Messages();

    private SqlDatabase database;
    private StorageExecutor storage;
    private StorageHealth health;
    private SessionManager sessions;
    private TickScheduler scheduler;
    private BookService books;
    private MenuService menus;
    private QuestService quests;
    private SubmissionService submissions;
    private HuntService hunts;
    private TitleService titles;
    private DeathlessService deathless;
    private RenewalService renewals;
    private RankAbilityService abilities;
    private TitleAbilityItems abilityItems;
    private ProgressTracker progress;
    private MarketService market;
    private TitleXpService titleXp;
    private DynamicShopService shop;
    private int flushCounter;

    @Override
    public void onEnable() {
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            getLogger().warning("Players are already online: this looks like /reload or a plugin manager. "
                    + "Ascension supports it, but a full restart is the only safe way to update plugins.");
        }
        saveDefaults();
        ConfigLoader.LoadResult loaded = loadConfig();
        loaded.warnings().forEach(w -> getLogger().warning(w));
        if (!loaded.ok()) {
            getLogger().severe("Configuration is invalid; Ascension will not start. Problems:");
            loaded.errors().forEach(e -> getLogger().severe(" - " + e));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        config.set(loaded.snapshot());
        messages.load(loaded.snapshot().messages());

        Path data = getDataFolder().toPath();
        ConfigSnapshot.StorageSettings st = loaded.snapshot().storage();
        try {
            database = SqlDatabase.sqlite(data.resolve(st.sqliteFile()));
            new MigrationRunner(database, data.resolve(st.backupDir()), getLogger()).migrate();
        } catch (SQLException | RuntimeException e) {
            getLogger().severe("Cannot open the Ascension database (" + e + "). Ascension will not start "
                    + "rather than run without safe storage.");
            if (database != null) {
                database.close();
                database = null;
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        IpHasher ipHasher;
        try {
            ipHasher = new IpHasher(data.resolve("ip-salt.txt"));
        } catch (IOException e) {
            getLogger().severe("Cannot create ip-salt.txt: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        health = new StorageHealth(getLogger());
        storage = new StorageExecutor(database, health, getLogger());
        PlayerRepository repo = new PlayerRepository(database.dialect());
        MainThread main = new MainThread(this);
        PlatformDetector platform = new PlatformDetector();
        AuditLog audit = new AuditLog(getLogger(), storage, repo);

        sessions = new SessionManager(getLogger(), storage, repo, main, config::get, platform, ipHasher);
        abilities = new RankAbilityService(this, sessions, config::get, messages);
        quests = new QuestService(getLogger(), sessions, storage, repo, health, main, config::get, messages, abilities);
        submissions = new SubmissionService(sessions, storage, repo, health, main, messages);
        books = new BookService(new BookItems(this), sessions, storage, repo, health, main, config::get, messages, audit);
        progress = new ProgressTracker(sessions, quests, config::get);
        renewals = new RenewalService(sessions, storage, repo, main, abilities, messages);
        progress.setRenewals(renewals);
        hunts = new HuntService(sessions, storage, repo, main, progress, config::get, messages);
        hunts.setRenewals(renewals);
        titles = new TitleService(sessions, storage, repo, health, main, config::get, messages);
        deathless = new DeathlessService(sessions, storage, repo, main, config::get, titles, messages);
        menus = new MenuService(sessions, config::get, messages, quests, submissions, hunts, titles, platform,
                new JavaMenuRenderer(), new BedrockFormRenderer(platform, main));
        market = new MarketService(sessions, storage, health, main, getLogger(), new MarketRepository(), MarketInventory.PAPER);
        shop = new DynamicShopService(this, storage, main, market);
        market.setShop(shop);
        menus.setMarket(market);
        books.setMarket(market);
        MobClassifier mobs = new MobClassifier(config::get);
        PlacedBlockTracker placed = new PlacedBlockTracker(this);
        TitleAbilityListener titleAbilities = new TitleAbilityListener(sessions, config::get, mobs,
                placed, storage, repo, main, messages);
        abilityItems = new TitleAbilityItems(this, sessions, config::get, messages,
                titleAbilities::activateFromItem, main);
        abilityItems.setMarket(market);
        titleXp = new TitleXpService(sessions, config::get);
        titleXp.setOnLevelChanged((player, session) -> {
            titles.refreshLevel(player, session);
            titleAbilities.reconcileLevel(player, session);
            abilityItems.refreshLevel(player, session);
        });
        progress.setTitleXp(titleXp);
        AdminService admin = new AdminService(sessions, storage, repo, main, config::get, messages, audit, titles);

        // Check durable trade snapshots before any bound-item creation or cosmetic reconciliation.
        sessions.addHook(market);
        sessions.addHook(books);
        sessions.addHook(submissions);
        sessions.addHook(deathless);
        sessions.addHook(titles);
        sessions.addHook(titleXp);
        sessions.addHook(titleAbilities);
        sessions.addHook(abilityItems);
        sessions.addHook(renewals);
        sessions.addHook(abilities);
        sessions.addHook(quests);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new SessionListener(sessions), this);
        pm.registerEvents(new BookListener(books, sessions, main, messages, p -> menus.open(p, MenuService.MAIN),
                loaded.snapshot().settings().openCooldownMs()), this);
        pm.registerEvents(new MenuListener(menus, main), this);
        pm.registerEvents(new ProgressListener(progress, placed, mobs, config::get, storage, repo, main), this);
        pm.registerEvents(new CombatProgressListener(getLogger(), progress, storage, repo, main,
                config::get, hunts, renewals, titleAbilities, abilities), this);
        pm.registerEvents(deathless, this);
        pm.registerEvents(new AbilityListener(abilities, sessions, mobs, main, config::get), this);
        pm.registerEvents(titleAbilities, this);
        pm.registerEvents(abilityItems, this);
        pm.registerEvents(new MarketListener(market), this);

        register("rank", new RankCommand(sessions, config::get, messages, menus, quests, hunts, admin, storage, repo, main));
        register("titlebook", new TitleBookCommand(books, sessions, messages, audit));
        register("titles", new TitlesCommand(menus, sessions, messages, this::reloadAscension, storage, health, repo, main));
        register("title", new TitleCommand(titles, admin, config::get, messages));
        register("auction", new MarketCommand(market, menus));

        health.onChange(ok -> main.run(() -> notifyAdmins(ok)));

        scheduler = new TickScheduler(this, getLogger());
        scheduler.every("rank-pulses", 1, abilities::tickPulses);
        scheduler.every("book-sweep", 1, () -> books.sweep(config.get().settings().sweepPlayersPerSecond()));
        scheduler.every("ability-item-sweep", 2, abilityItems::reconcileOnline);
        scheduler.every("progress-save", 1, () -> {
            if (++flushCounter >= config.get().settings().flushIntervalSeconds()) {
                flushCounter = 0;
                sessions.flushAll();
            }
        });
        scheduler.every("completion-retry", 5, quests::retryCompletions);
        scheduler.every("rank-renewal", 60, renewals::checkOnline);
        scheduler.every("market-shop-restock", 5, shop::tick);
        scheduler.every("market-menu-timers", 1, menus::tick);
        scheduler.every("permanent-effects", 10, this::ensurePermanentEffects);
        scheduler.every("storage-probe", 10, () -> {
            if (!health.isHealthy()) {
                storage.probe();
            }
        });
        scheduler.every("load-retry", 15, sessions::retryLoads);
        scheduler.every("orphan-retry", 30, sessions::retryOrphans);
        scheduler.every("pulse-prune", 300, abilities::prune);
        scheduler.every("deathless-activity", 60, deathless::tickMinute);
        scheduler.start();
        titles.refreshOwners();

        getServer().getServicesManager().register(AscensionApi.class, new Api(), this, ServicePriority.Normal);
        sessions.adoptOnlinePlayers();
        getLogger().info("Ascension enabled: " + loaded.snapshot().ladder().size() + " rank stages, storage "
                + st.type() + ", Floodgate " + (platform.hasFloodgate() ? "detected" : "not installed") + ".");
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (sessions != null) {
            sessions.shutdown(); // queues final saves and removes applied effects
        }
        if (storage != null) {
            storage.close(); // waits (up to 15 s) for every queued write
        }
        if (database != null) {
            database.close();
        }
        getServer().getServicesManager().unregisterAll(this);
        getLogger().info("Ascension disabled.");
    }

    // ------------------------------------------------------------------ config

    private void saveDefaults() {
        for (String name : ConfigLoader.FILES) {
            if (!new File(getDataFolder(), name).exists()) {
                saveResource(name, false);
            }
        }
    }

    private ConfigLoader.LoadResult loadConfig() {
        List<String> syntax = new ArrayList<>();
        Map<String, YamlConfiguration> files = ConfigLoader.readFolder(getDataFolder(), syntax);
        boolean migratedGoldHealth = files.containsKey("abilities.yml")
                && ConfigLoader.migrateGoldHealthDescription(files.get("abilities.yml"));
        int migratedTitles = 0;
        try (InputStream input = getResource("titles.yml")) {
            if (input == null) {
                syntax.add("titles.yml: bundled defaults are missing from the plugin JAR");
            } else if (files.containsKey("titles.yml")) {
                YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(input, StandardCharsets.UTF_8));
                migratedTitles = ConfigLoader.migrateRetiredTitles(files.get("titles.yml"), bundled);
                int updated = ConfigLoader.mergeLegacyTitleDescriptions(files.get("titles.yml"), bundled);
                updated += ConfigLoader.migrateTitleBalance(files.get("titles.yml"), bundled);
                migratedTitles += updated;
                if (updated > 0) {
                    getLogger().info("Updated " + updated + " legacy title ability setting(s) in memory.");
                }
            }
        } catch (IOException e) {
            syntax.add("titles.yml: cannot read bundled defaults: " + e.getMessage());
        }
        try (InputStream input = getResource("messages.yml")) {
            if (input == null) {
                syntax.add("messages.yml: bundled defaults are missing from the plugin JAR");
            } else {
                YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(input, StandardCharsets.UTF_8));
                YamlConfiguration configured = files.computeIfAbsent("messages.yml", ignored -> new YamlConfiguration());
                int added = ConfigLoader.mergeMissingStrings(configured, bundled);
                if (added > 0) {
                    getLogger().info("Supplied " + added + " new message default(s) missing from messages.yml.");
                }
            }
        } catch (IOException e) {
            syntax.add("messages.yml: cannot read bundled defaults: " + e.getMessage());
        }
        ConfigLoader.LoadResult result = new ConfigLoader(new RegistryNameResolver()).load(files);
        if (result.ok() && syntax.isEmpty() && migratedTitles > 0) {
            Path source = getDataFolder().toPath().resolve("titles.yml");
            Path backupDir = getDataFolder().toPath().resolve("backups");
            try {
                Files.createDirectories(backupDir);
                Path backup = backupDir.resolve("titles-pre-1.2.0-" + System.currentTimeMillis() + ".yml");
                Files.copy(source, backup);
                files.get("titles.yml").save(source.toFile());
                getLogger().info("Applied " + migratedTitles + " title migration changes; original saved to " + backup);
            } catch (IOException e) {
                syntax.add("titles.yml: could not back up and save title migration: " + e.getMessage());
            }
        }
        if (result.ok() && syntax.isEmpty() && migratedGoldHealth) {
            Path source = getDataFolder().toPath().resolve("abilities.yml");
            Path backupDir = getDataFolder().toPath().resolve("backups");
            try {
                Files.createDirectories(backupDir);
                Path backup = backupDir.resolve("abilities-pre-1.2.2-" + System.currentTimeMillis() + ".yml");
                Files.copy(source, backup);
                files.get("abilities.yml").save(source.toFile());
                getLogger().info("Updated Gold II health description; original saved to " + backup);
            } catch (IOException e) {
                syntax.add("abilities.yml: could not back up and save health description: " + e.getMessage());
            }
        }
        if (syntax.isEmpty()) {
            return result;
        }
        List<String> errors = new ArrayList<>(syntax);
        errors.addAll(result.errors());
        return new ConfigLoader.LoadResult(null, errors, result.warnings());
    }

    /**
     * /titles reload: validate first, swap atomically, re-bind sessions. Returns the problems
     * (empty on success). Scheduled jobs are never re-created; storage changes need a restart.
     */
    private List<String> reloadAscension() {
        saveDefaults();
        ConfigLoader.LoadResult r = loadConfig();
        if (!r.ok()) {
            return r.errors();
        }
        ConfigSnapshot old = config.get();
        config.set(r.snapshot());
        messages.load(r.snapshot().messages());
        sessions.rebindAll();
        r.warnings().forEach(w -> getLogger().warning(w));
        if (!old.storage().equals(r.snapshot().storage())) {
            getLogger().warning("storage.yml changed - the new storage settings apply after a full restart.");
        }
        return List.of();
    }

    // ------------------------------------------------------------------ helpers

    private void register(String name, Object handler) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("/" + name + " is missing from plugin.yml");
            return;
        }
        command.setExecutor((CommandExecutor) handler);
        command.setTabCompleter((TabCompleter) handler);
    }

    private void ensurePermanentEffects() {
        for (PlayerSession s : sessions.all()) {
            Player p = Bukkit.getPlayer(s.uuid());
            if (p != null && s.usable() && !p.isDead()) {
                abilities.ensurePermanent(p, s);
            }
        }
    }

    private void notifyAdmins(boolean healthy) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("ascension.admin")) {
                if (healthy) {
                    messages.send(p, "storage-recovered-admin");
                } else {
                    messages.send(p, "storage-degraded-admin", Messages.ph("error", health.lastError()));
                }
            }
        }
    }

    /** For tests and diagnostics. */
    public SessionManager sessions() {
        return sessions;
    }

    public BookService books() {
        return books;
    }

    public QuestService quests() {
        return quests;
    }

    public ProgressTracker progress() {
        return progress;
    }

    public ConfigSnapshot currentConfig() {
        return config.get();
    }

    public StorageExecutor storage() {
        return storage;
    }

    public StorageHealth health() {
        return health;
    }

    public TitleAbilityItems abilityItems() {
        return abilityItems;
    }

    public MarketService market() { return market; }
    public TitleXpService titleXp() { return titleXp; }

    private final class Api implements AscensionApi {
        @Override
        public int rankIndex(UUID player) {
            PlayerSession s = sessions.get(player);
            return s == null || !s.loaded() ? -1 : s.rankIndex();
        }

        @Override
        public String rankName(UUID player) {
            int idx = rankIndex(player);
            return idx < 0 ? null : config.get().ladder().get(idx).name();
        }

        @Override
        public boolean hasMastery(UUID player) {
            PlayerSession s = sessions.get(player);
            return s != null && s.loaded() && s.mastery();
        }

        @Override
        public int stageCount() {
            return config.get().ladder().size();
        }
    }
}
