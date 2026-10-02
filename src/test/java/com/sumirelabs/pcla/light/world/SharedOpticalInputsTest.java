package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.engine.RgbBlockPropagator;
import com.sumirelabs.pcla.light.engine.RgbSkyPropagator;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedOpticalInputsTest {
    @Test void sparseAndDenseTicketPlansMatchBitmapPlansIncludingContextualCellsAndBoundaries() {
        var b=new CapturedRgbRegion.Bounds(0,0,0,1,16,1);var tokens=chunks(b);
        var store=new SharedOpticalInputs<Object>();
        var view=store.capture(List.of(full(b,tokens)),(chunk,x,y,z,value)->{air(value);value.contextual=x==0 && z==0 && y%17==0;}).get(0);
        var region=new CapturedRgbRegion(b,true);region.refreshShared(view,b.cells());
        var gate=new RegionPublication<Object,String>();gate.watch(Set.of(1L));gate.loaded(1,new Object());gate.publish(gate.begin(),"initial");
        for(int count:new int[]{1,32,4000}) {
            var bits=new BitSet();
            for(int i=0;i<count;i++) {int cell=count==4000?i:(b.cells()-1-i*1301);bits.set(cell);gate.changedAt(1,cell);}
            var ticket=gate.begin();
            assertArrayEquals(region.planShared(bits,false),region.planShared(ticket,false));
            assertArrayEquals(region.planShared(bits,true),region.planShared(ticket,true));
            gate.publish(ticket,"done");
        }
        gate.changedAt(1,b.cells());
        assertThrows(IndexOutOfBoundsException.class,()->region.planShared(gate.begin(),true));
        assertThrows(IndexOutOfBoundsException.class,()->region.planShared(gate.begin(),false));
    }
    private static Map<Long,Object> chunks(CapturedRgbRegion.Bounds... bounds) {
        Map<Long,Object> result=new HashMap<>(); for(var b:bounds) for(long key:b.chunks()) result.putIfAbsent(key,new Object()); return result;
    }
    private static SharedOpticalInputs.Request<Object> full(CapturedRgbRegion.Bounds b,Map<Long,Object> chunks) {
        return new SharedOpticalInputs.Request<>(b,chunks,IntStream.range(0,b.cells()).toArray());
    }
    private static void air(CapturedRgbRegion.Input value) {
        value.source=0; value.blockedFaces=0; value.contextual=false; Arrays.fill(value.incoming,0);
    }
    private static BitSet dirty(int cell) { BitSet bits=new BitSet(); bits.set(cell); return bits; }

    @Test void overlappingInitialCapturesReadEachWorldCellOnceAndUseUniformPages() {
        var a=new CapturedRgbRegion.Bounds(-16,0,-16); var b=new CapturedRgbRegion.Bounds(0,0,-16);
        var tokens=chunks(a,b); var store=new SharedOpticalInputs<Object>();
        var views=store.capture(List.of(full(a,tokens),full(b,tokens)),(chunk,x,y,z,value)->air(value));
        assertEquals(2L*a.cells()-30L*46*46,store.stats().worldReads());
        assertEquals(store.stats().pages(),store.stats().uniformPages()); assertEquals(0,store.stats().arrayPayloadBytes());
        for(int v=0;v<2;v++) for(int cell=0;cell<views.get(v).bounds().cells();cell++) {
            assertEquals(0,views.get(v).emission(cell)); for(int d=0;d<6;d++) assertEquals(0,views.get(v).incoming(cell,d));
        }
        store.retain(List.of()); assertEquals(0,store.stats().sections());
        assertEquals(0,views.get(0).emission(0),"retiring the store cannot mutate a retained view");
    }

    @Test void allOpticalFieldsAndNegativeCoordinatesMatchFreshUnsharedInputs() {
        var bounds=new CapturedRgbRegion.Bounds(-32,-16,-16,2,1,2); var tokens=chunks(bounds);
        SharedOpticalInputs.Reader<Object> reader=(chunk,x,y,z,value)->{
            value.source=Math.floorMod(x*31+y*7+z*17,4096); value.blockedFaces=Math.floorMod(x+y+z,64);
            value.contextual=(x&7)==0;
            for(int d=0;d<6;d++) value.incoming[d]=Math.floorMod(x+y*3+z*5+d*313,4096);
        };
        var store=new SharedOpticalInputs<Object>(); var next=store.capture(List.of(full(bounds,tokens)),reader).get(0);
        var shared=new CapturedRgbRegion(bounds,true); shared.refreshShared(next,bounds.cells());
        var direct=new CapturedRgbRegion(bounds);
        direct.refresh((cell,value)->reader.read(null,bounds.x()-15+cell%bounds.inputX(),bounds.y()-15+cell/(bounds.inputX()*bounds.inputZ()),
                bounds.z()-15+cell/bounds.inputX()%bounds.inputZ(),value),new BitSet(),true);
        for(int cell=0;cell<bounds.cells();cell++) {
            assertEquals(direct.emission(cell),shared.emission(cell));
            for(int d=0;d<6;d++) assertEquals(direct.absorption(cell,d),shared.absorption(cell,d),"cell="+cell+" direction="+d);
        }
    }

    @Test void overlappingBatchChangesAreAdoptedEvenOutsideAnIndividualPlanAndMatchRebuild() {
        var a=new CapturedRgbRegion.Bounds(0,0,0); var b=new CapturedRgbRegion.Bounds(16,0,0);
        var tokens=chunks(a,b); var store=new SharedOpticalInputs<Object>(); int[] source={0};
        SharedOpticalInputs.Reader<Object> reader=(chunk,x,y,z,value)->{
            air(value); if(x==8 && y==8 && z==8) value.source=source[0];
        };
        var initial=store.capture(List.of(full(a,tokens),full(b,tokens)),reader);
        var region=new CapturedRgbRegion(a,true); region.refreshShared(initial.get(0),a.cells());
        var engine=new RgbBlockPropagator(region); engine.relight(); var oldLight=region.interior();
        source[0]=0xf00;
        var other=new CapturedRgbRegion(b,true); other.refreshShared(initial.get(1),b.cells());
        int[] plan=other.planShared(dirty(b.inputCell(8,8,8)),false); long before=store.stats().worldReads();
        var changed=store.capture(List.of(new SharedOpticalInputs.Request<>(a,tokens,new int[0]),new SharedOpticalInputs.Request<>(b,tokens,plan)),reader);
        assertEquals(7,store.stats().worldReads()-before);
        var capture=region.refreshShared(changed.get(0),0); assertArrayEquals(new int[]{a.inputCell(8,8,8)},capture.changedCells());
        assertEquals(0,capture.opacityChangedCells().length); engine.update(capture.changedCells());
        var rebuilt=new CapturedRgbRegion(a,true); rebuilt.refreshShared(changed.get(0),a.cells()); new RgbBlockPropagator(rebuilt).relight();
        for(int i=0;i<a.outputCells();i++) { assertEquals(rebuilt.interior().get(i),region.interior().get(i)); assertEquals(0,oldLight.get(i)); }
        assertEquals(0,initial.get(0).emission(a.inputCell(8,8,8)),"old shared input is immutable");
    }

    @Test void contextualTagUpdatesDoNotQueuePropagationAndRetirementStopsResampling() {
        var b=new CapturedRgbRegion.Bounds(0,0,0); var tokens=chunks(b); var store=new SharedOpticalInputs<Object>();
        boolean[] contextual={true};
        SharedOpticalInputs.Reader<Object> reader=(chunk,x,y,z,value)->{air(value); value.contextual=contextual[0] && x==8 && y==8 && z==8;};
        var region=new CapturedRgbRegion(b,true); region.refreshShared(store.capture(List.of(full(b,tokens)),reader).get(0),b.cells());
        int[] plan=region.planShared(new BitSet(),false); assertArrayEquals(new int[]{b.inputCell(8,8,8)},plan);
        contextual[0]=false;
        var capture=region.refreshShared(store.capture(List.of(new SharedOpticalInputs.Request<>(b,tokens,plan)),reader).get(0),plan.length);
        assertEquals(0,capture.changedCells().length); assertEquals(0,capture.opacityChangedCells().length);
        assertEquals(0,region.planShared(new BitSet(),false).length);
    }

    @Test void failedReadAndPartialChunkReplacementCannotInstallPartialInput() {
        var b=new CapturedRgbRegion.Bounds(0,0,0); var tokens=chunks(b); var store=new SharedOpticalInputs<Object>();
        var first=store.capture(List.of(full(b,tokens)),(chunk,x,y,z,value)->{air(value);value.source=0xf00;}).get(0);
        var empty=new SharedOpticalInputs.Request<>(b,tokens,new int[0]);
        assertThrows(IllegalArgumentException.class,()->store.capture(List.of(full(b,tokens)),(chunk,x,y,z,value)->{
            air(value); if(x==8) value.incoming[5]=4096;
        }));
        assertEquals(0xf00,store.capture(List.of(empty),(chunk,x,y,z,value)->fail("no reads expected")).get(0).emission(0));
        var replacement=new HashMap<>(tokens); replacement.put(RegionPublication.key(0,0),new Object());
        assertThrows(IllegalStateException.class,()->store.capture(List.of(new SharedOpticalInputs.Request<>(b,replacement,new int[]{b.inputCell(8,8,8)})),
                (chunk,x,y,z,value)->air(value)));
        assertEquals(0xf00,store.capture(List.of(empty),(chunk,x,y,z,value)->fail("no reads expected")).get(0).emission(0));
        var replaced=store.capture(List.of(full(b,replacement)),(chunk,x,y,z,value)->air(value)).get(0);
        assertEquals(0,replaced.emission(0)); assertEquals(0xf00,first.emission(0));
        assertThrows(IllegalStateException.class,()->store.capture(List.of(empty,new SharedOpticalInputs.Request<>(b,replacement,new int[0])),(chunk,x,y,z,value)->air(value)));
    }

    @Test void extremeAlignedCoordinatesKeepTheLastSectionValid() {
        var b=new CapturedRgbRegion.Bounds(Integer.MAX_VALUE-31,0,Integer.MIN_VALUE+16); var tokens=chunks(b);
        var view=new SharedOpticalInputs<Object>().capture(List.of(full(b,tokens)),(chunk,x,y,z,value)->{air(value);value.source=0x123;}).get(0);
        assertEquals(0x123,view.emission(0)); assertEquals(0x123,view.emission(b.cells()-1));
        assertThrows(IndexOutOfBoundsException.class,()->view.emission(b.cells()));
    }

    @Test void sharedFilterChangesKeepBothLanesEqualToFreshUnsharedSolves() {
        var b=new CapturedRgbRegion.Bounds(0,0,0); var tokens=chunks(b); var store=new SharedOpticalInputs<Object>();
        int[] loss={0};
        SharedOpticalInputs.Reader<Object> reader=(chunk,x,y,z,value)->{
            air(value); if(x==5 && y==8 && z==8) value.source=0xf38;
            if(x==8 && y==12 && z==8) Arrays.fill(value.incoming,loss[0]);
        };
        var region=new CapturedRgbRegion(b,true); var sky=new CapturedSkyRegion(region,0);
        var blockEngine=new RgbBlockPropagator(region); var skyEngine=new RgbSkyPropagator(sky);
        sky.refreshColumns((column,out)->out.mask=0xfff,new BitSet(),true);
        boolean first=true;
        for(int filter:new int[]{0,0xeee,0x0ee,0xfff,0}) {
            loss[0]=filter; int[] plan=region.planShared(dirty(b.inputCell(8,12,8)),first);
            var capture=region.refreshShared(store.capture(List.of(new SharedOpticalInputs.Request<>(b,tokens,plan)),reader).get(0),plan.length);
            if(first) { blockEngine.relight(); skyEngine.relight(); first=false; }
            else { blockEngine.update(capture.changedCells()); skyEngine.update(capture.opacityChangedCells()); }
            var direct=new CapturedRgbRegion(b);
            direct.refresh((cell,out)->reader.read(null,b.x()-15+cell%b.inputX(),b.y()-15+cell/(b.inputX()*b.inputZ()),b.z()-15+cell/b.inputX()%b.inputZ(),out),new BitSet(),true);
            new RgbBlockPropagator(direct).relight(); var directSky=new CapturedSkyRegion(direct,0);
            directSky.refreshColumns((column,out)->out.mask=0xfff,new BitSet(),true); new RgbSkyPropagator(directSky).relight();
            for(int cell=0;cell<b.cells();cell++) {
                assertEquals(direct.light(cell),region.light(cell),"block filter="+filter+" cell="+cell);
                assertEquals(directSky.light(cell),sky.light(cell),"sky filter="+filter+" cell="+cell);
            }
        }
    }
    @Test void fullHeightSharedViewsMapEveryVerticalLayerAcrossSectionBoundaries() {
        var b=new CapturedRgbRegion.Bounds(-16,0,-16,1,16,1); var tokens=chunks(b);
        var view=new SharedOpticalInputs<Object>().capture(List.of(full(b,tokens)),(chunk,x,y,z,out)->{
            air(out); out.source=y&4095; out.blockedFaces=(x+y+z)&63;
            for(int d=0;d<6;d++) out.incoming[d]=(x^z^(y*(d+1)))&4095;
        }).get(0);
        for(int y=-15;y<=270;y++) for(int x:new int[]{-31,-16,-1,14}) for(int z:new int[]{-31,-16,-1,14}) {
            int cell=b.inputCell(x,y,z); assertEquals(y&4095,view.emission(cell)); assertEquals((x+y+z)&63,view.faces(cell));
            for(int d=0;d<6;d++) assertEquals((x^z^(y*(d+1)))&4095,view.incoming(cell,d));
        }
    }
}
