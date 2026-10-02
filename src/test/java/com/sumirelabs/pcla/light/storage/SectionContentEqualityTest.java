package com.sumirelabs.pcla.light.storage;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionContentEqualityTest {
    @Test void bulkVisitMatchesEveryScalarCellAndCannotChangeRetainedContent() {
        var section = new RgbSection();
        section.set(63, 0x123); section.set(64, 0x456); section.set(4095, 0xabc);
        var snapshot = section.publish();
        int[] values = new int[4096], cursor = {0};
        snapshot.forEach(value -> values[cursor[0]++] = value);
        assertEquals(4096, cursor[0]);
        for (int cell = 0; cell < 4096; cell++) assertEquals(snapshot.get(cell), values[cell]);
        values[63] = 0;
        section.clear(); section.publish();
        assertEquals(0x123, snapshot.get(63));
        assertThrows(NullPointerException.class, () -> snapshot.forEach(null));
    }

    @Test void comparisonMatchesScalarValuesAcrossIndependentAndRetainedRoots() {
        var left = new RgbSection();
        var right = new RgbSection();
        var random = new Random(47001);
        var empty = left.snapshot();
        assertTrue(empty.contentEquals(null));
        for (int round = 0; round < 24; round++) {
            for (int edit = 0; edit < 32; edit++) {
                int cell = random.nextInt(4096), color = random.nextInt(4096);
                left.set(cell, color);
                right.set(cell, color);
            }
            var before = left.publish();
            var equal = right.publish();
            assertEquals(scalarEqual(before, equal), before.contentEquals(equal));
            assertTrue(before.contentEquals(equal));
            int cell = (round * 193 + 63) & 4095;
            right.set(cell, before.get(cell) ^ 1);
            var changed = right.publish();
            assertEquals(scalarEqual(before, changed), before.contentEquals(changed));
            assertFalse(before.contentEquals(changed));
            right.set(cell, before.get(cell));
            assertTrue(before.contentEquals(right.publish()));
            assertTrue(empty.contentEquals(null));
        }
        left.clear();
        var cleared = left.publish();
        assertTrue(cleared.contentEquals(null));
        assertTrue(cleared.contentEquals(empty));
        assertFalse(right.snapshot().contentEquals(null));
    }

    @Test void lateDifferenceAndUniformVersusMixedRemainDistinct() {
        var left = new RgbSection();
        var right = new RgbSection();
        for (int cell = 0; cell < 4096; cell++) { left.set(cell, 0xabc); right.set(cell, 0xabc); }
        var original = left.publish();
        assertTrue(original.contentEquals(right.publish()));
        right.set(4095, 0xabd);
        assertFalse(original.contentEquals(right.publish()));
        right.clear();
        assertFalse(original.contentEquals(right.publish()));
        assertEquals(0xabc, original.get(4095));
    }

    private static boolean scalarEqual(RgbSection.Snapshot left, RgbSection.Snapshot right) {
        for (int cell = 0; cell < 4096; cell++)
            if ((left == null ? 0 : left.get(cell)) != (right == null ? 0 : right.get(cell))) return false;
        return true;
    }
}
