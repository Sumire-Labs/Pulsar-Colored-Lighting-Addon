package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.engine.*;
import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvictedRgbStateTest {
    private record Pair(RgbLightVolume.Snapshot block,RgbLightVolume.Snapshot sky) {}
    private static final class State {
        final CapturedRgbRegion input;final RgbBlockPropagator block;final CapturedSkyRegion skyInput;final RgbSkyPropagator sky;
        State(CapturedRgbRegion.Bounds bounds) {
            input=new CapturedRgbRegion(bounds,true,0,255);block=new RgbBlockPropagator(input);
            skyInput=new CapturedSkyRegion(input,0,255);sky=new RgbSkyPropagator(skyInput);
        }
    }
    @Test void evictionRebuildMatchesFreshBothLaneResultsAndPreservesHeldPublications() {
        for(int limit:new int[]{1,2}) {
            var a=new CapturedRgbRegion.Bounds(0,0,0);var b=new CapturedRgbRegion.Bounds(16,0,0);
            var bounds=List.of(a,b);var chunks=new HashMap<Long,Object>();
            bounds.forEach(region->region.chunks().forEach(key->chunks.putIfAbsent(key,new Object())));
            var gates=new ArrayList<RegionPublication<Object,Pair>>();
            for(var region:bounds) {var gate=new RegionPublication<Object,Pair>();gate.watch(region.chunks());chunks.forEach(gate::loaded);gates.add(gate);}
            var cache=new WorkingSet<RegionPublication<Object,Pair>,State>(limit);var store=new SharedOpticalInputs<Object>();
            var held=new ArrayList<Pair>();var copies=new ArrayList<short[][]>();
            for(int phase=0;phase<4;phase++) {
                final int source=switch(phase){case 1->0xf00;case 2->0x00f;default->0;};final boolean filter=phase==2;
                SharedOpticalInputs.Reader<Object> reader=(chunk,x,y,z,out)->{
                    out.source=x==16&&y==8&&z==8?source:0;out.contextual=false;out.blockedFaces=0;
                    Arrays.fill(out.incoming,filter&&x==16&&y==12&&z==8?0x0ff:0);
                };
                for(int index=0;index<2;index++) {
                    var region=bounds.get(index);var gate=gates.get(index);
                    gate.changedAt(RegionPublication.key(1,0),region.inputCell(16,8,8));
                    gate.changedAt(RegionPublication.key(1,0),region.inputCell(16,12,8));
                    Pair before=gate.displaySnapshot();assertNull(gate.snapshot());
                    var ticket=gate.begin();var acquired=cache.acquire(gate,()->new State(region));var state=acquired.value();
                    assertSame(before,gate.displaySnapshot());
                    int[] plan=state.input.planShared(ticket,ticket.fullCapture()||acquired.created());
                    if(acquired.created())assertEquals(region.cells(),plan.length);
                    var view=store.capture(List.of(new SharedOpticalInputs.Request<>(region,chunks,plan,state.input.sharedInputs())),reader).get(0);
                    var capture=state.input.refreshShared(view,plan.length);
                    if(acquired.created())state.block.relight();else state.block.update(capture.changedCells());
                    var skyColumns=state.skyInput.refreshColumns((cell,out)->{out.mask=0xfff;out.contextual=false;},new BitSet(),acquired.created());
                    if(acquired.created())state.sky.relight();else state.sky.update(state.skyInput.changedRoots(capture.opacityChangedCells(),skyColumns.changedColumns()));
                    var pair=new Pair(state.input.interior(),state.skyInput.interior());assertTrue(gate.publish(ticket,pair));
                    var fresh=new CapturedRgbRegion(region,false,0,255);
                    fresh.refresh((cell,out)->reader.read(null,region.x()-15+cell%region.inputX(),region.y()-15+cell/(region.inputX()*region.inputZ()),region.z()-15+cell/region.inputX()%region.inputZ(),out),new BitSet(),true);
                    new RgbBlockPropagator(fresh).relight();var freshSky=new CapturedSkyRegion(fresh,0,255);
                    freshSky.refreshColumns((cell,out)->{out.mask=0xfff;out.contextual=false;},new BitSet(),true);new RgbSkyPropagator(freshSky).relight();
                    var expectedBlock=fresh.interior();var expectedSky=freshSky.interior();
                    for(int cell=0;cell<region.outputCells();cell++){assertEquals(expectedBlock.get(cell),pair.block.get(cell));assertEquals(expectedSky.get(cell),pair.sky.get(cell));}
                    short[][] copy={new short[region.outputCells()],new short[region.outputCells()]};pair.block.copyTo(copy[0]);pair.sky.copyTo(copy[1]);held.add(pair);copies.add(copy);
                }
            }
            assertEquals(limit==1?7:0,cache.stats().evictions());
            for(int i=0;i<held.size();i++){short[] data=new short[a.outputCells()];held.get(i).block.copyTo(data);assertArrayEquals(copies.get(i)[0],data);held.get(i).sky.copyTo(data);assertArrayEquals(copies.get(i)[1],data);}
        }
    }
}
