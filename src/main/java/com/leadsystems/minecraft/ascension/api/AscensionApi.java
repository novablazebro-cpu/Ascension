package com.leadsystems.minecraft.ascension.api;

import java.util.UUID;

/**
 * Read-only API for other plugins. Obtain it with
 * {@code Bukkit.getServicesManager().load(AscensionApi.class)}. All methods must be called on the
 * main server thread and only report online players (offline data lives in the database).
 */
public interface AscensionApi {

    /** Ladder index of the player's current stage (0 = first), or -1 if not online/loaded. */
    int rankIndex(UUID player);

    /** Display name of the player's current stage (e.g. "Gold II"), or null if not online/loaded. */
    String rankName(UUID player);

    /** True if the player completed the last stage's quest. */
    boolean hasMastery(UUID player);

    /** Number of stages on the ladder. */
    int stageCount();
}
