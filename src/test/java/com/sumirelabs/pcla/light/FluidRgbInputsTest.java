package com.sumirelabs.pcla.light;

import com.sumirelabs.pcla.compat.FluidRgbInputs;
import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidRgbInputsTest {
    @Test void neutralChannelsMatchPulsarMaxOpacityAndEmission() {
        for(int host=0;host<16;host++)for(int fluid=0;fluid<16;fluid++) {
            var value=new CapturedRgbRegion.Input();value.source=host*0x111;value.blockedFaces=63;
            FluidRgbInputs.merge(value,fluid*0x111,host*0x111,fluid*0x111,false);
            int expected=Math.max(host,fluid)*0x111;
            assertEquals(expected,value.source);assertEquals(0,value.blockedFaces);
            for(int direction=0;direction<6;direction++)assertEquals(expected,value.incoming[direction]);
        }
    }
    @Test void storedFluidKeepsItsOwnEmissionColorAndCombinesFiltersPerChannel() {
        var value=new CapturedRgbRegion.Input();value.source=0xf20;value.blockedFaces=4;
        FluidRgbInputs.merge(value,0x03e,0x117,0x531,true);
        assertEquals(0xf3e,value.source);assertTrue(value.contextual);assertEquals(0,value.blockedFaces);
        for(int direction=0;direction<6;direction++)assertEquals(0x537,value.incoming[direction]);
        assertEquals(0xac8,PackedRgb.attenuateBlock(0xfff,value.incoming[0]));
    }
    @Test void hostContextualFlagAndOpaqueHostArePreserved() {
        var value=new CapturedRgbRegion.Input();value.source=0x777;value.contextual=true;
        FluidRgbInputs.merge(value,0xf72,0xfff,0x333,false);
        assertEquals(0xf77,value.source);assertTrue(value.contextual);
        for(int absorption:value.incoming)assertEquals(0xfff,absorption);
    }
}
