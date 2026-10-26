package com.leadsystems.minecraft.ascension.domain.title;

/**
 * Pure requirement evaluation.
 * <p>
 * <b>Eligibility</b> considers every requirement except possession (items can be dropped and
 * picked up again, so holding one only means something at the moment of claiming). <b>Claimable</b>
 * additionally requires every possession check to pass on the live inventory.
 */
public final class RequirementEvaluator {

    private RequirementEvaluator() {}

    public static RequirementProgress evaluate(Requirement r, RequirementContext ctx) {
        return switch (r) {
            case Requirement.StatAtLeast s -> {
                long sum = 0;
                for (String k : s.keys()) {
                    sum += ctx.stat(k);
                }
                yield new RequirementProgress(r, Math.min(sum, s.amount()), s.amount(), sum >= s.amount());
            }
            case Requirement.RankAtLeast rk -> {
                boolean met = rk.mastery() ? ctx.mastery() : ctx.rankIndex() >= rk.rankIndex() || ctx.mastery();
                yield new RequirementProgress(r, met ? 1 : 0, 1, met);
            }
            case Requirement.Possess p -> {
                boolean met = Boolean.TRUE.equals(ctx.possesses(p.material(), p.enchantment()));
                yield new RequirementProgress(r, met ? 1 : 0, 1, met);
            }
            case Requirement.StreakAtLeast st -> new RequirementProgress(r,
                    Math.min(ctx.bestStreakDays(), st.days()), st.days(), ctx.bestStreakDays() >= st.days());
        };
    }

    /** Every non-possession requirement met. */
    public static boolean eligible(TitleDefinition t, RequirementContext ctx) {
        for (Requirement r : t.requirements()) {
            if (!(r instanceof Requirement.Possess) && !evaluate(r, ctx).met()) {
                return false;
            }
        }
        return true;
    }

    /** Eligible and every possession requirement verified on the live inventory. */
    public static boolean claimable(TitleDefinition t, RequirementContext ctx) {
        for (Requirement r : t.requirements()) {
            if (!evaluate(r, ctx).met()) {
                return false;
            }
        }
        return true;
    }
}
