package com.sumirelabs.pcla.light.engine;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RgbSkyBoundaryTest {
    @Test void fullStrengthMaskRejectsWeakInputsAndDropsOnlyAbsorbedChannels() {
        assertEquals(0xf0f,RgbSkyBoundary.fullStrengthThrough(0xfff,0x010));
        assertEquals(0x00f,RgbSkyBoundary.fullStrengthThrough(0xf0f,0x100));
        assertEquals(0,RgbSkyBoundary.fullStrengthThrough(0xfff,0xfff));
        assertThrows(IllegalArgumentException.class,()->RgbSkyBoundary.fullStrengthThrough(0xfef,0));
        assertThrows(IllegalArgumentException.class,()->RgbSkyBoundary.fullStrengthThrough(0xfff,0x1000));
    }

    @Test void farAboveFiltersCanSeedACropWithoutLosingLightInItsFifteenCellInteriorMargin() {
        var world=new RgbSkyPropagatorTest.Grid(41,72,41); world.whiteTop();
        var random=new Random(0x544f50);
        int[] filters={0xfff,0x0ee,0xe0e,0xee0,0x333};
        for(int i=0;i<600;i++) world.opacity[world.at(random.nextInt(41),2+random.nextInt(67),random.nextInt(41))]=filters[random.nextInt(filters.length)];
        // A colored obstruction well above the crop must still affect its direct column mask.
        world.opacity[world.at(20,60,20)]=0x0ee;
        var crop=new RgbSkyPropagatorTest.Grid(35,35,35);
        int originX=3,originY=5,originZ=3,top=originY+crop.height-1;
        for(int y=0;y<crop.height;y++) for(int z=0;z<crop.depth;z++) for(int x=0;x<crop.width;x++) {
            int local=crop.at(x,y,z),global=world.at(x+originX,y+originY,z+originZ);
            crop.opacity[local]=world.opacity[global]; crop.faces[local]=world.faces[global];
        }
        for(int z=0;z<crop.depth;z++) for(int x=0;x<crop.width;x++) {
            int mask=0xfff;
            for(int y=world.height-2;y>=top;y--) mask=RgbSkyBoundary.fullStrengthThrough(mask,world.absorption(world.at(x+originX,y+1,z+originZ),3));
            crop.boundary[crop.at(x,crop.height-1,z)]=mask;
        }
        new RgbSkyPropagator(crop).relight();
        int[] expected=RgbSkyPropagatorTest.reference(world);
        for(int y=15;y<20;y++) for(int z=15;z<20;z++) for(int x=15;x<20;x++)
            assertEquals(expected[world.at(x+originX,y+originY,z+originZ)],crop.light(crop.at(x,y,z)),"crop cell "+x+","+y+","+z);
    }
}
