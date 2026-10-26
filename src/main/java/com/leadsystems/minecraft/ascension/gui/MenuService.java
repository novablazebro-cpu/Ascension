package com.leadsystems.minecraft.ascension.gui;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import com.leadsystems.minecraft.ascension.domain.quest.ActiveQuest;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveDefinition;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveStatus;
import com.leadsystems.minecraft.ascension.domain.quest.QuestState;
import com.leadsystems.minecraft.ascension.domain.rank.RankStage;
import com.leadsystems.minecraft.ascension.domain.title.RequirementProgress;
import com.leadsystems.minecraft.ascension.domain.title.TitleDefinition;
import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityRegistry;
import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityResolver;
import com.leadsystems.minecraft.ascension.domain.title.TitleState;
import com.leadsystems.minecraft.ascension.integration.PlatformDetector;
import com.leadsystems.minecraft.ascension.market.MarketService;
import com.leadsystems.minecraft.ascension.service.QuestService;
import com.leadsystems.minecraft.ascension.service.SubmissionService;
import com.leadsystems.minecraft.ascension.service.HuntService;
import com.leadsystems.minecraft.ascension.service.TitleService;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.text.Messages;
import com.leadsystems.minecraft.ascension.text.ProgressBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds the Chronicle's screens and executes their actions.
 * <p>
 * Security model: each render issues a fresh one-time token (invalidating any older menu). An
 * action runs only if it carries the viewer's current, unused token, and every action is
 * re-validated against live server state - the client only ever picks one of the buttons the
 * server offered, never supplies data.
 */
public final class MenuService {

    public static final String MAIN = "main";

    private final SessionManager sessions;
    private final Supplier<ConfigSnapshot> config;
    private final Messages messages;
    private final QuestService quests;
    private final SubmissionService submissions;
    private final HuntService hunts;
    private final TitleService titles;
    private final PlatformDetector platform;
    private final JavaMenuRenderer java;
    private final BedrockFormRenderer bedrock;
    private final TitleAbilityResolver levelResolver;
    private MarketService market;
    private final Map<UUID, Long> renderRequests = new HashMap<>();

    public MenuService(SessionManager sessions, Supplier<ConfigSnapshot> config, Messages messages,
                       QuestService quests, SubmissionService submissions, HuntService hunts, TitleService titles,
                       PlatformDetector platform, JavaMenuRenderer java,
                       BedrockFormRenderer bedrock) {
        this.sessions = sessions;
        this.config = config;
        this.messages = messages;
        this.quests = quests;
        this.submissions = submissions;
        this.hunts = hunts;
        this.titles = titles;
        this.platform = platform;
        this.java = java;
        this.bedrock = bedrock;
        this.levelResolver = new TitleAbilityResolver(config);
    }

    // ------------------------------------------------------------------ open / handle

    public void setMarket(MarketService market) { this.market = market; }
    public void forget(UUID player) { renderRequests.remove(player); }

    /** Closing an old chest while opening a newer screen must not cancel the new request. */
    public void closed(UUID player, long token) {
        if (Objects.equals(renderRequests.get(player), token)) {
            renderRequests.remove(player);
            PlayerSession session = sessions.get(player);
            if (session != null) session.consumeMenuToken(token);
        }
    }

    /** Decorative Java countdown updates keep the same chest open; stock updates rotate tokens. */
    public void tick() {
        if (market == null) return;
        for (PlayerSession session : sessions.all()) {
            Player player = Bukkit.getPlayer(session.uuid());
            Long token = renderRequests.get(session.uuid());
            if (player == null || token == null || !session.usable() || market.inventoryLocked(session.uuid())) continue;
            String page = java.shopPage(player, token);
            if (page == null) continue;
            java.tick(player, token, () -> market.open(player, page, screen -> {
                if (!player.isOnline() || sessions.get(player) != session
                        || !Objects.equals(renderRequests.get(session.uuid()), token) || !java.owns(player, token)) return;
                long fresh = session.newMenuToken();
                if (java.refresh(player, token, screen, fresh)) renderRequests.put(session.uuid(), fresh);
            }));
            // Missing or failed async reads release the renderer's refresh guard for a bounded retry.
            java.refreshFailedIfTimedOut(player, token);
        }
    }

