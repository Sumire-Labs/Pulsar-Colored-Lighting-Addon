package com.sumirelabs.pcla.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidLightTintTest {
    @Test void mergesEachComponentRatherThanTakingTheLargerPackedInteger() {
        assertEquals(0xf0f,FluidLightTint.merge(0xf00,0x00f));
        assertEquals(0xd5c,FluidLightTint.merge(0x32c,0xd50));
        assertEquals(0,FluidLightTint.merge(0,0));
    }
    @Test void bothVerticalSamplesContributeToTheNativeFaceSample() {
        float[] tint=new float[3];
        FluidLightTint.compute(0xf00,0x00f,0,0,-1,0,15<<4,0,tint);
        assertArrayEquals(new float[]{1,0.03f/1.03f,1},tint,0.000001f);
    }
    @Test void absentCellOrLaneRetainsScalarInsteadOfProducingBorderDarkness() {
        assertEquals(-1,FluidLightTint.merge(-1,0xf00));assertEquals(-1,FluidLightTint.merge(0x00f,-1));
        for(int here:new int[]{-1,0x00f})for(int above:new int[]{-1,0x00f}) {
            if(here>=0 && above>=0)continue;
            float[] tint=new float[3];FluidLightTint.compute(here,above,-1,-1,-1,0,(15<<4)|(15<<20),1,tint);
            assertArrayEquals(new float[]{1,1,1},tint,0.000001f);
        }
    }
    @Test void emittedSourceColorAndFloorRemainIncludedForLava() {
        float[] tint=new float[3];FluidLightTint.compute(0,0,0,0,0xf20,15,0,0,tint);
        assertEquals(1,tint[0],0.000001f);assertTrue(tint[1]>tint[2]);assertEquals(0.03f/1.03f,tint[2],0.000001f);
        // A missing exterior lane is unknown, even if the source cell is known.
        FluidLightTint.compute(-1,0,0,0,0xf20,15,0,0,tint);assertArrayEquals(new float[]{1,1,1},tint,0.000001f);
    }
    @Test void filteredSkyUsesTheSamePerComponentRulesAndDaylightAsBlocks() {
        float[] tint=new float[3];FluidLightTint.compute(0,0,0xf00,0x00f,-1,0,15<<20,1,tint);
        assertArrayEquals(new float[]{1,0.03f/1.03f,1},tint,0.000001f);
        FluidLightTint.compute(0,0,0xf00,0x00f,-1,0,15<<20,0,tint);assertArrayEquals(new float[]{1,1,1},tint,0.000001f);
        for(int level=0;level<=15;level++) {
            FluidLightTint.compute(level*0x111,level*0x111,level*0x111,level*0x111,-1,0,(level<<4)|(level<<20),0.625f,tint);
            assertArrayEquals(new float[]{1,1,1},tint,0.000001f);
        }
    }
    @Test void invalidInputsAreRejectedBeforeWritingAnInvalidTint() {
        assertThrows(IllegalArgumentException.class,()->FluidLightTint.merge(-2,0));
        assertThrows(IllegalArgumentException.class,()->FluidLightTint.merge(0,4096));
        assertThrows(IllegalArgumentException.class,()->FluidLightTint.compute(0,0,0,0,-1,16,0,1,new float[3]));
        assertThrows(IllegalArgumentException.class,()->FluidLightTint.compute(0,0,0,0,-1,0,0,Float.NaN,new float[3]));
    }
}
