package com.sumirelabs.pcla.light.engine;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class LongWorkQueueTest {
    @Test void wrapGrowthAndReusePreserveEveryEntry() {
        var queue = new LongWorkQueue();
        var reference = new ArrayDeque<Long>();
        Random random = new Random(14);
        for (int operation = 0; operation < 30000; operation++) {
            if (reference.isEmpty() || random.nextInt(3) != 0) {
                long entry = random.nextLong();
                reference.add(entry); queue.add(entry);
            } else assertEquals(reference.remove().longValue(), queue.remove());
        }
        while (!reference.isEmpty()) assertEquals(reference.remove().longValue(), queue.remove());
        assertTrue(queue.isEmpty());
        queue.add(12); queue.clear(); queue.add(34);
        assertEquals(34, queue.remove());
        assertThrows(IllegalStateException.class, queue::remove);
    }
}
