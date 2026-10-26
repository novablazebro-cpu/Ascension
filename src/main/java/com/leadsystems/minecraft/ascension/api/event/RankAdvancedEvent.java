package com.leadsystems.minecraft.ascension.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread after a quest reward transaction committed: the player advanced a stage
 * or, on the last stage, achieved mastery. Not cancellable - the change is already durable.
 */
public final class RankAdvancedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int fromIndex;
    private final int toIndex;
    private final boolean mastery;

    public RankAdvancedEvent(@NotNull Player player, int fromIndex, int toIndex, boolean mastery) {
        super(player);
        this.fromIndex = fromIndex;
        this.toIndex = toIndex;
        this.mastery = mastery;
    }

    public int getFromIndex() {
        return fromIndex;
    }

    /** New stage index (equal to {@link #getFromIndex()} when {@link #isMastery()}). */
    public int getToIndex() {
        return toIndex;
    }

    /** True if this completion granted mastery instead of a new stage. */
    public boolean isMastery() {
        return mastery;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
