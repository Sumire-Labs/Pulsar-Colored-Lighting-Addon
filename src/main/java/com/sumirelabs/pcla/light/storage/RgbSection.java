package com.sumirelabs.pcla.light.storage;

import com.sumirelabs.pcla.light.PackedRgb;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Single-writer / multiple-reader 16-cube RGB storage. Index = x | z<<4 | y<<8.
 * The writer owns a private dense array; immutable 64-cell pages are copied only
 * at publication. Unchanged pages and historical snapshots remain shared safely.
 * A snapshot is atomic for this section only; a world adapter must coordinate
 * completion and publication across every section affected by a task.
 */
public final class RgbSection {
    public static final int SIZE = 4096;
    private static final int PAGE_SHIFT = 6;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int PAGE_COUNT = SIZE / PAGE_SIZE;
    private static final short[] ZERO_PAGE = new short[PAGE_SIZE];
    // RGB is 12-bit: one bounded immutable page per nonzero uniform color.
    // Index zero deliberately stays empty because sparse snapshots use null for zero.
    private static final AtomicReferenceArray<short[]> UNIFORM_PAGES = new AtomicReferenceArray<>(4096);
    // Never escapes to a Snapshot. Retained through zero-light periods while this section lives.
    private short[] updating;
    private volatile Snapshot visible = new Snapshot(new short[PAGE_COUNT][]);
    private long dirtyPages;

    public static final class Snapshot {
        private final short[][] pages;
        private Snapshot(short[][] pages) { this.pages = pages; }

        public int get(int index) {
            checkIndex(index);
            short[] page = pages[index >>> PAGE_SHIFT];
            return page == null ? 0 : page[index & (PAGE_SIZE - 1)];
        }

        /** Compare immutable content; a missing section is all zero. */
        public boolean contentEquals(Snapshot other) {
            if (this == other) return true;
            for (int index = 0; index < PAGE_COUNT; index++) {
                short[] left = pages[index], right = other == null ? null : other.pages[index];
                if (left == right) continue;
                if (left == null) {
                    if (!Arrays.equals(right, ZERO_PAGE)) return false;
                } else if (right == null) {
                    if (!Arrays.equals(left, ZERO_PAGE)) return false;
                } else if (!Arrays.equals(left, right)) return false;
            }
            return true;
        }

        /** Visit canonical values in section order without exposing backing pages. */
        public void forEach(java.util.function.IntConsumer sink) {
            java.util.Objects.requireNonNull(sink);
            for (short[] page : pages) {
                if (page == null) {
                    for (int cell = 0; cell < PAGE_SIZE; cell++) sink.accept(0);
                } else {
                    for (short value : page) sink.accept(value);
                }
            }
        }

        /** Caller-owned serialization buffer; internal arrays are never exposed. */
        public void copyTo(short[] destination) {
            if (destination.length != SIZE) throw new IllegalArgumentException("Expected 4096 cells");
            for (int page = 0; page < PAGE_COUNT; page++) {
                int start = page << PAGE_SHIFT;
                if (pages[page] == null) Arrays.fill(destination, start, start + PAGE_SIZE, (short) 0);
                else System.arraycopy(pages[page], 0, destination, start, PAGE_SIZE);
            }
        }

        /** Copy an immutable range without exposing the backing pages. */
        public void copyTo(int sourceOffset, short[] destination, int destinationOffset, int length) {
            java.util.Objects.checkFromIndexSize(sourceOffset, length, SIZE);
            java.util.Objects.checkFromIndexSize(destinationOffset, length, destination.length);
            while (length > 0) {
                int offset = sourceOffset & (PAGE_SIZE - 1);
                int count = Math.min(length, PAGE_SIZE - offset);
                short[] page = pages[sourceOffset >>> PAGE_SHIFT];
                if (page == null) Arrays.fill(destination, destinationOffset, destinationOffset + count, (short) 0);
                else System.arraycopy(page, offset, destination, destinationOffset, count);
                sourceOffset += count; destinationOffset += count; length -= count;
            }
        }
    }

    public int getUpdating(int index) {
        checkIndex(index);
        short[] values = updating;
        return values == null ? 0 : values[index];
    }

    public void set(int index, int color) {
        checkIndex(index);
        PackedRgb.validate(color);
        short[] values = updating;
        if (values == null) {
            if (color == 0) return;
            updating = values = new short[SIZE];
        } else if (values[index] == color) return;
        values[index] = (short) color;
        dirtyPages |= 1L << (index >>> PAGE_SHIFT);
    }

    /** Does not expose the new working state until publish() is called. */
    public void clear() {
        if (updating == null) return;
        Arrays.fill(updating, (short) 0);
        dirtyPages = -1L;
    }

    /** Validate the entire persisted/received buffer before replacing working data. */
    public void replace(short[] values) {
        if (values.length != SIZE) throw new IllegalArgumentException("Expected 4096 cells");
        boolean nonzero = false;
        for (short value : values) {
            PackedRgb.validate(value);
            nonzero |= value != 0;
        }
        if (updating == null) {
            if (!nonzero) return;
            updating = new short[SIZE];
        }
        System.arraycopy(values, 0, updating, 0, SIZE);
        dirtyPages = -1L;
    }

