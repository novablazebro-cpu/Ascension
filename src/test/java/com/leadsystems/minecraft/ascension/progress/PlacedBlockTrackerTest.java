package com.leadsystems.minecraft.ascension.progress;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacedBlockTrackerTest {

    @Test
    void packingIsChunkLocalAndCollisionFreeAcrossTheWorldHeight() {
        Set<Long> seen = new HashSet<>();
        for (int y = -64; y < 320; y += 7) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    seen.add(PlacedBlockTracker.pack(x, y, z));
                }
            }
        }
        assertEquals(((320 + 64 + 6) / 7) * 256, seen.size());
    }

    @Test
    void negativeWorldCoordinatesMapIntoTheChunk() {
        // x=-1 is local x 15 of chunk -1; must equal the packing of local coordinate 15.
        assertEquals(PlacedBlockTracker.pack(15, 10, 15), PlacedBlockTracker.pack(-1, 10, -1));
        assertEquals(PlacedBlockTracker.pack(0, -64, 0), PlacedBlockTracker.pack(-16, -64, 32));
    }
}
