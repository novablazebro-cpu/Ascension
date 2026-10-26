package com.leadsystems.minecraft.ascension.domain.title;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * All title definitions with lookup helpers. Immutable.
 */
public final class TitleCatalog {

    private final Map<String, TitleDefinition> byId = new LinkedHashMap<>();
    private final Map<Integer, TitleDefinition> byNumber = new HashMap<>();
    private final Map<String, List<TitleDefinition>> byStat = new HashMap<>();
    private final List<TitleDefinition> ordered;

    public TitleCatalog(List<TitleDefinition> titles) {
        List<TitleDefinition> sorted = new ArrayList<>(titles);
        sorted.sort(Comparator.comparingInt(TitleDefinition::number));
        this.ordered = List.copyOf(sorted);
        for (TitleDefinition t : ordered) {
            byId.put(t.id().toLowerCase(Locale.ROOT), t);
            byNumber.put(t.number(), t);
            for (Requirement r : t.requirements()) {
                if (r instanceof Requirement.StatAtLeast s) {
                    for (String k : s.keys()) {
                        List<TitleDefinition> users = byStat.computeIfAbsent(k, x -> new ArrayList<>());
                        if (!users.contains(t)) {
                            users.add(t);
                        }
                    }
                }
            }
        }
    }

    /** By id (case-insensitive), {@code #n} or {@code n}; null if unknown. */
    public TitleDefinition resolve(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String r = ref.trim();
        String digits = r.startsWith("#") ? r.substring(1) : r;
        if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit)) {
            try {
                return byNumber.get(Integer.parseInt(digits));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String normalized = r.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (normalized.equals("thunder_god") || normalized.equals("thundergod")) normalized = "stormbringer";
        return byId.get(normalized);
    }

    public TitleDefinition byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    /** Hardest (#1) first. */
    public List<TitleDefinition> ordered() {
        return ordered;
    }

    /** Titles with a statistic requirement on {@code key} (re-evaluated when it changes). */
    public List<TitleDefinition> usingStat(String key) {
        return byStat.getOrDefault(key, List.of());
    }

    public int size() {
        return ordered.size();
    }
}
