package com.sumirelabs.pcla.light.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionBoundsTest {
    @Test void multiSectionShapesMapOutputsUniquelyAndShareOneHalo() {
        for(int sx=1;sx<=2;sx++) for(int sy=1;sy<=2;sy++) for(int sz=1;sz<=2;sz++) {
            var b=new CapturedRgbRegion.Bounds(-32,-16,16,sx,sy,sz);
            assertEquals((sx+2)*(sz+2),b.chunks().size());
            var seen=new java.util.BitSet(b.cells());
            for(int cell=0;cell<b.outputCells();cell++) {
                int mapped=b.interiorIndex(cell);
                assertFalse(seen.get(mapped)); seen.set(mapped);
                int x=b.x()+cell%b.sizeX(), z=b.z()+cell/b.sizeX()%b.sizeZ(), y=b.y()+cell/(b.sizeX()*b.sizeZ());
                assertEquals(mapped,b.inputCell(x,y,z));
            }
            assertEquals(b.outputCells(),seen.cardinality());
        }
        assertEquals(238328,new CapturedRgbRegion.Bounds(0,0,0,2,2,2).cells());
        assertThrows(IllegalArgumentException.class,()->new CapturedRgbRegion.Bounds(1,0,0));
        assertThrows(IllegalArgumentException.class,()->new CapturedRgbRegion.Bounds(0,0,0,3,1,1));
        assertThrows(ArithmeticException.class,()->new CapturedRgbRegion.Bounds(Integer.MIN_VALUE,0,0));
    }
    @Test void haloDependenciesAndInteriorMappingWorkAcrossNegativeChunks() {
        for (int origin : new int[]{-1024, -16, 0, 16, 1024}) {
            var bounds = new CapturedRgbRegion.Bounds(origin, -16, origin);
            var keys = bounds.chunks();
            assertTrue(bounds.containsInput(origin - 15, -31, origin + 30));
            assertFalse(bounds.containsInput(origin - 16, -16, origin));
            assertFalse(bounds.containsInput(origin, 15, origin));
            assertEquals(9, keys.size());
            for (int z = origin - 15; z <= origin + 30; z++)
                for (int x = origin - 15; x <= origin + 30; x++)
                    assertTrue(keys.contains(RegionPublication.key(x >> 4, z >> 4)));
            for (int cell = 0; cell < 4096; cell++) {
                int mapped = bounds.interiorIndex(cell);
                assertEquals(15 + (cell & 15), mapped % 46);
                assertEquals(15 + (cell >> 4 & 15), mapped / 46 % 46);
                assertEquals(15 + (cell >> 8), mapped / (46 * 46));
            }
        }
    }
}
