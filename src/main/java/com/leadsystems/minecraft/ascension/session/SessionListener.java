package com.leadsystems.minecraft.ascension.session;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Wires the Bukkit connection lifecycle to the {@link SessionManager}.
 */
public final class SessionListener implements Listener {

    private final SessionManager sessions;

    public SessionListener(SessionManager sessions) {
        this.sessions = sessions;
    }

    /** Async thread: blocking storage access is allowed here. Runs last, only for allowed logins. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            sessions.preLogin(e.getUniqueId(), e.getName(), e.getAddress());
        }
    }

    /** Early, so other plugins' join handlers already see a session. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        sessions.join(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        sessions.quit(e.getPlayer());
    }
}
