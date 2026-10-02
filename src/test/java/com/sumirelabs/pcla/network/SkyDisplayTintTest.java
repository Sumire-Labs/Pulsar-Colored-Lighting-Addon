package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.client.DisplayTint;
import com.sumirelabs.pcla.client.RgbFaceTint;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SkyDisplayTintTest {
    @Test void neutralAndMissingLanesKeepOrdinaryColor() {
        for(int block=0;block<16;block++) for(int sky=0;sky<16;sky++) for(int shift:new int[]{0,4,8}) {
            assertEquals(1,DisplayTint.channel(-1,-1,shift,block,sky,0.5f),0.000001);
            assertEquals(1,DisplayTint.channel(block*0x111,sky*0x111,shift,block,sky,1),0.000001);
        }
    }
    @Test void daylightCombinesBothColorsAndZeroDaylightRemovesSkyContribution() {
        float red=DisplayTint.channel(0xf00,0x00f,8,15,15,1);
        float blue=DisplayTint.channel(0xf00,0x00f,0,15,15,1);
        float green=DisplayTint.channel(0xf00,0x00f,4,15,15,1);
        assertEquals(red,blue,0.000001); assertTrue(red>0.5f && red<0.52f); assertTrue(green<0.02f);
        for(int shift:new int[]{0,4,8}) assertEquals(DisplayTint.channel(0xf00,shift,15,15,0),DisplayTint.channel(0xf00,0x00f,shift,15,15,0));
    }
    @Test void skyOnlySamplesTintCornersAndOpaqueNeighborsUseTheMatchingCenterSky() {
        int[] samples=new int[9],sky=new int[9];
        Arrays.fill(samples,RgbFaceTint.pack(0,0,15,false)); Arrays.fill(sky,0x00f);
        float[] corners=new float[12]; RgbFaceTint.corners(samples,sky,1,corners);
        for(int corner=0;corner<4;corner++) {
            assertTrue(corners[corner*3]<0.04f); assertTrue(corners[corner*3+1]<0.04f); assertEquals(1,corners[corner*3+2]);
        }
        samples[3]|=RgbFaceTint.OPAQUE; samples[1]|=RgbFaceTint.OPAQUE;
        sky[0]=0xf00; sky[1]=0xf00; sky[3]=0xf00;
        RgbFaceTint.corners(samples,sky,1,corners);
        assertTrue(corners[0]<0.04f); assertEquals(1,corners[2]);
    }
    @Test void absentSkyCornerDataMatchesLegacyAndInvalidColorsAreRejected() {
        int[] samples=new int[9],sky=new int[9]; Arrays.fill(samples,RgbFaceTint.pack(0xf30,12,9,false)); Arrays.fill(sky,-1);
        float[] legacy=new float[12],dual=new float[12];
        RgbFaceTint.corners(samples,0.5f,legacy); RgbFaceTint.corners(samples,sky,0.5f,dual); assertArrayEquals(legacy,dual);
        assertThrows(IllegalArgumentException.class,()->DisplayTint.channel(0,0x1000,0,0,0,1));
        sky[0]=0x1000; assertThrows(IllegalArgumentException.class,()->RgbFaceTint.corners(samples,sky,1,dual));
    }
}
