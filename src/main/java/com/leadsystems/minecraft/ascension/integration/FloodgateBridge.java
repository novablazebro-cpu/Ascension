package com.leadsystems.minecraft.ascension.integration;

import org.geysermc.cumulus.form.Form;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;

/**
 * The only class that references the Floodgate API. Loaded lazily, and only called when
 * {@link PlatformDetector#hasFloodgate()} is true, so a server without Floodgate never loads it.
 */
final class FloodgateBridge {

    private FloodgateBridge() {}

    static boolean isFloodgatePlayer(UUID uuid) {
        return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
    }

    static boolean sendForm(UUID uuid, Form form) {
        return FloodgateApi.getInstance().sendForm(uuid, form);
    }
}
