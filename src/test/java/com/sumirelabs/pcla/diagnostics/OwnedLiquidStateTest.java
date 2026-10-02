package com.sumirelabs.pcla.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedLiquidStateTest {
    @Test void nativeVariantsOfTheSameFluidAndLevelRemainOwned() {
        for(String type:new String[]{"water","lava"})for(int level=0;level<16;level++) {
            String still="minecraft:"+type+"[level="+level+"]",flow="minecraft:flowing_"+type+"[level="+level+"]";
            assertTrue(OwnedLiquidState.matches(still,flow));assertTrue(OwnedLiquidState.matches(flow,still));assertTrue(OwnedLiquidState.matches(still,still));
        }
    }
    @Test void differentMaterialsLevelsAndForeignBlocksAreRetained() {
        assertFalse(OwnedLiquidState.matches("minecraft:water[level=0]","minecraft:lava[level=0]"));
        assertFalse(OwnedLiquidState.matches("minecraft:water[level=4]","minecraft:flowing_water[level=0]"));
        assertFalse(OwnedLiquidState.matches("minecraft:water[level=0]","minecraft:stone[variant=stone]"));
        assertFalse(OwnedLiquidState.matches("minecraft:water[level=0]","custom:water[level=0]"));
        assertFalse(OwnedLiquidState.matches("minecraft:water[level=16]","minecraft:water[level=16]"));
    }
}
