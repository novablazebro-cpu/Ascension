package com.leadsystems.minecraft.ascension.config;

/** Validates Minecraft names so config errors are caught at load/reload time. */
public interface NameResolver {
    boolean isMaterial(String upperName);
    /** Returns whether a configured material can be held as an item. */
    default boolean isItemMaterial(String upperName) { return isMaterial(upperName); }
    boolean isEntityType(String upperName);
    boolean isEffect(String lowerKey);
}
