package com.sumirelabs.pcla.light.world;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionPublicationTest {
    @Test void aPausedJobMayFinishOldInputsButCannotPublishAfterAnEditOrKeepReplacedChunks() {
        var gate=new RegionPublication<Object,String>();gate.watch(Set.of(1L));gate.loaded(1,new Object());gate.publish(gate.begin(),"old display");
        gate.changedAt(1,15);var paused=gate.begin();gate.changedAt(1,29);
        assertTrue(gate.ownsChunks(paused));assertFalse(gate.publish(paused,"stale solve"));assertEquals("old display",gate.displaySnapshot());
        assertTrue(gate.publish(gate.begin(),"reconciled"));gate.loaded(1,new Object());assertFalse(gate.ownsChunks(paused));
        var replacement=gate.begin();gate.close();assertFalse(gate.ownsChunks(replacement));
    }
    private static java.util.BitSet visited(RegionPublication.Ticket<?> ticket) {
        var result=new java.util.BitSet();int[] previous={-1};
        ticket.forEachDirty(cell->{ assertTrue(cell>previous[0],"Unique ascending visitation");result.set(cell);previous[0]=cell; });
        assertEquals(previous[0],ticket.maximumDirtyCell());return result;
    }
    @Test void sparseDenseAndRepeatedBatchesKeepImmutableTicketsAndPendingUnion() {
        var gate=new RegionPublication<Object,String>();gate.watch(Set.of(1L));gate.loaded(1,new Object());gate.publish(gate.begin(),"initial");
        var random=new java.util.Random(20260914); var retained=new java.util.ArrayList<RegionPublication.Ticket<Object>>();
        var expectedRoots=new java.util.ArrayList<java.util.BitSet>();
        for(int round=0;round<12;round++) {
            var expected=new java.util.BitSet();
            int count=round%3==0?4000:round%3==1?1:32;
            for(int i=0;i<count;i++) { int cell=count==4000?i:random.nextInt(605176);gate.changedAt(1,cell);gate.changedAt(1,cell);expected.set(cell); }
            var stale=gate.begin();retained.add(stale);expectedRoots.add((java.util.BitSet)expected.clone());
            assertEquals(expected,visited(stale));
            gate.changedAt(1,600001);expected.set(600001);
            assertFalse(gate.publish(stale,"stale"));
            var current=gate.begin();assertEquals(expected,visited(current));
            current.dirtyCells().clear();assertEquals(expected,visited(current));
            assertTrue(gate.publish(current,"current"));assertTrue(visited(gate.begin()).isEmpty());
        }
        for(int i=0;i<retained.size();i++) assertEquals(expectedRoots.get(i),visited(retained.get(i)));
    }
    @Test void visitationCanFailOrReenterWithoutMutatingTicketOrDroppingNewEdits() {
        var gate=new RegionPublication<Object,String>();gate.watch(Set.of(1L));gate.loaded(1,new Object());gate.publish(gate.begin(),"initial");
        gate.changedAt(1,10);gate.changedAt(1,572401);var ticket=gate.begin();
        assertThrows(IllegalStateException.class,()->ticket.forEachDirty(cell->{gate.changedAt(1,20);throw new IllegalStateException("reader failed");}));
        assertEquals(2,visited(ticket).cardinality());assertFalse(gate.publish(ticket,"stale"));
        assertEquals(3,visited(gate.begin()).cardinality());
        gate.changed(1);assertTrue(gate.begin().fullCapture());assertEquals(-1,gate.begin().maximumDirtyCell());
        assertEquals(2,visited(ticket).cardinality());
    }
    @Test void highSparseIndexIsVisitedOnceAfterTicketCreation() {
        var gate=new RegionPublication<Object,String>();gate.watch(Set.of(1L));gate.loaded(1,new Object());gate.publish(gate.begin(),"initial");
        gate.changedAt(1,605175);var ticket=gate.begin();
        assertEquals(605175,ticket.maximumDirtyCell());int[] count={0};
        ticket.forEachDirty(cell->{assertEquals(605175,cell);count[0]++;});assertEquals(1,count[0]);
    }
    @Test void compatibilityConstructorsDefensivelyCopyBothLanesAndKeepValueEquality() {
        var cells=new java.util.BitSet();cells.set(3);var columns=new java.util.BitSet();columns.set(8);
        var chunks=new java.util.HashMap<Long,Object>();chunks.put(1L,new Object());
        var a=new RegionPublication.Ticket<>(2,chunks,false,cells,columns);
        var b=new RegionPublication.Ticket<>(2,chunks,false,cells,columns);
        cells.clear();columns.clear();chunks.clear();a.dirtySkyColumns().clear();
        assertTrue(a.dirtySkyColumns().get(8));assertTrue(visited(a).get(3));
        assertEquals(a,b);assertEquals(a.hashCode(),b.hashCode());assertTrue(a.toString().contains("revision=2"));
    }
    @Test void displayRetainsLastCompletedRootDuringEditsButDoesNotClaimCompletion() {
        var gate=new RegionPublication<Object,String>();
        gate.watch(Set.of(1L)); gate.loaded(1,new Object());
        gate.publish(gate.begin(),"old");
        gate.changedAt(1,4);
        assertNull(gate.snapshot()); assertEquals("old",gate.displaySnapshot());
        var rejected=gate.begin(); gate.changedAt(1,5);
        assertFalse(gate.publish(rejected,"incomplete"));
        assertEquals("old",gate.displaySnapshot());
        gate.changed(1); // Coarse relight still has the same chunk ownership.
        assertNull(gate.snapshot()); assertEquals("old",gate.displaySnapshot());
        assertTrue(gate.publish(gate.begin(),"new"));
        assertEquals("new",gate.snapshot()); assertEquals("new",gate.displaySnapshot());
    }
    @Test void everyLifecycleRetirementAndFailureClearsDisplayAndRejectsLateWork() {
        for(int scenario=0;scenario<5;scenario++) {
            var gate=new RegionPublication<Object,String>();
            gate.watch(Set.of(1L)); gate.loaded(1,new Object()); gate.publish(gate.begin(),"old");
            gate.changedAt(1,4); var late=gate.begin();
            switch(scenario) {
                case 0 -> gate.unloaded(1);
                case 1 -> gate.loaded(1,new Object());
                case 2 -> gate.watch(Set.of(1L));
                case 3 -> gate.failed();
                default -> gate.close();
            }
            assertNull(gate.snapshot()); assertNull(gate.displaySnapshot());
            assertFalse(gate.publish(late,"late"));
            assertNull(gate.displaySnapshot());
        }
    }
    @Test void rejectedBatchesRetainEveryDirtyCellAndTicketsCannotBeMutated() {
        var gate = new RegionPublication<Object,String>();
        gate.watch(Set.of(1L)); gate.loaded(1,new Object());
        assertTrue(gate.begin().fullCapture());
        assertTrue(gate.publish(gate.begin(),"initial"));
        gate.changedAt(1,10);
        var first=gate.begin();
        assertFalse(first.fullCapture());
        first.dirtyCells().clear();
        assertTrue(first.dirtyCells().get(10));
        gate.changedAt(1,20);
        assertFalse(gate.publish(first,"stale"));
        var second=gate.begin();
        assertEquals(2,second.dirtyCells().cardinality());
        assertTrue(second.dirtyCells().get(10));
        assertTrue(second.dirtyCells().get(20));
        assertTrue(gate.publish(second,"current"));
        assertTrue(gate.begin().dirtyCells().isEmpty());
        gate.changedAt(1,30);
        gate.changed(1);
        assertTrue(gate.begin().fullCapture());
        assertThrows(IllegalArgumentException.class,()->gate.changedAt(1,-1));
    }
    @Test void missingNeighborsPreventPublicationAndUnloadsInvalidateHeldWork() {
        var gate = new RegionPublication<Object, String>();
        gate.watch(Set.of(1L, 2L));
        gate.loaded(1, new Object());
        assertNull(gate.begin());
        gate.loaded(2, new Object());
        var old = gate.begin();
        assertTrue(gate.publish(old, "ready"));
        gate.unloaded(2);
        assertNull(gate.snapshot());
        assertFalse(gate.publish(old, "stale"));
        gate.loaded(2, new Object());
        assertFalse(gate.publish(old, "replaced"));
        assertTrue(gate.publish(gate.begin(), "new"));
    }

    @Test void queuedEditsAndRegionChangesRejectInFlightResults() {
        var gate = new RegionPublication<Object, String>();
        gate.watch(Set.of(1L)); gate.loaded(1, new Object());
        var old = gate.begin();
        gate.changed(9);
        assertTrue(gate.publish(old, "unrelated edit"));
        gate.changed(1);
        assertNull(gate.snapshot());
        assertFalse(gate.publish(old, "old edit"));
        old = gate.begin();
        gate.watch(Set.of(1L));
        assertFalse(gate.publish(old, "previous region"));
        old = gate.begin();
        gate.close();
        assertFalse(gate.publish(old, "late worker"));
        assertNull(gate.begin());
        assertThrows(IllegalStateException.class, () -> gate.watch(Set.of(1L)));
    }

    @Test void sameChunkRegistrationDoesNotInvalidateButIdentityReplacementDoes() {
        var gate = new RegionPublication<Object, String>();
        Object chunk = new Object();
        gate.watch(Set.of(1L)); gate.loaded(1, chunk);
        var ticket = gate.begin();
        gate.loaded(1, chunk);
        assertTrue(gate.publish(ticket, "same"));
        gate.loaded(1, new Object());
        assertFalse(gate.publish(ticket, "different"));
        assertNull(gate.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> ticket.chunks().clear());
        assertNotEquals(RegionPublication.key(-1, 0), RegionPublication.key(0, -1));
    }
}