    public void open(Player player, String screenId) {
        PlayerSession s = sessions.get(player);
        if (s == null || !s.usable()) {
            messages.send(player, "data-unavailable");
            return;
        }
        long token = s.newMenuToken();
        renderRequests.put(s.uuid(), token);
        if (market != null && (screenId.equals("market") || screenId.startsWith("market_"))) {
            market.open(player, screenId, screen -> {
                if (player.isOnline() && sessions.get(player) == s
                        && Objects.equals(renderRequests.get(s.uuid()), token)) {
                    render(player, s, screen, token);
                }
            });
            return;
        }
        render(player, s, build(s, screenId), token);
    }

    private void render(Player player, PlayerSession s, Menu.Screen screen, long token) {
        if (s.bedrock() && config.get().bedrockForms() && platform.hasFloodgate()) {
            player.closeInventory(); // a form cannot show over an open chest menu
            if (bedrock.render(player, screen, token, this::onFormClick)) {
                return;
            }
        }
        java.render(player, screen, token);
    }

    private void onFormClick(UUID viewer, long token, Menu.Button button) {
        Player p = Bukkit.getPlayer(viewer);
        if (p != null) {
            handle(p, token, button);
        }
    }

    /** Executes a button press (main thread). */
    public void handle(Player player, long token, Menu.Button button) {
        PlayerSession s = sessions.get(player);
        if (s == null || !s.usable()) {
            return;
        }
        if (!Objects.equals(renderRequests.get(s.uuid()), token) || !s.consumeMenuToken(token)) {
            messages.send(player, "menu-stale");
            return;
        }
        renderRequests.remove(s.uuid());
        switch (button.action()) {
            case OPEN -> {
                if ("target".equals(button.arg())) {
                    player.closeInventory();
                    hunts.showOrAssign(player, false);
                } else {
                    open(player, button.arg());
                }
            }
            case ACCEPT_QUEST -> open(player, "confirm_accept");
            case CONFIRM_ACCEPT -> {
                player.closeInventory();
                int expected = parse(button.arg());
                if (expected != s.rankIndex()) {
                    messages.send(player, "menu-stale"); // the menu was built for an older stage
                    return;
                }
                quests.accept(player, () -> open(player, "active_quest"));
            }
            case SUBMIT_ITEM -> submissions.submit(player, button.arg(), () -> open(player, "active_quest"));
            case TITLE_CLAIM -> titles.claim(player, button.arg(), () -> open(player, "title:" + button.arg()));
            case TITLE_EQUIP -> titles.equip(player, button.arg(), () -> open(player, "title:" + button.arg()));
            case TITLE_UNEQUIP -> titles.unequip(player, () -> open(player, "title:" + button.arg()));
            case MARKET_BUY, MARKET_CANCEL, MARKET_SELL, MARKET_CLAIM, MARKET_EXCHANGE, MARKET_SHOP_BUY -> {
                if (market != null) market.handle(player, button.action(), button.arg(), () ->
                        open(player, button.action() == Menu.Action.MARKET_SELL ? "market_mine:0" : "market_deliveries:0"));
            }
            case CLOSE -> player.closeInventory();
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ screens

    Menu.Screen build(PlayerSession s, String id) {
        return switch (id) {
            case "profile" -> profile(s);
            case "take_quest" -> takeQuest(s);
            case "confirm_accept" -> confirmAccept(s);
            case "active_quest" -> activeQuest(s);
            case "target" -> later(s, "menu.target-title");
            case "titles" -> titleList(s, 0);
            case "abilities" -> abilities(s);
            case "stats" -> stats(s);
            case "help" -> help();
            default -> id.startsWith("title:") ? titleDetail(s, id.substring("title:".length()))
                    : id.startsWith("titles:") ? titleList(s, parse(id.substring("titles:".length()))) : main(s);
        };
    }

    private Menu.Screen titleList(PlayerSession s, int page) {
        List<TitleDefinition> all = config.get().titles().ordered();
        boolean singleScrollingList = usesBedrockForms(s);
        TitleRange range = titleRange(all.size(), page, singleScrollingList);
        page = singleScrollingList ? 0 : range.from() / 10;
        List<Menu.Button> buttons = new ArrayList<>();
        for (TitleDefinition title : all.subList(range.from(), range.to())) {
            Player player = Bukkit.getPlayer(s.uuid());
            TitleState titleState = player == null ? TitleState.UNAVAILABLE : titles.state(player, s, title);
            String state = switch (titleState) {
                case OWNED -> title.id().equals(s.equippedTitle()) ? "equipped" : "wearable";
                case ELIGIBLE -> "available";
                case TAKEN -> "claimed";
                case CLAIMING -> "claiming";
                case UNAVAILABLE -> "locked";
            };
            buttons.add(new Menu.Button(titleName(title),
                    List.of(Component.text(state, stateColor(state)),
                            Component.text("Level " + levelResolver.level(s, title.id()) + " · "
                                    + levelResolver.xp(s, title.id()) + " title XP", NamedTextColor.AQUA),
                            xpBar(s, title.id())),
                    titleIcon(titleState, state), Menu.Action.OPEN, "title:" + title.id(), false));
        }
        if (range.previous()) {
            buttons.add(new Menu.Button(msg("menu.previous"), List.of(), Material.ARROW,
                    Menu.Action.OPEN, page == 1 ? "titles" : "titles:" + (page - 1), true));
        }
        if (range.next()) {
            buttons.add(new Menu.Button(msg("menu.next"), List.of(), Material.ARROW,
                    Menu.Action.OPEN, "titles:" + (page + 1), true));
        }
        buttons.add(back());
        List<Component> body = singleScrollingList ? List.of()
                : List.of(messages.get("menu.title-page", Messages.ph("page", page + 1)));
        return new Menu.Screen("titles:" + page, msg("menu.titles-title"),
                body, List.of(), buttons, false);
    }

    private Menu.Screen titleDetail(PlayerSession s, String titleId) {
        TitleDefinition title = config.get().titles().byId(titleId);
        Player player = Bukkit.getPlayer(s.uuid());
        if (title == null || player == null) {
            return titleList(s, 0);
        }
        TitleState state = titles.state(player, s, title);
        List<Component> body = new ArrayList<>();
        body.add(Component.text(title.description(), NamedTextColor.GRAY));
        int level = levelResolver.level(s, titleId);
        long targetXp = levelResolver.nextLevelXp(s, titleId);
        body.add(Component.text("My Title Level: ", NamedTextColor.GOLD)
                .append(Component.text(level + " / 3", NamedTextColor.AQUA)));
        body.add(Component.text(targetXp == 0 ? "Maximum level · " + levelResolver.xp(s, titleId) + " title XP"
                : "Title XP: " + levelResolver.xp(s, titleId) + " / " + targetXp, NamedTextColor.AQUA));
        body.add(xpBar(s, titleId));
        body.add(Component.text("Current Ability: " + title.abilityName(), NamedTextColor.GOLD));
        body.add(Component.text(levelResolver.levelSummary(s, titleId), NamedTextColor.WHITE));
        body.add(Component.text(TitleAbilityRegistry.isManual(titleId)
                ? "Use your Ascension Relic to activate." : "Passive: works automatically while equipped.", NamedTextColor.GREEN));
        body.add(Component.text("Next Upgrade", NamedTextColor.GOLD));
        body.add(Component.text(levelResolver.nextLevelSummary(s, titleId), NamedTextColor.GRAY));
        body.add(Component.text("Earn XP through valid gameplay while this title is equipped.", NamedTextColor.GRAY));
        body.add(Component.text("Claim Requirements", NamedTextColor.GOLD));
        for (RequirementProgress requirement : titles.progress(player, s, title)) {
            body.add(messages.get(requirement.met() ? "menu.title-requirement-done" : "menu.title-requirement",
                    Messages.ph("requirement", requirement.requirement().label()),
                    Messages.ph("current", requirement.current()), Messages.ph("required", requirement.required())));
        }
        List<Menu.Button> buttons = new ArrayList<>();
        if (state == TitleState.ELIGIBLE) {
            buttons.add(new Menu.Button(msg("menu.title-claim"), List.of(), Material.LIME_CONCRETE,
                    Menu.Action.TITLE_CLAIM, title.id(), false));
        } else if (state == TitleState.OWNED && !title.id().equals(s.equippedTitle())) {
            buttons.add(new Menu.Button(msg("menu.title-equip"), List.of(), Material.LIME_CONCRETE,
                    Menu.Action.TITLE_EQUIP, title.id(), false));
        } else if (state == TitleState.OWNED) {
            buttons.add(new Menu.Button(msg("menu.title-unequip"), List.of(), Material.RED_CONCRETE,
                    Menu.Action.TITLE_UNEQUIP, title.id(), false));
        }
        int page = Math.max(0, (title.number() - 1) / 10);
        buttons.add(new Menu.Button(msg("menu.back"), List.of(), Material.ARROW, Menu.Action.OPEN,
                usesBedrockForms(s) || page == 0 ? "titles" : "titles:" + page, true));
        return new Menu.Screen("title:" + title.id(), titleName(title),
                body, List.of(), buttons, false);
    }

    private boolean usesBedrockForms(PlayerSession session) {
        return session.bedrock() && config.get().bedrockForms() && platform.hasFloodgate();
    }

    static TitleRange titleRange(int total, int requestedPage, boolean singleScrollingList) {
        if (singleScrollingList) {
            return new TitleRange(0, total, false, false);
        }
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, (total - 1) / 10)));
        int from = Math.min(page * 10, total);
        int to = Math.min(from + 10, total);
        return new TitleRange(from, to, page > 0, to < total);
    }

    record TitleRange(int from, int to, boolean previous, boolean next) {}

    private static Component titleName(TitleDefinition title) {
        TextColor color = TextColor.fromHexString(title.color());
        return Component.text("#" + title.number() + " " + title.name(),
                color == null ? NamedTextColor.WHITE : color);
    }

    private Menu.Screen main(PlayerSession s) {
        List<Component> body = List.of(rankLine(s), questLine(s));
        List<Menu.Button> buttons = List.of(
                open("menu.main-profile", "menu.main-profile-lore", Material.NETHER_STAR, "profile"),
                open("menu.main-take-quest", "menu.main-take-quest-lore", Material.WRITABLE_BOOK, "take_quest"),
                open("menu.main-active-quest", "menu.main-active-quest-lore", Material.COMPASS, "active_quest"),
                open("menu.main-target", "menu.main-target-lore", Material.TARGET, "target"),
                open("menu.main-titles", "menu.main-titles-lore", Material.NAME_TAG, "titles"),
                open("menu.main-abilities", "menu.main-abilities-lore", Material.BLAZE_POWDER, "abilities"),
                new Menu.Button(Component.text("Auction House & Shop", NamedTextColor.GOLD),
                        List.of(Component.text("Buy, sell, collect payments, and exchange currency.")),
                        Material.EMERALD_BLOCK, Menu.Action.OPEN, "market", false),
                open("menu.main-stats", "menu.main-stats-lore", Material.PAPER, "stats"),
                open("menu.main-help", "menu.main-help-lore", Material.BOOK, "help"),
                new Menu.Button(msg("menu.close"), List.of(), Material.BARRIER, Menu.Action.CLOSE, "", true));
        return new Menu.Screen(MAIN, msg("menu.title"), body, List.of(), buttons, false);
    }

    private Menu.Screen profile(PlayerSession s) {
        ConfigSnapshot cfg = config.get();
        RankStage stage = cfg.ladder().get(s.rankIndex());
        List<Component> body = new ArrayList<>();
        body.add(messages.get("menu.profile-current", Messages.ph("rank", Messages.rank(stage))));
        if (s.mastery()) {
            body.add(messages.get("menu.mastery-line", Messages.ph("mastery", cfg.masteryName())));
        } else {
            int next = cfg.ladder().next(s.rankIndex());
            body.add(next >= 0
                    ? messages.get("menu.profile-next", Messages.ph("rank", Messages.rank(cfg.ladder().get(next))))
                    : messages.get("menu.profile-next-mastery", Messages.ph("mastery", cfg.masteryName())));
        }
        RankAbility ability = cfg.abilityFor(stage);
        body.add(messages.get("menu.profile-ability", Messages.ph("ability", ability == null ? "-" : ability.description())));
        body.add(questLine(s));
        return new Menu.Screen("profile", msg("menu.profile-title"), body, List.of(), List.of(back()), false);
    }

    private Menu.Screen takeQuest(PlayerSession s) {
        if (!s.rankUsable(System.currentTimeMillis())) {
            return new Menu.Screen("take_quest", msg("menu.quest-title"), List.of(renewalLine(s)),
                    List.of(), List.of(back()), false);
        }
        ActiveQuest q = s.quest();
        if (q == null) {
            return new Menu.Screen("take_quest", msg("menu.quest-title"), List.of(msg("menu.quest-none")),
                    List.of(), List.of(back()), false);
        }
        RankStage stage = config.get().ladder().get(s.rankIndex());
        List<Component> body = List.of(
                messages.get("menu.quest-stage", Messages.ph("rank", Messages.rank(stage))),
                msg("menu.quest-intro"), msg("menu.quest-retro"));
        List<Menu.Entry> entries = new ArrayList<>();
        for (ObjectiveDefinition o : q.definition().objectives()) {
            List<Component> lore = new ArrayList<>();
            lore.add(messages.get("menu.quest-need", Messages.ph("required", o.amount())));
            if (o.unique() > 0) {
                lore.add(messages.get("menu.quest-need-unique", Messages.ph("unique", o.unique())));
            }
            if (o.submission()) {
                lore.add(msg("menu.quest-submission"));
            }
            entries.add(new Menu.Entry(Component.text(o.description()), lore, iconFor(o)));
        }
        List<Menu.Button> buttons = new ArrayList<>();
        if (q.state() == QuestState.NOT_ACCEPTED) {
            buttons.add(new Menu.Button(msg("menu.quest-accept"), List.of(msg("menu.quest-accept-lore")),
                    Material.LIME_CONCRETE, Menu.Action.ACCEPT_QUEST, "", false));
        } else {
            entries.add(new Menu.Entry(msg("menu.quest-accepted"), List.of(), Material.CLOCK));
        }
        buttons.add(back());
        return new Menu.Screen("take_quest", msg("menu.quest-title"), body, entries, buttons, false);
    }

    private Menu.Screen confirmAccept(PlayerSession s) {
        RankStage stage = config.get().ladder().get(s.rankIndex());
        List<Component> body = List.of(messages.get("menu.confirm-body", Messages.ph("rank", Messages.rank(stage))));
        List<Menu.Button> buttons = List.of(
                new Menu.Button(msg("menu.confirm"), List.of(), Material.LIME_CONCRETE, Menu.Action.CONFIRM_ACCEPT,
                        Integer.toString(s.rankIndex()), false),
                new Menu.Button(msg("menu.cancel"), List.of(), Material.RED_CONCRETE, Menu.Action.OPEN, "take_quest", false));
        return new Menu.Screen("confirm_accept", msg("menu.confirm-title"), body, List.of(), buttons, true);
    }

    private Menu.Screen activeQuest(PlayerSession s) {
        if (!s.rankUsable(System.currentTimeMillis())) {
            return new Menu.Screen("active_quest", msg("menu.active-title"), List.of(renewalLine(s)),
                    List.of(), List.of(back()), false);
        }
        ActiveQuest q = s.quest();
        if (q == null) {
            return new Menu.Screen("active_quest", msg("menu.active-title"), List.of(msg("menu.quest-none")),
                    List.of(), List.of(back()), false);
        }
        RankStage stage = config.get().ladder().get(s.rankIndex());
        List<Component> body = new ArrayList<>();
        body.add(messages.get("menu.quest-stage", Messages.ph("rank", Messages.rank(stage))));
        body.add(questLine(s));
        List<Menu.Button> buttons = new ArrayList<>();
        List<Menu.Entry> entries = new ArrayList<>();
        if (q.state() == QuestState.NOT_ACCEPTED) {
            body.add(msg("menu.active-none"));
            buttons.add(new Menu.Button(msg("menu.active-open-take"), List.of(), Material.WRITABLE_BOOK,
                    Menu.Action.OPEN, "take_quest", false));
        } else {
            for (ObjectiveDefinition o : q.definition().objectives()) {
                ObjectiveStatus st = q.status(o);
                List<Component> lore = new ArrayList<>();
                lore.add(messages.get("menu.active-progress", Messages.ph("current", st.current()),
                        Messages.ph("required", o.amount())));
                lore.add(ProgressBar.of(st.current(), o.amount()));
                if (o.unique() > 0) {
                    lore.add(messages.get("menu.active-progress-unique", Messages.ph("marks", st.marks()),
                            Messages.ph("unique", o.unique())));
                }
                if (o.submission()) {
                    lore.add(msg("menu.quest-submission"));
                    if (!st.complete()) {
                        buttons.add(new Menu.Button(
                                messages.get("menu.submit", Messages.ph("description", o.description())),
                                List.of(msg("menu.submit-lore")), Material.HOPPER,
                                Menu.Action.SUBMIT_ITEM, o.id(), false));
                    }
                }
                Component label = messages.get(st.complete() ? "menu.active-done" : "menu.active-todo",
                        Messages.ph("description", o.description()));
                entries.add(new Menu.Entry(label, lore, st.complete() ? Material.LIME_DYE : Material.GRAY_DYE));
            }
        }
        buttons.add(back());
        return new Menu.Screen("active_quest", msg("menu.active-title"), body, entries, buttons, false);
    }

    private Menu.Screen abilities(PlayerSession s) {
        List<Component> body = new ArrayList<>();
        List<RankAbility> active = s.abilities().active();
        if (active.isEmpty()) {
            body.add(msg("menu.abilities-none"));
        }
        long now = System.currentTimeMillis();
        for (RankAbility pkg : active) {
            body.add(messages.get("menu.abilities-rank", Messages.ph("ability", pkg.description())));
            List<RankAbility.Component> comps = pkg.components();
            for (int i = 0; i < comps.size(); i++) {
                if (comps.get(i) instanceof RankAbility.TriggeredEffect) {
                    String key = "rank." + pkg.id() + "." + i;
                    long left = s.cooldowns().remainingMillis(key, now);
                    body.add(left <= 0
                            ? messages.get("menu.abilities-ready", Messages.ph("n", i + 1))
                            : messages.get("menu.abilities-cooling", Messages.ph("n", i + 1),
                            Messages.ph("seconds", (left + 999) / 1000)));
                }
            }
        }
        return new Menu.Screen("abilities", msg("menu.abilities-title"), body, List.of(), List.of(back()), false);
    }

    private Menu.Screen stats(PlayerSession s) {
        Map<String, Long> stats = s.statsView();
        List<Menu.Entry> entries = List.of(
                stat("menu.stats-quests", s.rankIndex() + (s.mastery() ? 1 : 0), Material.WRITABLE_BOOK),
                stat("menu.stats-levels", stats.getOrDefault("levels", 0L), Material.EXPERIENCE_BOTTLE),
                stat("menu.stats-hostile", stats.getOrDefault("kills.hostile", 0L), Material.ZOMBIE_HEAD),
                stat("menu.stats-kills", stats.getOrDefault("kills", 0L), Material.DIAMOND_SWORD),
                stat("menu.stats-mined", stats.getOrDefault("mined", 0L), Material.IRON_PICKAXE),
                stat("menu.stats-smelted", stats.getOrDefault("smelted", 0L), Material.FURNACE));
        return new Menu.Screen("stats", msg("menu.stats-title"), List.of(), entries, List.of(back()), false);
    }

    private Menu.Entry stat(String nameKey, long value, Material icon) {
        return new Menu.Entry(messages.get("menu.stats-line", Messages.ph("name", Messages.plain(msg(nameKey))),
                Messages.ph("value", value)), List.of(), icon);
    }

    /** One book whose tooltip reads as a page, instead of seven separate lines to hover. */
    private Menu.Screen help() {
        List<Component> steps = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            steps.add(msg("menu.help-" + i));
        }
        return new Menu.Screen("help", msg("menu.help-title"), List.of(),
                List.of(new Menu.Entry(msg("menu.help-title"), steps, Material.KNOWLEDGE_BOOK)),
                List.of(back()), false);
    }

    private Menu.Screen later(PlayerSession s, String titleKey) {
        return new Menu.Screen("later", msg(titleKey), List.of(msg("menu.later")), List.of(), List.of(back()), false);
    }

    // ------------------------------------------------------------------ helpers

    private Component rankLine(PlayerSession s) {
        ConfigSnapshot cfg = config.get();
        Component line = messages.get("menu.rank-line", Messages.ph("rank", Messages.rank(cfg.ladder().get(s.rankIndex()))));
        if (!s.rankUsable(System.currentTimeMillis())) line = Component.text("Held rank (inactive): ", NamedTextColor.RED).append(Messages.rank(cfg.ladder().get(s.rankIndex())));
        line = !s.rankUsable(System.currentTimeMillis()) ? line.append(Component.text("  ")).append(renewalLine(s)) : line;
        return s.mastery()
                ? line.append(Component.text("  ")).append(messages.get("menu.mastery-line", Messages.ph("mastery", cfg.masteryName())))
                : line;
    }

    private Component renewalLine(PlayerSession s) {
        var r = s.renewal();
        if (r == null) return msg("menu.renewal-pending");
        if ("HUNT".equals(r.branch())) {
            return messages.get("menu.renewal-hunt", Messages.ph("target", r.target() == null ? "waiting for target" : r.target()),
                    Messages.ph("current", r.progress()), Messages.ph("required", r.required()));
        }
        return messages.get("menu.renewal-pve", Messages.ph("objective", r.objectiveType().toLowerCase().replace('_', ' ')),
                Messages.ph("current", r.progress()), Messages.ph("required", r.required()));
    }

    private Component questLine(PlayerSession s) {
        return messages.get("menu.quest-line", Messages.ph("state", Messages.plain(msg(stateKey(s)))));
    }

    private static String stateKey(PlayerSession s) {
        if (s.quest() == null) {
            return "menu.state-mastered";
        }
        return switch (s.quest().state()) {
            case NOT_ACCEPTED -> "menu.state-not-accepted";
            case ACTIVE -> "menu.state-active";
            case COMPLETED -> "menu.state-completed";
            case REWARDED -> "menu.state-rewarded";
        };
    }

    private Menu.Button open(String labelKey, String loreKey, Material icon, String target) {
        return new Menu.Button(msg(labelKey), List.of(msg(loreKey)), icon, Menu.Action.OPEN, target, false);
    }

    private Menu.Button back() {
        return new Menu.Button(msg("menu.back"), List.of(), Material.ARROW, Menu.Action.OPEN, MAIN, true);
    }

    private Component msg(String key) {
        return messages.get(key);
    }

    private Component xpBar(PlayerSession s, String titleId) {
        long target = levelResolver.nextLevelXp(s, titleId);
        return target == 0 ? ProgressBar.of(1, 1) : ProgressBar.of(levelResolver.xp(s, titleId), target);
    }

    private static NamedTextColor stateColor(String state) {
        return switch (state) {
            case "equipped", "available" -> NamedTextColor.GREEN;
            case "wearable" -> NamedTextColor.AQUA;
            case "claiming" -> NamedTextColor.YELLOW;
            case "claimed" -> NamedTextColor.RED;
            default -> NamedTextColor.GRAY;
        };
    }

    /** Equipped titles glow as a nether star, claimable ones as experience, others keep the name tag. */
    private static Material titleIcon(TitleState titleState, String state) {
        if (state.equals("equipped")) return Material.NETHER_STAR;
        return titleState == TitleState.ELIGIBLE ? Material.EXPERIENCE_BOTTLE : Material.NAME_TAG;
    }

    private static Material iconFor(ObjectiveDefinition o) {
        return switch (o.type()) {
            case GAIN_LEVELS -> Material.EXPERIENCE_BOTTLE;
            case KILL_MOBS, KILL_DISTINCT_MOBS -> Material.IRON_SWORD;
            case MINE_BLOCKS -> Material.IRON_PICKAXE;
            case SMELT_ITEMS -> Material.FURNACE;
            case OBTAIN_ITEMS -> Material.CHEST;
            case SUBMIT_ITEMS -> Material.HOPPER;
            case PLAYER_KILLS -> Material.DIAMOND_SWORD;
            case HUNTS -> Material.TARGET;
            case RAIDS -> Material.CROSSBOW;
            case DEFEAT_BOSS -> Material.WITHER_SKELETON_SKULL;
        };
    }
}
