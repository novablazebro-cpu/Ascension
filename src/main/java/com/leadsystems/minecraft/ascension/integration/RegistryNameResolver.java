package com.leadsystems.minecraft.ascension.integration;

import com.leadsystems.minecraft.ascension.config.NameResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;

import java.util.Locale;

/**
 * Validates config names against the live server registries.
 */
public final class RegistryNameResolver implements NameResolver {

    @Override
    public boolean isMaterial(String upperName) {
        return Material.matchMaterial(upperName) != null;
    }

    @Override
    public boolean isItemMaterial(String upperName) {
        Material material = Material.matchMaterial(upperName);
        return material != null && material.isItem() && !material.isAir();
    }

    @Override
    public boolean isEntityType(String upperName) {
        return entityType(upperName) != null;
    }

    @Override
    public boolean isEffect(String lowerKey) {
        return Registry.EFFECT.get(NamespacedKey.minecraft(lowerKey.toLowerCase(Locale.ROOT))) != null;
    }

    /** Entity type by upper-case enum name, or null. */
    public static EntityType entityType(String upperName) {
        try {
            return EntityType.valueOf(upperName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
