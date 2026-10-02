package com.sumirelabs.pcla.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;

class RgbLightmapTest {
    @Test void bakedEmissiveFacesKeepIndependentBlockAndSkyFloors() {
        int colored=RgbLightCoordinates.pair(0xf20,0x18f);
        assertEquals(colored,RgbLightCoordinates.withBakedFloor(colored,0));
        int lit=RgbLightCoordinates.withBakedFloor(colored,7*16 | 3*16<<16);
        assertEquals(15,RgbLightCoordinates.component(lit,0));
        assertEquals(7,RgbLightCoordinates.component(lit,1));
        assertEquals(7,RgbLightCoordinates.component(lit,2));
        assertEquals(3,RgbLightCoordinates.component(lit>>>16,0));
        assertEquals(8,RgbLightCoordinates.component(lit>>>16,1));
        assertEquals(15,RgbLightCoordinates.component(lit>>>16,2));
        int full=RgbLightCoordinates.withBakedFloor(colored,240 | 240<<16);
        assertEquals(RgbLightCoordinates.pair(0xfff,0xfff),full);
        int scalar=8<<4 | 12<<20;
        assertEquals(scalar,RgbLightCoordinates.withBakedFloor(scalar,240 | 240<<16));
    }
    @Test void everyRgbValueSurvivesSignedAndUnsignedShortCoordinates() {
        for(int rgb=0;rgb<4096;rgb++) {
            int lane=RgbLightCoordinates.lane(rgb),pair=RgbLightCoordinates.pair(rgb,rgb);
            assertTrue(RgbLightCoordinates.encoded(pair));assertTrue((short)lane<0);
            for(int c=0;c<3;c++) {
                int expected=rgb>>(8-c*4)&15;
                assertEquals(expected,RgbLightCoordinates.component(lane,c));
                assertEquals(expected,RgbLightCoordinates.component((short)lane,c));
                double shaderLane=((short)lane+65536)%32768;
                double actual=Math.floor(shaderLane/Math.pow(32,2-c))%32*0.5;
                assertEquals(expected,actual);
            }
        }
        for(int sky=0;sky<=240;sky++)for(int block=0;block<=240;block++)
            assertFalse(RgbLightCoordinates.encoded(block|sky<<16));
        assertThrows(IllegalArgumentException.class,()->RgbLightCoordinates.lane(Float.NaN,0,0));
    }
    @Test void halfLevelsRoundTripAndMissingLanesKeepScalarValues() {
        for(int r=0;r<=30;r++)for(int g=0;g<=30;g++)for(int b=0;b<=30;b++) {
            int lane=RgbLightCoordinates.lane(r*0.5f,g*0.5f,b*0.5f);
            assertEquals(r*0.5f,RgbLightCoordinates.component(lane,0));
            assertEquals(g*0.5f,RgbLightCoordinates.component(lane,1));
            assertEquals(b*0.5f,RgbLightCoordinates.component(lane,2));
        }
        int packed=RgbLightCoordinates.withFallback(-1,0xf00,7<<4|12<<20);
        for(int c=0;c<3;c++)assertEquals(7,RgbLightCoordinates.component(packed,c));
        assertEquals(15,RgbLightCoordinates.component(packed>>>16,0));
        assertEquals(0,RgbLightCoordinates.component(packed>>>16,1));
    }
    @Test void nightVisionNormalizesRgbTogetherAndGammaCanIncreaseBeyondNativeScalarChannel() {
        float[] raw=new float[768];
        for(int s=0;s<16;s++)for(int b=0;b<16;b++) {
            int index=(s*16+b)*3;
            raw[index]=raw[index+1]=raw[index+2]=0.02f+b*0.05f+s*0.01f;
        }
        var curve=new RgbLightmapCurve(raw,0,1);
        float[] result=curve.sample(new float[]{15,2,0},new float[]{0,0,0});
        assertEquals(0.99f,result[0],0.000001f);
        assertEquals((0.12f/0.77f)*0.96f+0.03f,result[1],0.000001f);
        assertEquals((0.02f/0.77f)*0.96f+0.03f,result[2],0.000001f);
        var gamma=new RgbLightmapCurve(raw,1,0);
        assertTrue(gamma.sample(new float[]{2,2,2},new float[]{0,0,0})[0]>new RgbLightmapCurve(raw,0,0).sample(new float[]{2,2,2},new float[]{0,0,0})[0]);
        Arrays.fill(raw,1);assertArrayEquals(result,curve.sample(new float[]{15,2,0},new float[]{0,0,0}));
    }
    @Test void cornersInterpolateLevelsAndRespectOccludedDiagonalsBeforeLightmapEvaluation() {
        int[] block=new int[9],sky=new int[9];Arrays.fill(block,RgbFaceTint.pack(0x842,8,15,false));Arrays.fill(sky,0x123);
        block[0]=RgbFaceTint.pack(0xfff,15,15,false);
        block[1]=block[3]=RgbFaceTint.pack(0,0,0,true);
        float[] b=new float[12],s=new float[12];RgbFaceTint.lightCorners(block,sky,b,s);
        assertArrayEquals(new float[]{8,4,2},Arrays.copyOf(b,3));
        assertArrayEquals(new float[]{1,2,3},Arrays.copyOf(s,3));
        Arrays.fill(sky,-1);RgbFaceTint.lightCorners(block,sky,b,s);
        assertArrayEquals(new float[]{15,15,15},Arrays.copyOf(s,3));
    }
}
