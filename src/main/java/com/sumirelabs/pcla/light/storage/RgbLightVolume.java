package com.sumirelabs.pcla.light.storage;

import com.sumirelabs.pcla.light.PackedRgb;
import java.util.BitSet;

/**
 * Bounded staging storage for the propagation core. Cell ID = x + sizeX*(z + sizeZ*y).
 * One volatile root publishes all touched sections together. Readers retain a
 * Snapshot for a coherent task result. Coordinates are relative to the adapter's
 * origin; no assumptions about world minimum Y or chunk X/Z signs are made here.
 */
public final class RgbLightVolume {
    private record Shape(int sizeX, int sizeY, int sizeZ, int sectionsX, int sectionsZ, int cells, int sections) {
        int section(int cell) {
            check(cell);
            // Shared world fields are full-height 16x16 columns: their cell IDs
            // already contain a 12-bit section-local index, with no division.
            if (sizeX == 16 && sizeZ == 16) return cell >>> 12;
            int x = cell % sizeX, z = cell / sizeX % sizeZ, y = cell / (sizeX * sizeZ);
            return (x >>> 4) + sectionsX * ((z >>> 4) + sectionsZ * (y >>> 4));
        }
        int local(int cell) {
            if (sizeX == 16 && sizeZ == 16) return cell & 4095;
            int x = cell % sizeX, z = cell / sizeX % sizeZ, y = cell / (sizeX * sizeZ);
            return x & 15 | (z & 15) << 4 | (y & 15) << 8;
        }
        void check(int cell) {
            if (cell < 0 || cell >= cells) throw new IndexOutOfBoundsException("Volume cell " + cell);
        }
    }

    public static final class Snapshot {
        private final Shape shape;
        private final RgbSection.Snapshot[] sections;
        private Snapshot(Shape shape, RgbSection.Snapshot[] sections) { this.shape = shape; this.sections = sections; }
        public int get(int cell) {
            RgbSection.Snapshot section = sections[shape.section(cell)];
            return section == null ? 0 : section.get(shape.local(cell));
        }
        public int cellCount() { return shape.cells; }
        public int sectionCount() { return sections.length; }
        /** Whether section order represents one 16x16 horizontal column. */
        public boolean isColumn() { return shape.sizeX == 16 && shape.sizeZ == 16; }
        /** Immutable identity suitable for section-level change detection; null is all zero. */
        public RgbSection.Snapshot section(int index) { return sections[index]; }

        /** Export x-fastest rows into a caller-owned buffer; missing sections overwrite with zero. */
        public void copyTo(short[] destination) {
            if (destination.length != shape.cells) throw new IllegalArgumentException("Invalid volume buffer size");
            int target = 0;
            for (int y = 0; y < shape.sizeY; y++) for (int z = 0; z < shape.sizeZ; z++) {
                int sectionRow = shape.sectionsX * ((z >>> 4) + shape.sectionsZ * (y >>> 4));
                int localRow = (z & 15) << 4 | (y & 15) << 8;
                for (int sx = 0; sx < shape.sectionsX; sx++) {
                    int count = Math.min(16, shape.sizeX - (sx << 4));
                    var section = sections[sectionRow + sx];
                    if (section == null) java.util.Arrays.fill(destination, target, target + count, (short) 0);
                    else section.copyTo(localRow, destination, target, count);
                    target += count;
                }
            }
        }
    }

    /** Single-writer working view of a column; this binding is not an immutable snapshot. */
    public static final class UpdatingColumnReader {
        private final RgbSection[] sections;
        private final int cells;
        private UpdatingColumnReader(RgbSection[] sections, int cells) {
            this.sections = sections;
            this.cells = cells;
        }
        public int get(int cell) {
            if (cell < 0 || cell >= cells) throw new IndexOutOfBoundsException("Volume cell " + cell);
            RgbSection section = sections[cell >>> 12];
            return section == null ? 0 : section.getUpdating(cell & 4095);
        }
    }

