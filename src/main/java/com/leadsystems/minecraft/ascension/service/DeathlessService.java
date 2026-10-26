package com.leadsystems.minecraft.ascension.service;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionHook;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Active-play calendar streak tracking and the atomic Undying auto-award trigger. */
public final class DeathlessService implements Listener, SessionHook {

    private final SessionManager sessions;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final MainThread main;
    private final Supplier<ConfigSnapshot> config;
    private final TitleService titles;
    private final Messages messages;
    private final Map<UUID, Long> lastActivity = new HashMap<>();

    public DeathlessService(SessionManager sessions, StorageExecutor storage, PlayerRepository repo,
                            MainThread main, Supplier<ConfigSnapshot> config, TitleService titles,
                            Messages messages) {
        this.sessions = sessions;
        this.storage = storage;
        this.repo = repo;
        this.main = main;
        this.config = config;
        this.titles = titles;
        this.messages = messages;
    }

    @Override
    public void onLoaded(Player player, PlayerSession session, boolean reload) {
        lastActivity.put(session.uuid(), System.currentTimeMillis());
    }

    @Override
    public void onUnload(Player player, PlayerSession session) {
        lastActivity.remove(session.uuid());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock()) {
            mark(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        LocalDate day = LocalDate.now(config.get().settings().zone());
        storage.submit("deathless.reset", c -> {
            repo.resetDeathlessStreak(c, player.getUniqueId(), day, System.currentTimeMillis());
            return null;
        });
        PlayerSession session = sessions.get(player);
        if (session != null) {
            session.setCommittedStat("deathless.current_days", 0);
        }
    }

    /** One global scheduler call per minute; movement itself never touches storage. */
    public void tickMinute() {
        ConfigSnapshot snapshot = config.get();
        long now = System.currentTimeMillis();
        long activeWindow = TimeUnit.MINUTES.toMillis(snapshot.settings().activityTimeoutMinutes());
        int required = snapshot.settings().deathlessMinutesPerDay() * 60;
        ZoneId zone = snapshot.settings().zone();
        LocalDate day = LocalDate.now(zone);
        long dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        for (PlayerSession session : sessions.all()) {
            if (!session.usable() || now - lastActivity.getOrDefault(session.uuid(), 0L) > activeWindow) {
                continue;
            }
            main.onComplete(storage.submit("deathless.activity",
                    c -> repo.addDailyActivity(c, session.uuid(), day, 60, required, dayStart, dayEnd)),
                    (update, error) -> {
                if (error != null || update == null || sessions.get(session.uuid()) != session) {
                    return;
                }
                session.setCommittedStat("deathless.current_days", update.currentDays());
                session.setCommittedStat("deathless.best_days", update.bestDays());
                if (update.newlyQualified()) {
                    Player player = org.bukkit.Bukkit.getPlayer(session.uuid());
                    if (player != null) {
                        messages.send(player, "deathless-progress", Messages.ph("days", update.currentDays()));
                        if (update.currentDays() >= 25) {
                            titles.autoAward(player, "undying");
                        }
                    }
                }
            });
        }
    }

    private void mark(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
    }
}
