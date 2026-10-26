package com.leadsystems.minecraft.ascension.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread after a rank quest was accepted and the acceptance is saved.
 * Informational (not cancellable): the database already records the acceptance.
 */
public final class QuestAcceptedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int rankIndex;
    private final String stageId;

    public QuestAcceptedEvent(@NotNull Player player, int rankIndex, @NotNull String stageId) {
        super(player);
        this.rankIndex = rankIndex;
        this.stageId = stageId;
    }

    /** Ladder index of the stage whose quest was accepted. */
    public int getRankIndex() {
        return rankIndex;
    }

    /** Stage id from ranks.yml, e.g. {@code gold_2}. */
    public @NotNull String getStageId() {
        return stageId;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
