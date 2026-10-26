package com.leadsystems.minecraft.ascension.ability;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AbilityFxTest {

    @Test void ringsSitOnTheRadiusAndNeverExceedThePointCap() {
        List<Vector> ring = AbilityFx.ringPoints(5, 40);
        assertEquals(40, ring.size());
        ring.forEach(point -> assertEquals(5, Math.hypot(point.getX(), point.getZ()), 1e-9));
        assertEquals(AbilityFx.MAX_POINTS, AbilityFx.ringPoints(500, 100_000).size());
        assertEquals(16, AbilityFx.pointsFor(0.5));
        assertEquals(AbilityFx.MAX_POINTS, AbilityFx.pointsFor(1_000));
    }

    @Test void beamsStartAndEndExactlyAndStayCappedWhenLong() {
        Vector start = new Vector(0, 64, 0);
        Vector end = new Vector(3, 64, 4);
        List<Vector> line = AbilityFx.linePoints(start, end, 0.35);
        assertEquals(start, line.getFirst());
        assertEquals(end, line.getLast());
        assertTrue(AbilityFx.linePoints(start, new Vector(10_000, 64, 0), 0.35).size() <= AbilityFx.MAX_POINTS);
        assertEquals(2, AbilityFx.linePoints(start, start, 0.35).size(), "zero-length beam is still drawable");
    }

    @Test void lightningArcsKeepTheirEndpointsOnTheTargets() {
        Vector start = new Vector(0, 65, 0);
        Vector end = new Vector(6, 66, 2);
        List<Vector> arc = AbilityFx.arcPoints(start, end, 0.6);
        assertEquals(start, arc.getFirst());
        assertEquals(end, arc.getLast());
        assertTrue(arc.size() <= AbilityFx.MAX_POINTS);
    }
}
