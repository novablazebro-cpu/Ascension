package com.leadsystems.minecraft.ascension.integration;

import org.bukkit.Bukkit;
import org.geysermc.cumulus.form.Form;

import java.util.UUID;

/**
 * Detects Bedrock (Geyser/Floodgate) players. Adapted from the LeadSystems
 * {@code core/platform/PlatformDetector}. Floodgate classes are only touched through
 * {@link FloodgateBridge} and only when Floodgate is actually installed, so the plugin runs
 * without it.
 */
public final class PlatformDetector {

    private final boolean floodgate;

    public PlatformDetector() {
        this.floodgate = Bukkit.getPluginManager().getPlugin("floodgate") != null
                && classExists("org.geysermc.floodgate.api.FloodgateApi");
    }

    public boolean hasFloodgate() {
        return floodgate;
    }

    /**
     * True for Bedrock players. Uses the Floodgate API when present; otherwise falls back to
     * Floodgate's synthetic UUID scheme ({@code new UUID(0, xuid)}: most significant bits are 0,
     * which never happens for Java UUIDs - see training note 03).
     */
    public boolean isBedrock(UUID uuid) {
        if (floodgate) {
            try {
                return FloodgateBridge.isFloodgatePlayer(uuid);
            } catch (Throwable ignored) {
                // fall through to the UUID heuristic
            }
        }
        return uuid.getMostSignificantBits() == 0L;
    }

    /** Sends a Cumulus form; false if Floodgate is missing or refused the form. */
    public boolean sendForm(UUID uuid, Form form) {
        if (!floodgate) {
            return false;
        }
        try {
            return FloodgateBridge.sendForm(uuid, form);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
