package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Revalidates persisted testing selections when loading or using a title. */
final class TitleAccess {
    private TitleAccess() { }

    static boolean canUse(PlayerSession session, ConfigSnapshot config, String titleId) {
        if (session == null || !session.usable() || titleId == null
                || !titleId.equals(session.equippedTitle())) return false;
        if (session.ownedTitles().contains(titleId)) return true;
        if (!config.testingUnlockAll() || session.uuid() == null) return false;
        Player player = Bukkit.getPlayer(session.uuid());
        return player != null && player.hasPermission("ascension.admin");
    }
}
