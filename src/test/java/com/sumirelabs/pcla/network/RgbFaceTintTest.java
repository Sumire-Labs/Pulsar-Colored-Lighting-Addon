package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.client.RgbFaceTint;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RgbFaceTintTest {
    @Test void neutralFieldsRemainNeutralEvenWithDifferentNeighborLightLevels() {
        var random=new Random(4911);
        for(int run=0;run<500;run++) {
            int[] samples=new int[9]; float[] corners=new float[12];
            for(int cell=0;cell<9;cell++) {
                int light=random.nextInt(16);
                samples[cell]=RgbFaceTint.pack(light*0x111,light,random.nextInt(16),random.nextBoolean());
            }
            RgbFaceTint.corners(samples,random.nextFloat(),corners);
            for(float value:corners) assertEquals(1,value,0.000001);
        }
    }
    @Test void twoOpaqueSidesExcludeColoredLightBehindTheirCorner() {
        int neutral=RgbFaceTint.pack(0x777,7,0,false);
        int[] samples=new int[9]; Arrays.fill(samples,neutral);
        samples[0]=RgbFaceTint.pack(0xf00,15,0,false);
        samples[1]=samples[3]=RgbFaceTint.pack(0,0,0,true);
        float[] closed=new float[12]; RgbFaceTint.corners(samples,0,closed);
        assertEquals(1,closed[0]); assertEquals(1,closed[1]); assertEquals(1,closed[2]);
        samples[1]=neutral;
        float[] open=new float[12]; RgbFaceTint.corners(samples,0,open);
        assertTrue(open[1]<closed[1]); assertEquals(1,open[0]);
        for(int i=3;i<12;i++) assertEquals(closed[i],open[i]);
    }
    @Test void adjacentBlocksShareCornerColorsAndInteriorInterpolationIsContinuous() {
        // An arbitrary world field sampled from adjacent blocks must agree along the shared edge.
        float[] left=new float[12],right=new float[12];
        int[] a=new int[9],b=new int[9];
        for(int v=-1;v<=1;v++) for(int u=-1;u<=1;u++) {
            a[u+1+3*(v+1)]=world(u,v); b[u+1+3*(v+1)]=world(u+1,v);
        }
        RgbFaceTint.corners(a,0.6f,left); RgbFaceTint.corners(b,0.6f,right);
        for(int channel=0;channel<3;channel++) for(int step=0;step<=16;step++)
            assertEquals(RgbFaceTint.interpolate(left,channel,1,step/16f),RgbFaceTint.interpolate(right,channel,0,step/16f),0.000001);
        assertEquals(left[0],RgbFaceTint.interpolate(left,0,0,0));
        assertEquals(left[9],RgbFaceTint.interpolate(left,0,1,1));
        assertEquals((left[0]+left[3]+left[6]+left[9])/4,RgbFaceTint.interpolate(left,0,0.5f,0.5f),0.000001);
    }
    private static int world(int u,int v) {
        int rgb=((u+3)*2)<<8 | (v+3)<<4 | ((u-v+5)&15);
        int peak=Math.max(rgb>>8,Math.max(rgb>>4&15,rgb&15));
        return RgbFaceTint.pack(rgb,peak,(u+v+5)&15,false);
    }
    @Test void missingNeighborsFallBackToCenterAndMissingCenterDisablesTint() {
        int[] samples=new int[9]; Arrays.fill(samples,-1); samples[4]=RgbFaceTint.pack(0xf00,15,0,false);
        float[] corners=new float[12]; RgbFaceTint.corners(samples,0,corners);
        for(int i=0;i<4;i++) { assertEquals(1,corners[i*3]); assertEquals(corners[1],corners[i*3+1]); }
        samples[4]=-1; RgbFaceTint.corners(samples,0,corners);
        for(float value:corners) assertEquals(1,value);
        assertThrows(IllegalArgumentException.class,()->RgbFaceTint.corners(new int[8],0,corners));
        assertThrows(IllegalArgumentException.class,()->RgbFaceTint.interpolate(corners,3,0,0));
        assertThrows(IllegalArgumentException.class,()->RgbFaceTint.pack(0x1000,0,0,false));
    }
}