    /**
     * Worker-only native BLOCK writers publish canonical 12-bit immutable roots.
     * Private dense arrays are allocated on the first nonzero write and retain
     * their identity through clear, discard and publication; snapshots never own them.
     */
    public static final class WordColumnVolume {
        private static final class WordSection {
            final int[] words=new int[4096];
            long dirty;
            RgbSection.Snapshot visible=RgbSection.emptyWordSnapshot();
            void set(int local,int word) {
                if(words[local]!=word) {
                    words[local]=word;
                    dirty|=1L<<(local>>>6);
                }
            }
            RgbSection.Snapshot publish() {
                visible=RgbSection.publishWordPages(visible,words,dirty);
                dirty=0;
                return visible;
            }
            void discard() {
                RgbSection.restoreWords(visible,words,dirty);
                dirty=0;
            }
        }
        private final Shape shape;
        private final int cells;
        private final WordSection[] sections;
        private final int[][] writers;
        private final BitSet changed=new BitSet();
        private volatile Snapshot visible;
        public WordColumnVolume(int height) {
            if(height<=0)throw new IllegalArgumentException("Positive height required");
            cells=Math.multiplyExact(height,256);
            int count=(height-1)/16+1;
            shape=new Shape(16,height,16,1,1,cells,count);
            sections=new WordSection[count];
            writers=new int[count][];
            visible=new Snapshot(shape,new RgbSection.Snapshot[count]);
        }
        private void checkWordCell(int cell) {
            if(cell<0||cell>=cells)throw new IndexOutOfBoundsException("Volume cell "+cell);
        }
        public int cellCount() {
            return cells;
        }
        public int getUpdatingWord(int cell) {
            checkWordCell(cell);
            int[] words=writers[cell>>>12];
            return words==null?0:words[cell&4095];
        }
        public void setUpdatingWord(int cell,int word) {
            checkWordCell(cell);
            com.sumirelabs.pcla.light.engine.PackedWordRgb.validate(word);
            int id=cell>>>12,local=cell&4095;
            WordSection section=sections[id];
            if(section==null) {
                if(word==0)return;
                sections[id]=section=new WordSection();
                writers[id]=section.words;
            }
            if(section.words[local]==word)return;
            section.set(local,word);
            changed.set(id);
        }
        public void setRgb12(int cell,int rgb) {
            checkWordCell(cell);
            PackedRgb.validate(rgb);
            setUpdatingWord(cell,com.sumirelabs.pcla.light.engine.PackedWordRgb.fromRgb12(rgb));
        }
        public Snapshot snapshot() {
            return visible;
        }
        public Snapshot publish() {
            if(!changed.isEmpty()) {
                RgbSection.Snapshot[] next=visible.sections.clone();
                for(int id=changed.nextSetBit(0);id>=0;id=changed.nextSetBit(id+1))next[id]=sections[id].publish();
                visible=new Snapshot(shape,next);
                changed.clear();
            }
            return visible;
        }
        public void discard() {
            for(int id=changed.nextSetBit(0);id>=0;id=changed.nextSetBit(id+1))sections[id].discard();
            changed.clear();
        }
    }

    private final Shape shape;
    private final RgbSection[] updating;
    private final BitSet changed = new BitSet();
    private volatile Snapshot visible;

    public RgbLightVolume(int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) throw new IllegalArgumentException("Positive volume dimensions required");
        int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        int sx = (sizeX - 1) / 16 + 1, sy = (sizeY - 1) / 16 + 1, sz = (sizeZ - 1) / 16 + 1;
        int sections = Math.multiplyExact(Math.multiplyExact(sx, sy), sz);
        shape = new Shape(sizeX, sizeY, sizeZ, sx, sz, cells, sections);
        updating = new RgbSection[sections];
        visible = new Snapshot(shape, new RgbSection.Snapshot[sections]);
    }

    public int cellCount() { return shape.cells; }
    public UpdatingColumnReader updatingColumnReader() {
        if (shape.sizeX != 16 || shape.sizeZ != 16)
            throw new IllegalArgumentException("Updating column reader requires 16x16 horizontal dimensions");
        return new UpdatingColumnReader(updating, shape.cells);
    }
    public int getUpdating(int cell) {
        RgbSection section = updating[shape.section(cell)];
        return section == null ? 0 : section.getUpdating(shape.local(cell));
    }

    public void set(int cell, int color) {
        int sectionId = shape.section(cell), local = shape.local(cell);
        PackedRgb.validate(color);
        RgbSection section = updating[sectionId];
        if (section == null) {
            if (color == 0) return;
            updating[sectionId] = section = new RgbSection();
        }
        if (section.getUpdating(local) == color) return;
        section.set(local, color);
        changed.set(sectionId);
    }

    public Snapshot snapshot() { return visible; }

    /** After a successful propagation task, publish one immutable root for the whole bounded view. */
    public Snapshot publish() {
        if (!changed.isEmpty()) {
            RgbSection.Snapshot[] next = visible.sections.clone();
            for (int section = changed.nextSetBit(0); section >= 0; section = changed.nextSetBit(section + 1))
                next[section] = updating[section].publish();
            visible = new Snapshot(shape, next);
            changed.clear();
        }
        return visible;
    }

    /** Roll back a failed, unpublished propagation task. Does not undo a completed publication. */
    public void discard() {
        for (int section = changed.nextSetBit(0); section >= 0; section = changed.nextSetBit(section + 1))
            updating[section].discard();
        changed.clear();
    }
}
