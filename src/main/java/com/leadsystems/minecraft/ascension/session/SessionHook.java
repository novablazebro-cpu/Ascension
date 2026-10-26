package com.leadsystems.minecraft.ascension.session;

import org.bukkit.entity.Player;

/**
 * Lifecycle callbacks for services that attach behaviour to a session (book, abilities, notices).
 * All callbacks run on the main thread.
 */
public interface SessionHook {

    /**
     * The session has data (first load after join, or a reload after an admin change).
     *
     * @param reload true when re-applied after an admin mutation or config reload
     */
    void onLoaded(Player player, PlayerSession session, boolean reload);

    /** The player is leaving or the plugin is disabling; undo anything applied to the player. */
    default void onUnload(Player player, PlayerSession session) {}
}
