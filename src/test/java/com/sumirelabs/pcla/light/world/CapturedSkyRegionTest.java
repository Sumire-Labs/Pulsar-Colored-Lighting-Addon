package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.engine.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CapturedSkyRegionTest {
    @Test void skyUsesSeparateStorageAndNeverPropagatesThroughCellsBelowWorldMinimum() {
        var bounds=new CapturedRgbRegion.Bounds(0,0,0);
        var input=new CapturedRgbRegion(bounds); int source=bounds.inputCell(8,8,8);
        input.refresh((cell,out)-> { out.source=cell==source ? 0xf00 : 0; out.blockedFaces=0; out.contextual=false; Arrays.fill(out.incoming,0); },new BitSet(),true);
        new RgbBlockPropagator(input).relight(); var block=input.interior(); int[] blockValues=new int[block.cellCount()];
        for(int i=0;i<blockValues.length;i++) blockValues[i]=block.get(i);
        var sky=new CapturedSkyRegion(input,0); sky.refreshColumns((column,out)->out.mask=0xfff,new BitSet(),true);
        var engine=new RgbSkyPropagator(sky); engine.relight(); var white=sky.interior();
        for(int i=0;i<white.cellCount();i++) assertEquals(0xfff,white.get(i));
        assertEquals(0,sky.light(bounds.inputCell(8,-1,8)));
        var columns=sky.refreshColumns((column,out)->out.mask=0xf00,new BitSet(),true);
        engine.update(sky.changedRoots(new int[0],columns.changedColumns())); var red=sky.interior();
        for(int i=0;i<red.cellCount();i++) {
            assertEquals(0xf00,red.get(i)); assertEquals(0xfff,white.get(i)); assertEquals(blockValues[i],input.interior().get(i));
        }
    }

    @Test void emissionOnlyChangesDoNotInvalidateSkyGeometry() {
        var bounds=new CapturedRgbRegion.Bounds(0,0,0); var input=new CapturedRgbRegion(bounds);
        int at=bounds.inputCell(8,8,8); int[] emission={0},opacity={0};
        CapturedRgbRegion.Reader reader=(cell,out)-> { out.source=cell==at ? emission[0] : 0; out.blockedFaces=0; out.contextual=false; Arrays.fill(out.incoming,cell==at ? opacity[0] : 0); };
        input.refresh(reader,new BitSet(),true); var dirty=new BitSet(); dirty.set(at);
        emission[0]=0xf00; var sourceChange=input.refresh(reader,dirty,false);
        assertArrayEquals(new int[]{at},sourceChange.changedCells()); assertEquals(0,sourceChange.opacityChangedCells().length);
        opacity[0]=0x0ee; var filterChange=input.refresh(reader,dirty,false);
        assertArrayEquals(new int[]{at},filterChange.changedCells()); assertArrayEquals(new int[]{at},filterChange.opacityChangedCells());
    }

    @Test void preservingRawZeroOpacityDoesNotChangeBlockLightMinimumAttenuation() {
        var bounds=new CapturedRgbRegion.Bounds(0,0,0); int source=bounds.inputCell(8,8,8),filter=bounds.inputCell(8,8,9);
        var raw=new CapturedRgbRegion(bounds); var clamped=new CapturedRgbRegion(bounds);
        raw.refresh((cell,out)-> { out.source=cell==source ? 0xf83 : 0; out.blockedFaces=0; out.contextual=false; Arrays.fill(out.incoming,cell==filter ? 0x008 : 0); },new BitSet(),true);
        clamped.refresh((cell,out)-> { out.source=cell==source ? 0xf83 : 0; out.blockedFaces=0; out.contextual=false; Arrays.fill(out.incoming,cell==filter ? 0x118 : 0x111); },new BitSet(),true);
        new RgbBlockPropagator(raw).relight(); new RgbBlockPropagator(clamped).relight();
        for(int cell=0;cell<raw.cellCount();cell++) assertEquals(clamped.light(cell),raw.light(cell),"cell "+cell);
    }
}
