package com.sumirelabs.pcla.client;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisplayLightmapTest {
    private static int[] colors(boolean bright) {
        int[] pixels=new int[256];
        for(int sky=0;sky<16;sky++)for(int block=0;block<16;block++) {
            int level=Math.max(block,sky),v=bright?120+level*9:15+level*16;
            pixels[sky*16+block]=0xff000000|v<<16|(v-3)<<8|Math.max(1,v-7);
        }
        return pixels;
    }
    @Test void nativeArgbChannelsAndIndexAreCopiedAndValidated() {
        int[] pixels=new int[256];pixels[3*16+5]=0xff123456;var map=new DisplayLightmap(pixels);
        Arrays.fill(pixels,0);
        assertEquals(0x12/255f,map.value(5,3,8));assertEquals(0x34/255f,map.value(5,3,4));assertEquals(0x56/255f,map.value(5,3,0));
        assertThrows(IllegalArgumentException.class,()->new DisplayLightmap(new int[255]));
        assertThrows(IllegalArgumentException.class,()->map.value(16,0,8));
        assertThrows(IllegalArgumentException.class,()->map.value(0,0,1));
    }
    @Test void neutralAndMissingLanesStayNeutralForEveryLightPair() {
        for(boolean bright:new boolean[]{false,true}) {
            var map=new DisplayLightmap(colors(bright));
            for(int block=0;block<16;block++)for(int sky=0;sky<16;sky++)for(int shift:new int[]{0,4,8}) {
                assertEquals(1,DisplayTint.channel(block*0x111,sky*0x111,shift,block,sky,0,map));
                assertEquals(1,DisplayTint.channel(-1,-1,shift,block,sky,1,map));
            }
        }
    }
    @Test void nativeCurveChangesReachFlatFluidAndSmoothPaths() {
        var dark=new DisplayLightmap(colors(false));var bright=new DisplayLightmap(colors(true));
        float d=DisplayTint.channel(0xf00,0,4,15,0,0,dark),b=DisplayTint.channel(0xf00,0,4,15,0,0,bright);
        assertTrue(b>d);assertEquals((15-3f)/(255-3f),d,0.000001);
        for(var map:new DisplayLightmap[]{dark,bright}) {
            float[] fluid=new float[3];FluidLightTint.compute(0xf00,0xf00,0,0,-1,0,240,0,fluid,map);
            int[] field=new int[9],sky=new int[9];Arrays.fill(field,RgbFaceTint.pack(0xf00,15,0,false));
            float[] corners=new float[12];RgbFaceTint.corners(field,sky,0,corners,map);
            for(int component=0;component<3;component++) {
                float flat=DisplayTint.channel(0xf00,0,8-component*4,15,0,0,map);
                assertEquals(flat,fluid[component]);for(int i=0;i<4;i++)assertEquals(flat,corners[i*3+component],0.000001);
            }
        }
    }
    @Test void fullbrightAndBlackNativeTablesDoNotCreateNaNOrSpuriousTint() {
        for(int pixel:new int[]{0xff000000,0xffffffff}) {
            int[] pixels=new int[256];Arrays.fill(pixels,pixel);var map=new DisplayLightmap(pixels);
            for(int rgb=0;rgb<4096;rgb++)for(int shift:new int[]{0,4,8})assertEquals(1,DisplayTint.channel(rgb,0,shift,15,0,0,map));
        }
    }
    @Test void coloredSkylightUsesItsOwnChannelAndMissingSkyUsesScalar() {
        var map=new DisplayLightmap(colors(false));
        assertTrue(DisplayTint.channel(0,0x00f,8,0,15,1,map)<1);
        assertEquals(1,DisplayTint.channel(0,0x00f,0,0,15,1,map));
        assertEquals(1,DisplayTint.channel(0,-1,8,0,15,1,map));
    }
    @Test void changingInputsAreCoalescedButWorldResetPublishesImmediately() {
        var gate=new LightmapRefreshGate();var a=new LightmapRefreshGate.Key(3,0,0,0);var b=new LightmapRefreshGate.Key(3,1,0,0);
        assertTrue(gate.accept(a,0));for(int tick=1;tick<100;tick++)assertFalse(gate.accept(a,tick));
        assertTrue(gate.accept(b,100));assertFalse(gate.accept(a,101));assertTrue(gate.accept(a,110));
        gate.clear();assertTrue(gate.accept(b,111));
    }
}
