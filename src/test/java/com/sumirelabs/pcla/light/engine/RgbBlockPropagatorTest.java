package com.sumirelabs.pcla.light.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RgbBlockPropagatorTest {
    static final class Grid implements RgbBlockAccess {
        final int width, height, depth;
        final int[] lights, sources, filters, closedFaces;
        Grid(int width, int height, int depth) {
            this.width = width; this.height = height; this.depth = depth;
            int count = width * height * depth;
            lights = new int[count]; sources = new int[count]; filters = new int[count]; closedFaces = new int[count];
        }
        int at(int x, int y, int z) { return x + width * (z + depth * y); }
        public int cellCount() { return lights.length; }
        public int neighbor(int cell, int direction) {
            int x = cell % width, z = cell / width % depth, y = cell / (width * depth);
            return switch (direction) {
                case 0 -> x + 1 < width ? cell + 1 : -1;
                case 1 -> x > 0 ? cell - 1 : -1;
                case 2 -> y + 1 < height ? cell + width * depth : -1;
                case 3 -> y > 0 ? cell - width * depth : -1;
                case 4 -> z + 1 < depth ? cell + width : -1;
                case 5 -> z > 0 ? cell - width : -1;
                default -> throw new IllegalArgumentException();
            };
        }
        public int emission(int cell) { return sources[cell]; }
        public int absorption(int cell, int direction) {
            int target = neighbor(cell, direction);
            if (target < 0 || (closedFaces[cell] & 1 << direction) != 0
                    || (closedFaces[target] & 1 << (direction ^ 1)) != 0) return 0xfff;
            return filters[target];
        }
        public int light(int cell) { return lights[cell]; }
        public void light(int cell, int color) { lights[cell] = color; }
    }

    /** Independent full rebuild: three scalar fields, repeated relaxation, no engine queues or RGB helpers. */
    static int[] reference(Grid grid) {
        int[] result = new int[grid.cellCount()];
        for (int shift : new int[]{0, 4, 8}) {
            int[] lane = new int[result.length];
            for (int cell = 0; cell < lane.length; cell++) lane[cell] = grid.sources[cell] >> shift & 15;
            boolean changed;
            int passes = 0;
            do {
                changed = false;
                for (int cell = 0; cell < lane.length; cell++) {
                    for (int direction = 0; direction < 6; direction++) {
                        int from = grid.neighbor(cell, direction);
                        if (from < 0) continue;
                        int loss = Math.max(1, grid.absorption(from, direction ^ 1) >> shift & 15);
                        int candidate = lane[from] - loss;
                        if (candidate > lane[cell]) { lane[cell] = candidate; changed = true; }
                    }
                }
                assertTrue(++passes <= 16, "positive-loss scalar oracle must converge");
            } while (changed);
            for (int cell = 0; cell < lane.length; cell++) result[cell] |= lane[cell] << shift;
        }
        return result;
    }

    @Test void whiteLightMatchesManhattanDistanceAndDiesAfterRemovingSource() {
        var grid = new Grid(33, 5, 5);
        int source = grid.at(16, 2, 2);
        grid.sources[source] = 0xfff;
        var engine = new RgbBlockPropagator(grid);
        engine.relight();
        for (int y = 0; y < grid.height; y++) for (int z = 0; z < grid.depth; z++) for (int x = 0; x < grid.width; x++) {
            int expected = Math.max(0, 15 - Math.abs(x - 16) - Math.abs(y - 2) - Math.abs(z - 2));
            assertEquals(expected * 0x111, grid.light(grid.at(x, y, z)));
        }
        grid.sources[source] = 0;
        assertTrue(engine.update(source).decreaseVisits() > 0);
        assertArrayEquals(new int[grid.cellCount()], grid.lights);
    }

    @Test void removalContinuesPastDimEmittersAndPreservesOtherColors() {
        var grid = new Grid(33, 1, 1);
        grid.sources[2] = 0xf00; grid.sources[6] = 0x600; grid.sources[11] = 0x00f;
        var engine = new RgbBlockPropagator(grid);
        engine.relight();
        assertArrayEquals(reference(grid), grid.lights);
        grid.sources[2] = 0;
        engine.update(2);
        assertArrayEquals(reference(grid), grid.lights);
        assertEquals(0, grid.lights[13] & 0xf00, "no red ghost beyond the dim emitter");
        assertEquals(15, grid.lights[11] & 15);
        grid.sources[6] = 0;
        engine.update(6);
        assertArrayEquals(reference(grid), grid.lights);
    }

    @Test void decreaseDoesNotReseedAnEntireDarkField() {
        var grid=new Grid(32,1,1);grid.sources[0]=0xfff;
        var engine=new RgbBlockPropagator(grid);engine.relight();
        grid.sources[0]=0;var work=engine.update(0);
        assertArrayEquals(reference(grid),grid.lights);
        assertEquals(2,work.increaseVisits(),"Only the changed root and its initial neighbor need revisiting");
    }

    @Test void repeatedDecreasePathsRestoreClearedWeakAndChangedEmitters() {
        var grid=new Grid(24,1,1);
        grid.sources[2]=0xf00;grid.sources[10]=0xf00;grid.sources[6]=0x200;
        var engine=new RgbBlockPropagator(grid);engine.relight();
        assertArrayEquals(reference(grid),grid.lights);
        // Both removed sources reach the weak emitter. After the first visit
        // clears it, later decrease visits must leave its pending seed intact.
        grid.sources[2]=0;grid.sources[10]=0;grid.sources[9]=0x00f;
        engine.update(2,10,2,9);
        assertArrayEquals(reference(grid),grid.lights);
        assertEquals(0x200,grid.lights[6]&0xf00);
        assertEquals(0x00f,grid.lights[9]&0x00f);
        grid.sources[6]=0;grid.sources[9]=0;
        engine.update(6,9,6);
        assertArrayEquals(reference(grid),grid.lights);
    }

    @Test void decreasedEmitterRestartsAfterAnUpstreamFaceCloses() {
        var grid=new Grid(24,1,1);grid.sources[0]=0xf00;grid.sources[8]=0x300;
        var engine=new RgbBlockPropagator(grid);engine.relight();
        assertEquals(0x700,grid.lights[8],"Strong upstream source hides the dim emitter");
        grid.closedFaces[4]=1;engine.update(4);
        assertArrayEquals(reference(grid),grid.lights);
        assertEquals(0x300,grid.lights[8],"Emitter cleared by decrease must still be an increase seed");
        grid.closedFaces[4]=0;engine.update(4);
        assertArrayEquals(reference(grid),grid.lights);
    }

    @Test void overlappingSourcesPreserveWeakChannelWhileAnotherChannelIncreases() {
        var grid=new Grid(32,32,32);
        for(int z=0;z<32;z++)for(int x=0;x<32;x++)grid.filters[grid.at(x,0,z)]=0xfff;
        int first=grid.at(17,12,15),second=grid.at(15,12,15),sample=grid.at(3,12,15);
        var engine=new RgbBlockPropagator(grid);
        grid.sources[first]=0x16f;engine.update(first);
        assertArrayEquals(reference(grid),grid.lights);
        grid.sources[second]=0xf31;engine.update(second);
        // Red from the second source increases while blue from the first still
        // contributes one level. Whole-RGB stale checks must not discard that blue.
        assertEquals(0x301,grid.lights[sample]);
        assertArrayEquals(reference(grid),grid.lights);
        grid.sources[second]=0x3f1;engine.update(second);
        assertArrayEquals(reference(grid),grid.lights);
        grid.sources[second]=0;engine.update(second);
        assertArrayEquals(reference(grid),grid.lights);
        assertEquals(0x001,grid.lights[sample]);
    }

    @Test void sameMaximumSourceColorChangeReplacesChannels() {
        var grid = new Grid(21, 3, 3);
        int source = grid.at(10, 1, 1);
        var engine = new RgbBlockPropagator(grid);
        for (int color : new int[]{0xf00, 0x00f, 0x0f0, 0xf0f, 0x777, 0}) {
            grid.sources[source] = color;
            engine.update(source);
            assertArrayEquals(reference(grid), grid.lights);
        }
    }

    @Test void simultaneousCorridorColorsPropagateBackAcrossTheirMergeWithSingleOperationBudgets() {
        var grid=new Grid(7,1,7);
        for(int z=0;z<7;z++)for(int x=0;x<7;x++)
            if(x!=3 && z!=3)grid.filters[grid.at(x,0,z)]=0xfff;
        int red=grid.at(0,0,3),blue=grid.at(3,0,0);
        grid.sources[red]=0xf00;grid.sources[blue]=0x00f;
        var engine=new RgbBlockPropagator(grid);
        engine.startRelight();while(!engine.advance(1)){}
        assertArrayEquals(reference(grid),grid.lights);
        assertEquals(0xc0c,grid.lights[grid.at(3,0,3)]);
        assertEquals(0xd0b,grid.lights[grid.at(2,0,3)]);
        assertEquals(0xb0d,grid.lights[grid.at(3,0,2)]);
        grid.sources[red]=0;
        engine.startUpdate(red);while(!engine.advance(1)){}
        assertArrayEquals(reference(grid),grid.lights);
        grid.sources[red]=0x0f0;grid.sources[blue]=0xf00;
        engine.startUpdate(red,blue);while(!engine.advance(1)){}
        assertArrayEquals(reference(grid),grid.lights);
        grid.sources[red]=0;grid.sources[blue]=0;
        engine.startUpdate(red,blue);while(!engine.advance(1)){}
        assertArrayEquals(reference(grid),grid.lights);
    }

    @Test void changedFacesInvalidateOldPathsEvenWhenTheNewFaceIsClosed() {
        var grid = new Grid(33, 1, 1);
        grid.sources[12] = 0xfff;
        var engine = new RgbBlockPropagator(grid);
        engine.relight();
        grid.closedFaces[16] = 1; // +X closes at the section boundary; cell 16 keeps the same final light.
        int before = grid.lights[16];
        engine.update(16);
        assertEquals(before, grid.lights[16]);
        assertEquals(0, grid.lights[17]);
        assertArrayEquals(reference(grid), grid.lights);
        grid.closedFaces[16] = 0;
        engine.update(16);
        assertArrayEquals(reference(grid), grid.lights);
        assertTrue(grid.lights[17] > 0);
    }

    @Test void weakIncomingLightStillVisitsAnUnrestoredStrongEmitter() {
        var grid=new Grid(20,1,1);
        grid.sources[0]=0x300;grid.sources[2]=0x00f;
        var engine=new RgbBlockPropagator(grid);
        // Start from empty light without relighting every emitter. Cell 2 is
        // outside the root-neighbor seeds and first receives only red level one.
        engine.startUpdate(0);while(!engine.advance(1)){}
        assertArrayEquals(reference(grid),grid.lights);
        assertEquals(0x10f,grid.lights[2]);
        assertEquals(0x00e,grid.lights[3],"Strong own emission must propagate beyond weak incoming RGB");
        grid.sources[2]=0x0f0;
        engine.startUpdate(2);while(!engine.advance(1)){}
        assertArrayEquals(reference(grid),grid.lights);
        grid.sources[2]=0;
        engine.startUpdate(2);while(!engine.advance(1)){}
        assertArrayEquals(reference(grid),grid.lights);
        grid.sources[0]=0;engine.update(0);
        assertArrayEquals(reference(grid),grid.lights);
    }

    @Test void insertingReplacingAndRemovingGlassMatchesIndependentChannels() {
        var grid = new Grid(25, 1, 1);
        grid.sources[4] = 0xfff;
        var engine = new RgbBlockPropagator(grid);
        engine.relight();
        for (int filter : new int[]{0x1dd, 0xdc1, 0xfff, 0x333, 0}) {
            grid.filters[8] = filter;
            engine.update(8);
            assertArrayEquals(reference(grid), grid.lights);
        }
        grid.filters[4] = 0xfff; // Opaque source still emits outward.
        engine.update(4);
        assertEquals(0xeee, grid.lights[5]);
        assertArrayEquals(reference(grid), grid.lights);
    }

    @Test void randomizedBatchesMatchFullScalarRebuilds() {
        for (long seed : new long[]{1, 19, 20260911, 0xabcdef}) {
            Random random = new Random(seed);
            var grid = new Grid(9, 5, 7);
            var engine = new RgbBlockPropagator(grid);
            for (int cell = 0; cell < grid.cellCount(); cell++) {
                grid.sources[cell] = random.nextInt(20) == 0 ? random.nextInt(4096) : 0;
                grid.filters[cell] = random.nextInt(8) == 0 ? random.nextInt(4096) : 0;
                grid.closedFaces[cell] = random.nextInt(12) == 0 ? random.nextInt(64) : 0;
            }
            engine.relight();
            assertArrayEquals(reference(grid), grid.lights);
            for (int step = 0; step < 200; step++) {
                int[] edits = new int[1 + random.nextInt(6)];
                for (int i = 0; i < edits.length; i++) {
                    int cell = edits[i] = random.nextInt(grid.cellCount());
                    switch (random.nextInt(3)) {
                        case 0 -> grid.sources[cell] = random.nextBoolean() ? 0 : random.nextInt(4096);
                        case 1 -> grid.filters[cell] = random.nextBoolean() ? 0 : random.nextInt(4096);
                        case 2 -> grid.closedFaces[cell] = random.nextBoolean() ? 0 : random.nextInt(64);
                    }
                }
                engine.update(edits);
                assertArrayEquals(reference(grid), grid.lights, "seed=" + seed + " step=" + step);
            }
        }
    }

    @Test void validatesWholeBatchBeforeMutationAndHandlesDuplicateRoots() {
        var grid = new Grid(20, 1, 1);
        grid.sources[4] = 0xfff;
        var engine = new RgbBlockPropagator(grid);
        engine.relight();
        int[] before = grid.lights.clone();
        assertThrows(IndexOutOfBoundsException.class, () -> engine.update(4, 20));
        assertArrayEquals(before, grid.lights);
        assertEquals(0, engine.update().writes());
        grid.sources[4] = 0x00f;
        engine.update(4, 4, 4);
        assertArrayEquals(reference(grid), grid.lights);
    }

    @Test void saturatedNeighborsAndLevelOneFrontiersSkipAbsorptionQueries() {
        var grid = new Grid(21, 1, 1);
        Arrays.fill(grid.sources, 0xfff);
        var engine = new RgbBlockPropagator(grid);
        engine.relight();
        // Initial ordering can still propagate into emitters before they are visited.
        // An unchanged batch restores its root from neighboring light; saturated
        // neighbors themselves cannot benefit from another face/opacity query.
        var update = engine.update(10);
        assertArrayEquals(reference(grid), grid.lights);
        assertTrue(update.absorptionReads() < update.increaseVisits() * 2);
        Arrays.fill(grid.sources, 0);
        grid.sources[10] = 0x111;
        assertEquals(0, engine.relight().absorptionReads());
        assertEquals(0x111, grid.lights[10]);
        assertEquals(0, grid.lights[11]);
    }
}
