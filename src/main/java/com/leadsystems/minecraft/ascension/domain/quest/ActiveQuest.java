package com.leadsystems.minecraft.ascension.domain.quest;

import com.leadsystems.minecraft.ascension.domain.progress.Fact;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mutable progress of the player's current rank quest. Main-thread only.
 * <p>
 * State transitions are guarded here so a duplicated event can never start a second completion:
 * {@link #accept(long)} only from NOT_ACCEPTED, {@link #beginCompletion()} only from ACTIVE when every
 * objective is met, {@link #revertCompletion()} only from COMPLETED (failed reward transaction).
 * Progress only counts while ACTIVE, so nothing done before accepting is retroactive.
 */
public final class ActiveQuest {

    private final QuestDefinition definition;
    private QuestState state;
    private long acceptedAt;
    private final Map<String, Long> amounts = new HashMap<>();
    private final Map<String, Set<String>> marks = new HashMap<>();
    private final Set<String> dirtyAmounts = new HashSet<>();
    private final Map<String, Set<String>> dirtyMarks = new HashMap<>();

    private ActiveQuest(QuestDefinition definition, QuestState state, long acceptedAt) {
        this.definition = definition;
        this.state = state;
        this.acceptedAt = acceptedAt;
    }

    /** A quest with no progress. */
    public static ActiveQuest fresh(QuestDefinition definition, QuestState state, long acceptedAt) {
        return new ActiveQuest(definition, state, acceptedAt);
    }

    /** Restores a quest loaded from storage. Unknown objective ids (removed from config) are ignored. */
    public static ActiveQuest restore(QuestDefinition definition, QuestState state, long acceptedAt,
                                      Map<String, Long> storedAmounts, Map<String, Set<String>> storedMarks) {
        ActiveQuest q = new ActiveQuest(definition, state, acceptedAt);
        for (ObjectiveDefinition o : definition.objectives()) {
            Set<String> m = storedMarks.get(o.id());
            if (m != null) {
                q.marks.put(o.id(), new LinkedHashSet<>(m));
            }
            Long a = storedAmounts.get(o.id());
            if (a != null) {
                q.amounts.put(o.id(), Math.min(a, o.amount()));
            }
            if (o.type() == ObjectiveType.KILL_DISTINCT_MOBS) {
                q.amounts.put(o.id(), Math.min(q.markCount(o.id()), o.amount()));
            }
        }
        return q;
    }

    public QuestDefinition definition() {
        return definition;
    }

    public QuestState state() {
        return state;
    }

    public long acceptedAt() {
        return acceptedAt;
    }

    /** NOT_ACCEPTED -> ACTIVE. Called only after the accept transaction committed. */
    public boolean accept(long now) {
        if (state != QuestState.NOT_ACCEPTED) {
            return false;
        }
        state = QuestState.ACTIVE;
        acceptedAt = now;
        return true;
    }

    /** Applies a fact to every matching objective. Returns true if any progress changed. */
    public boolean apply(Fact fact) {
        if (state != QuestState.ACTIVE) {
            return false;
        }
        boolean changed = false;
        for (ObjectiveDefinition o : definition.objectives()) {
            ObjectiveMatcher.Contribution c = ObjectiveMatcher.match(o, fact);
            if (c == null) {
                continue;
            }
            if (c.mark() != null && addMark(o, c.mark())) {
                changed = true;
            }
            long before = amount(o.id());
            long after = o.type() == ObjectiveType.KILL_DISTINCT_MOBS
                    ? Math.min(markCount(o.id()), o.amount())
                    : Math.min(before + c.delta(), o.amount());
            if (after != before) {
                amounts.put(o.id(), after);
                dirtyAmounts.add(o.id());
                changed = true;
            }
        }
        return changed;
    }

    private boolean addMark(ObjectiveDefinition o, String mark) {
        int cap = markCap(o);
        if (cap <= 0) {
            return false;
        }
        Set<String> set = marks.computeIfAbsent(o.id(), k -> new LinkedHashSet<>());
        // Marks beyond what the objective needs are never stored (bounds storage per quest).
        if (set.size() >= cap || !set.add(mark)) {
            return false;
        }
        dirtyMarks.computeIfAbsent(o.id(), k -> new LinkedHashSet<>()).add(mark);
        return true;
    }

    private static int markCap(ObjectiveDefinition o) {
        if (o.type() == ObjectiveType.KILL_DISTINCT_MOBS) {
            return (int) Math.min(Integer.MAX_VALUE, o.amount());
        }
        return o.unique();
    }

    private int markCount(String objectiveId) {
        Set<String> set = marks.get(objectiveId);
        return set == null ? 0 : set.size();
    }

    /** Current amount for an objective (0 if none). */
    public long amount(String objectiveId) {
        return amounts.getOrDefault(objectiveId, 0L);
    }

    /** Progress view for one objective. */
    public ObjectiveStatus status(ObjectiveDefinition objective) {
        long current = amount(objective.id());
        int markCount = markCount(objective.id());
        boolean complete = current >= objective.amount()
                && (objective.unique() <= 0 || markCount >= objective.unique());
        return new ObjectiveStatus(objective, current, markCount, complete);
    }

    /** True when every objective is complete. */
    public boolean allComplete() {
        for (ObjectiveDefinition o : definition.objectives()) {
            if (!status(o).complete()) {
                return false;
            }
        }
        return true;
    }

    /** ACTIVE -> COMPLETED, only when all objectives are met. Returns false if nothing changed. */
    public boolean beginCompletion() {
        if (state != QuestState.ACTIVE || !allComplete()) {
            return false;
        }
        state = QuestState.COMPLETED;
        return true;
    }

    /** COMPLETED -> ACTIVE after a failed reward transaction, so completion can be retried. */
    public void revertCompletion() {
        if (state == QuestState.COMPLETED) {
            state = QuestState.ACTIVE;
        }
    }

    /** Returns changed progress since the last drain and clears the dirty set. */
    public QuestDelta drainDirty() {
        Map<String, Long> changedAmounts = new HashMap<>();
        for (String id : dirtyAmounts) {
            changedAmounts.put(id, amount(id));
        }
        QuestDelta delta = new QuestDelta(changedAmounts, dirtyMarks);
        dirtyAmounts.clear();
        dirtyMarks.clear();
        return delta;
    }

    /** Re-queues a delta whose flush failed. Amounts are re-read at next drain (they only grow). */
    public void restoreDirty(QuestDelta delta) {
        dirtyAmounts.addAll(delta.amounts().keySet());
        delta.newMarks().forEach((id, set) ->
                dirtyMarks.computeIfAbsent(id, k -> new LinkedHashSet<>()).addAll(set));
    }

    /**
     * Admin override: sets one objective to complete. Missing unique marks are filled with
     * {@code admin-N} placeholders so the unique-victim requirement is also satisfied.
     */
    public boolean forceComplete(String objectiveId) {
        ObjectiveDefinition o = definition.objective(objectiveId);
        if (o == null) {
            return false;
        }
        int need = markCap(o);
        int n = 1;
        while (markCount(o.id()) < need) {
            addMark(o, "admin-" + n++);
        }
        amounts.put(o.id(), o.amount());
        dirtyAmounts.add(o.id());
        return true;
    }

    /**
     * Merges a delta (e.g. an unsaved batch from a failed quit-save) using max semantics and marks
     * everything in it dirty, so it is written on the next flush. Never lowers progress.
     */
    public void mergeDelta(QuestDelta delta) {
        delta.newMarks().forEach((id, set) -> {
            ObjectiveDefinition o = definition.objective(id);
            if (o != null) {
                for (String mark : set) {
                    addMark(o, mark);
                }
            }
        });
        delta.amounts().forEach((id, value) -> {
            ObjectiveDefinition o = definition.objective(id);
            if (o == null) {
                return;
            }
            long merged = o.type() == ObjectiveType.KILL_DISTINCT_MOBS
                    ? Math.min(markCount(id), o.amount())
                    : Math.min(Math.max(amount(id), value), o.amount());
            amounts.put(id, merged);
            dirtyAmounts.add(id);
        });
    }

    /**
     * The same state and progress under a new definition (config reload). Amounts are clamped to
     * the new requirements; unsaved changes stay dirty.
     */
    public ActiveQuest rebind(QuestDefinition newDefinition) {
        ActiveQuest q = restore(newDefinition, state, acceptedAt, amounts, marks);
        for (String id : dirtyAmounts) {
            if (newDefinition.objective(id) != null) {
                q.dirtyAmounts.add(id);
            }
        }
        dirtyMarks.forEach((id, set) -> {
            if (newDefinition.objective(id) != null) {
                q.dirtyMarks.computeIfAbsent(id, k -> new LinkedHashSet<>()).addAll(set);
            }
        });
        return q;
    }
}