    public Snapshot snapshot() { return visible; }

    /** Publish only modified pages; writes that cancel each other retain snapshot identity. */
    public Snapshot publish() {
        if (dirtyPages == 0) return visible;
        Snapshot previous = visible;
        short[][] next = null;
        for (long pages = dirtyPages; pages != 0; pages &= pages - 1) {
            int page = Long.numberOfTrailingZeros(pages);
            int start = page << PAGE_SHIFT, end = start + PAGE_SIZE;
            short[] old = previous.pages[page];
            boolean empty = Arrays.equals(updating, start, end, ZERO_PAGE, 0, PAGE_SIZE);
            if (empty ? old == null : old != null && Arrays.equals(updating, start, end, old, 0, PAGE_SIZE)) continue;
            if (next == null) next = previous.pages.clone();
            next[page] = empty ? null : immutablePage(start, end);
        }
        dirtyPages = 0;
        if (next != null) visible = new Snapshot(next);
        return visible;
    }

    private short[] immutablePage(int start, int end) {
        int color = updating[start];
        if (color != 0) {
            short[] shared = UNIFORM_PAGES.get(color);
            if (shared != null) {
                if (Arrays.equals(updating, start, end, shared, 0, PAGE_SIZE)) return shared;
            } else {
                // Only genuinely uniform ranges populate the process-wide table.
                // Nonuniform pages whose first cell is colored must not retain unused palette entries.
                boolean uniform = true;
                for (int cell = start + 1; cell < end; cell++) if (updating[cell] != color) { uniform = false; break; }
                if (uniform) {
                    short[] page = new short[PAGE_SIZE];
                    Arrays.fill(page, (short) color);
                    if (UNIFORM_PAGES.compareAndSet(color, null, page)) return page;
                    return UNIFORM_PAGES.get(color);
                }
            }
        }
        return Arrays.copyOfRange(updating, start, end);
    }

    /** Restore only changed writer pages; no reader-owned array is ever modified. */
    public void discard() {
        Snapshot snapshot = visible;
        for (long pages = dirtyPages; pages != 0; pages &= pages - 1) {
            int page = Long.numberOfTrailingZeros(pages), start = page << PAGE_SHIFT;
            short[] old = snapshot.pages[page];
            if (old == null) Arrays.fill(updating, start, start + PAGE_SIZE, (short) 0);
            else System.arraycopy(old, 0, updating, start, PAGE_SIZE);
        }
        dirtyPages = 0;
    }

    static Snapshot emptyWordSnapshot() {
        return new Snapshot(new short[PAGE_COUNT][]);
    }
    private static final int[] ZERO_WORD_PAGE=new int[PAGE_SIZE];
    static Snapshot publishWordPages(Snapshot previous,int[] words,long dirty) {
        short[][] next=null;
        for(long bits=dirty;bits!=0;bits&=bits-1) {
            int page=Long.numberOfTrailingZeros(bits),start=page<<PAGE_SHIFT,end=start+PAGE_SIZE;
            short[] old=previous.pages[page];
            boolean empty=Arrays.equals(words,start,end,ZERO_WORD_PAGE,0,PAGE_SIZE);
            if(empty) {
                if(old==null)continue;
                if(next==null)next=previous.pages.clone();
                next[page]=null;
                continue;
            }
            if(old!=null) {
                boolean equal=true;
                for(int j=0;j<PAGE_SIZE;j++) {
                    if(com.sumirelabs.pcla.light.engine.PackedWordRgb.toRgb12(words[start+j])!=old[j]) {
                        equal=false;
                        break;
                    }
                }
                if(equal)continue;
            }
            if(next==null)next=previous.pages.clone();
            int firstWord=words[start];
            boolean uniform=firstWord!=0;
            if(uniform)for(int cell=start+1;cell<end;cell++)if(words[cell]!=firstWord) {
                uniform=false;
                break;
            }
            if(uniform) {
                int first=com.sumirelabs.pcla.light.engine.PackedWordRgb.toRgb12(firstWord);
                short[] shared=UNIFORM_PAGES.get(first);
                if(shared==null) {
                    short[] values=new short[PAGE_SIZE];
                    Arrays.fill(values,(short)first);
                    if(UNIFORM_PAGES.compareAndSet(first,null,values))shared=values;
                    else shared=UNIFORM_PAGES.get(first);
                }
                next[page]=shared;
            }
            else {
                short[] values=new short[PAGE_SIZE];
                for(int j=0;j<PAGE_SIZE;j++)values[j]=(short)com.sumirelabs.pcla.light.engine.PackedWordRgb.toRgb12(words[start+j]);
                next[page]=values;
            }
        }
        return next==null?previous:new Snapshot(next);
    }
    static void restoreWords(Snapshot visible,int[] words,long dirty) {
        for(long bits=dirty;bits!=0;bits&=bits-1) {
            int page=Long.numberOfTrailingZeros(bits),start=page<<PAGE_SHIFT;
            short[] old=visible.pages[page];
            for(int j=0;j<PAGE_SIZE;j++)words[start+j]=old==null?0:com.sumirelabs.pcla.light.engine.PackedWordRgb.fromRgb12(old[j]);
        }
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= SIZE) throw new IndexOutOfBoundsException("Section cell " + index);
    }
}
