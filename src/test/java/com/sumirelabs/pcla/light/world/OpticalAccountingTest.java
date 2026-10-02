package com.sumirelabs.pcla.light.world;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpticalAccountingTest {
    @Test void compactFullRangeMatchesExplicitPlanAndKeepsSparsePlansDefensive() throws Exception {
        var b=new CapturedRgbRegion.Bounds(-16,0,-16,1,16,1);var tokens=chunks(b);
        SharedOpticalInputs.Reader<Object> reader=(c,x,y,z,out)->{
            uniform(c,x,y,z,out);out.source=(x*31+y*7+z*13)&4095;out.contextual=(y&7)==0;
            for(int d=0;d<6;d++)out.incoming[d]=(x+y+z+d*17)&4095;
        };
        var compactStore=new SharedOpticalInputs<Object>();var explicitStore=new SharedOpticalInputs<Object>();
        var compact=SharedOpticalInputs.Request.full(b,tokens,null);
        assertTrue(compact.fullRange());assertEquals(b.cells(),compact.selectedCellCount());
        var task=compactStore.beginCapture(List.of(compact),reader);
        while(!task.advance(137)){};
        var actual=task.result().getFirst();var expected=explicitStore.capture(List.of(full(b,tokens)),reader).getFirst();
        assertEquals(explicitStore.stats(),compactStore.stats());check(compactStore);
        assertEquals(b.cells(),task.selectedCells());assertEquals(b.cells(),task.readCells());
        for(int i=0;i<b.cells();i++) {
            assertEquals(expected.emission(i),actual.emission(i));assertEquals(expected.contextual(i),actual.contextual(i));
            for(int d=0;d<6;d++)assertEquals(expected.incoming(i,d),actual.incoming(i,d));
        }
        int[] sparse={3,5,9};var request=new SharedOpticalInputs.Request<>(b,tokens,sparse,actual);
        sparse[0]=99;int[] exported=request.cells();exported[0]=98;
        assertArrayEquals(new int[]{3,5,9},request.cells());assertEquals(3,request.selectedCellCount());
        assertThrows(IllegalArgumentException.class,()->new SharedOpticalInputs.Request<>(b,tokens,new int[]{1},null,true));
    }
    @Test void backendRequestPlanIncludesContextualCellsAndNeighborsAfterFullAdoption() {
        var b=A;var tokens=chunks(b);var gate=new RegionPublication<Object,String>();gate.watch(b.chunks());
        tokens.forEach(gate::loaded);var ticket=gate.begin();
        var region=new CapturedRgbRegion(b,true);var request=region.requestShared(ticket,false);
        assertTrue(request.fullRange());
        var store=new SharedOpticalInputs<Object>();
        var view=store.capture(List.of(request),(c,x,y,z,out)->{uniform(c,x,y,z,out);out.contextual=x==8 && y==8 && z==8;}).getFirst();
        region.refreshShared(view,request.selectedCellCount());gate.publish(ticket,"initial");
        gate.changedAt(RegionPublication.key(0,0),b.inputCell(10,8,8));ticket=gate.begin();
        var sparse=region.requestShared(ticket,false);assertFalse(sparse.fullRange());
        assertArrayEquals(region.planShared(ticket,false),sparse.cells());assertEquals(8,sparse.selectedCellCount());
        assertTrue(region.requestShared(ticket,true).fullRange());
        gate.changedAt(RegionPublication.key(0,0),b.cells());var invalid=gate.begin();
        assertThrows(IndexOutOfBoundsException.class,()->region.requestShared(invalid,true));
    }
    private static final CapturedRgbRegion.Bounds A=new CapturedRgbRegion.Bounds(0,0,0);
    private static final CapturedRgbRegion.Bounds B=new CapturedRgbRegion.Bounds(160,0,0);
    private static Map<Long,Object> chunks(CapturedRgbRegion.Bounds b) {
        var result=new HashMap<Long,Object>();for(long key:b.chunks())result.put(key,new Object());return result;
    }
    private static SharedOpticalInputs.Request<Object> full(CapturedRgbRegion.Bounds b,Map<Long,Object> chunks) {
        return new SharedOpticalInputs.Request<>(b,chunks,IntStream.range(0,b.cells()).toArray());
    }
    private static void uniform(Object chunk,int x,int y,int z,CapturedRgbRegion.Input value) {
        value.source=0;value.contextual=false;value.blockedFaces=0;Arrays.fill(value.incoming,0);
    }
    /** Count the actual committed pages independently of the reported accounting. */
    private static void check(SharedOpticalInputs<?> store) throws Exception {
        var field=SharedOpticalInputs.class.getDeclaredField("sections");field.setAccessible(true);
        var sections=(Map<?,?>)field.get(store);int pages=0,uniform=0;
        for(var section:sections.values()) {
            var pageField=section.getClass().getDeclaredField("pages");pageField.setAccessible(true);
            for(Object page:(Object[])pageField.get(section))if(page!=null) {
                pages++;var lowField=page.getClass().getDeclaredField("lows");lowField.setAccessible(true);
                if(lowField.get(page)==null)uniform++;
            }
        }
        var stats=store.stats();assertEquals(sections.size(),stats.sections());
        assertEquals(pages,stats.pages());assertEquals(uniform,stats.uniformPages());
        assertEquals(1024L*(pages-uniform),stats.arrayPayloadBytes());
    }
    @Test void countsFollowUniformMixedReplacementFailureAndRetirement() throws Exception {
        var store=new SharedOpticalInputs<Object>();var tokens=chunks(A);check(store);
        var original=store.capture(List.of(full(A,tokens)),OpticalAccountingTest::uniform).getFirst();check(store);
        var stats=store.stats();assertEquals(stats.pages(),stats.uniformPages());
        int cell=A.inputCell(8,8,8);
        for(int value:new int[]{0xf22,0,0x123,0}) {
            store.capture(List.of(new SharedOpticalInputs.Request<>(A,tokens,new int[]{cell})),(c,x,y,z,out)->{
                uniform(c,x,y,z,out);out.source=value;
            });check(store);
            assertEquals(value==0?0:1024,store.stats().arrayPayloadBytes());
        }
        assertEquals(0,original.emission(cell));
        assertThrows(IllegalStateException.class,()->store.capture(List.of(full(A,tokens)),(c,x,y,z,out)->{
            uniform(c,x,y,z,out);out.source=0xfff;if(x==8)throw new IllegalStateException("reader failed");
        }));check(store);assertEquals(stats.pages(),store.stats().pages());assertEquals(0,store.stats().arrayPayloadBytes());
        // Replacing the chunk identities replaces pages instead of adding duplicate counts.
        store.capture(List.of(full(A,chunks(A))),(c,x,y,z,out)->{uniform(c,x,y,z,out);out.source=x&4095;});
        check(store);assertEquals(stats.pages(),store.stats().pages());assertTrue(store.stats().arrayPayloadBytes()>0);
        store.retain(List.of());check(store);assertEquals(0,store.stats().pages());
    }
    @Test void sameRetentionAfterNewGeometryStillPrunesAndDeferredCancellationUsesLatestBounds() throws Exception {
        var store=new SharedOpticalInputs<Object>();var a=chunks(A);var b=chunks(B);
        store.capture(List.of(full(A,a)),OpticalAccountingTest::uniform);
        var keep=new ArrayList<>(List.of(A));store.retain(keep);var first=store.stats();
        store.capture(List.of(full(B,b)),OpticalAccountingTest::uniform);check(store);
        assertTrue(store.stats().sections()>first.sections());store.retain(keep);check(store);
        assertEquals(first.sections(),store.stats().sections());
        var task=store.beginCapture(List.of(full(B,b)),OpticalAccountingTest::uniform);
        assertFalse(task.advance(10));store.retain(List.of(B));store.retain(keep);
        task.cancel();check(store);assertEquals(first.sections(),store.stats().sections());
        // A retained caller-owned list must not silently change the cached policy.
        keep.clear();store.retain(keep);check(store);assertEquals(0,store.stats().sections());
    }
    @Test void stagingStatsStayCommittedAndDeferredPrunePreservesCompletedViews() throws Exception {
        var store=new SharedOpticalInputs<Object>();var a=chunks(A);var b=chunks(B);
        store.capture(List.of(full(A,a)),OpticalAccountingTest::uniform);var before=store.stats();
        var task=store.beginCapture(List.of(full(B,b)),OpticalAccountingTest::uniform);
        store.retain(List.of(A));int turns=0;
        while(!task.advance(1024)) {
            assertEquals(before.pages(),store.stats().pages());
            assertEquals(before.sections(),store.stats().sections());
            if(++turns%50==0)check(store);
        }
        check(store);assertEquals(before.pages(),store.stats().pages());
        assertEquals(0,task.result().getFirst().emission(B.inputCell(168,8,8)));
        store.retain(List.of());check(store);assertEquals(0,store.stats().pages());
    }
}
