package com.leadsystems.minecraft.ascension.domain.rank;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The ordered rank ladder (Bronze I ... Platinum III by default). Immutable.
 * Completing the last stage's quest grants mastery instead of a further stage.
 */
public final class RankLadder {

    private final List<RankStage> stages;

    /**
     * @param stages stages in order; {@code stages.get(i).index()} must equal {@code i}
     * @throws IllegalArgumentException if empty or indices do not match positions
     */
    public RankLadder(List<RankStage> stages) {
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("rank ladder needs at least one stage");
        }
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).index() != i) {
                throw new IllegalArgumentException("stage " + stages.get(i).id() + " has index "
                        + stages.get(i).index() + " but is at position " + i);
            }
        }
        this.stages = List.copyOf(stages);
    }

    public int size() {
        return stages.size();
    }

    public RankStage get(int index) {
        return stages.get(index);
    }

    public List<RankStage> stages() {
        return stages;
    }

    /** Stage with this config id, or null. */
    public RankStage byId(String id) {
        for (RankStage s : stages) {
            if (s.id().equalsIgnoreCase(id)) {
                return s;
            }
        }
        return null;
    }

    /** Stage by tier name (case-insensitive) and stage number, or null. */
    public RankStage find(String tier, int stage) {
        for (RankStage s : stages) {
            if (s.tier().equalsIgnoreCase(tier) && s.stage() == stage) {
                return s;
            }
        }
        return null;
    }

    /** Next stage index, or -1 at the top of the ladder. */
    public int next(int index) {
        return index + 1 < stages.size() ? index + 1 : -1;
    }

    public boolean isLast(int index) {
        return index == stages.size() - 1;
    }

    /** Distinct tier names in ladder order (for tab completion). */
    public List<String> tiers() {
        List<String> out = new ArrayList<>();
        for (RankStage s : stages) {
            String t = s.tier().toLowerCase(Locale.ROOT);
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }
}
