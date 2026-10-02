package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.client.DisplayTint;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisplayTintTest {
    @Test void packedCeleritasColorsMatchByteBufferColorsAndRetainEveryAlphaValue() {
        var random=new java.util.Random(20260913);
        var bytes=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        for(int alpha=0;alpha<256;alpha++) for(int trial=0;trial<10;trial++) {
            int color=alpha<<24|random.nextInt(1<<24);
            float r=random.nextFloat(),g=random.nextFloat(),b=random.nextFloat();
            bytes.putInt(0,color); DisplayTint.multiply(bytes,0,r,g,b);
            int packed=DisplayTint.multiplyAbgr(color,r,g,b);
            assertEquals(bytes.getInt(0),packed); assertEquals(alpha,packed>>>24);
            assertEquals(color,DisplayTint.multiplyAbgr(color,1,1,1));
        }
    }
    @Test void neutralLightIsUnchangedAndSunlightReducesSaturation() {
        for(int light=0;light<16;light++) for(int sky=0;sky<16;sky++) for(int shift:new int[]{0,4,8})
            assertEquals(1,DisplayTint.channel(light*0x111,shift,light,sky,0.75f));
        assertTrue(DisplayTint.channel(0xf00,0,15,0,0)<0.04f);
        assertEquals(1,DisplayTint.channel(0xf00,8,15,0,0));
        assertTrue(DisplayTint.channel(0xf00,0,15,15,1)>DisplayTint.channel(0xf00,0,15,0,0));
        assertTrue(DisplayTint.channel(0,0,15,0,0)<0.04f);
        assertThrows(IllegalArgumentException.class,()->DisplayTint.channel(0x1000,0,1,1,1));
        assertThrows(IllegalArgumentException.class,()->DisplayTint.channel(0,0,1,1,Float.NaN));
    }
    @Test void colorModificationPreservesAlphaPositionsAndLightmapBytesInBothByteOrders() {
        for(var order:new ByteOrder[]{ByteOrder.BIG_ENDIAN,ByteOrder.LITTLE_ENDIAN}) {
            var bytes=ByteBuffer.allocate(28).order(order);
            for(int i=0;i<28;i++) bytes.put(i,(byte)200);
            DisplayTint.multiply(bytes,12,1,0.5f,0);
            assertEquals(200,bytes.get(12)&255); assertEquals(100,bytes.get(13)&255); assertEquals(0,bytes.get(14)&255);
            for(int i=0;i<28;i++) if(i<12 || i>14) assertEquals(200,bytes.get(i)&255);
        }
    }
}
