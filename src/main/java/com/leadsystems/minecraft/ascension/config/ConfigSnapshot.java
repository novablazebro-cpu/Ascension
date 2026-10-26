package com.leadsystems.minecraft.ascension.config;

import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import com.leadsystems.minecraft.ascension.domain.quest.QuestDefinition;
import com.leadsystems.minecraft.ascension.domain.rank.RankLadder;
import com.leadsystems.minecraft.ascension.domain.rank.RankStage;
import com.leadsystems.minecraft.ascension.domain.title.TitleCatalog;
import com.leadsystems.minecraft.ascension.domain.title.TitleLevels;

import java.util.Map;

/**
 * One fully validated configuration. Immutable; {@code /titles reload} swaps in a new snapshot
 * atomically only if the new files validate, so running code always sees a consistent config.
 *
 * @param settings               config.yml
 * @param ladder                 ranks.yml stages
 * @param quests                 quests.yml, keyed by rank index
 * @param rankAbilities          abilities.yml rank packages, keyed by id
 * @param stackPreviousAbilities apply lower stages' packages too
 * @param announceRankUps        broadcast rank-ups
 * @param masteryName            display name for mastery
 * @param messages               raw MiniMessage templates from messages.yml
 * @param storage                storage.yml (restart required to change)
 * @param bedrockForms           use Floodgate forms for Bedrock players
 * @param testingUnlockAll       allow beta testers to select any rank and equip any title
 */
public record ConfigSnapshot(
        Settings settings,
        RankLadder ladder,
        Map<Integer, QuestDefinition> quests,
        Map<String, RankAbility> rankAbilities,
        TitleCatalog titles,
        int titleOwnershipLimit,
        boolean stackPreviousAbilities,
        boolean announceRankUps,
        String masteryName,
        Map<String, String> messages,
        StorageSettings storage,
        boolean bedrockForms,
        boolean testingUnlockAll,
        TitleLevels titleLevels
) {
    public ConfigSnapshot {
        quests = Map.copyOf(quests);
        rankAbilities = Map.copyOf(rankAbilities);
        messages = Map.copyOf(messages);
        java.util.Objects.requireNonNull(titleLevels, "titleLevels");
    }

    public ConfigSnapshot(Settings settings, RankLadder ladder, Map<Integer, QuestDefinition> quests,
                          Map<String, RankAbility> rankAbilities, TitleCatalog titles, int titleOwnershipLimit,
                          boolean stackPreviousAbilities, boolean announceRankUps, String masteryName,
                          Map<String, String> messages, StorageSettings storage, boolean bedrockForms,
                          boolean testingUnlockAll) {
        this(settings, ladder, quests, rankAbilities, titles, titleOwnershipLimit, stackPreviousAbilities,
                announceRankUps, masteryName, messages, storage, bedrockForms, testingUnlockAll, TitleLevels.defaults());
    }

    /** The quest for a rank index (always present for valid ladders). */
    public QuestDefinition questFor(int rankIndex) {
        return quests.get(rankIndex);
    }

    /** The ability package of a stage. */
    public RankAbility abilityFor(RankStage stage) {
        return rankAbilities.get(stage.abilityId());
    }

    /**
     * storage.yml.
     *
     * @param type       {@code sqlite}
     * @param sqliteFile database file relative to the plugin folder
     * @param backupDir  backup directory relative to the plugin folder
     */
    public record StorageSettings(String type, String sqliteFile, String backupDir) {}
}
