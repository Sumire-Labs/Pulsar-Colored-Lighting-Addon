package com.sumirelabs.pcla.light.engine;

/** Reusable primitive FIFO. Growth preserves pending entries, including after wraparound. */
final class LongWorkQueue {
    private long[] entries = new long[64];
    private int head;
    private int size;

    void add(long entry) {
        if (size == entries.length) {
            if (entries.length >= 1 << 29) throw new IllegalStateException("RGB work queue exceeds supported capacity");
            long[] grown = new long[entries.length << 1];
            for (int i = 0; i < size; i++) grown[i] = entries[(head + i) & (entries.length - 1)];
            entries = grown;
            head = 0;
        }
        entries[(head + size++) & (entries.length - 1)] = entry;
    }

    long remove() {
        if (size == 0) throw new IllegalStateException("Empty queue");
        long entry = entries[head];
        head = (head + 1) & (entries.length - 1);
        size--;
        return entry;
    }

    boolean isEmpty() { return size == 0; }
    void clear() { head = size = 0; }
}
