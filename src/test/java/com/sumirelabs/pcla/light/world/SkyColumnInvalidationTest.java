package com.sumirelabs.pcla.light.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkyColumnInvalidationTest {
    private static final CapturedRgbRegion.Bounds BOUNDS=new CapturedRgbRegion.Bounds(-16,32,-16,2,2,2);
    private static RegionDirectory<Object,String> directory(boolean sky) {
        var directory=new RegionDirectory<Object,String>(sky); directory.replace(0,BOUNDS);
        for(long key:BOUNDS.chunks()) directory.loaded(key,new Object());
        var gate=directory.get(0).gate(); assertTrue(gate.publish(gate.begin(),"old")); return directory;
    }

    @Test void farAboveChangesInvalidateSkyBoundariesWithoutForcingBlockInputRecapture() {
        var directory=directory(true); var gate=directory.get(0).gate(); var old=gate.begin();
        assertTrue(directory.containsInput(-1,200,-1));
        directory.changedAt(-1,200,-1);
        assertNull(gate.snapshot()); assertEquals("old",gate.displaySnapshot());
        var ticket=gate.begin(); assertFalse(ticket.fullCapture()); assertTrue(ticket.dirtyCells().isEmpty());
        int column=BOUNDS.inputColumn(-1,-1);
        assertEquals(1,ticket.dirtySkyColumns().cardinality()); assertTrue(ticket.dirtySkyColumns().get(column));
        ticket.dirtySkyColumns().clear(); assertTrue(ticket.dirtySkyColumns().get(column));
        assertFalse(gate.publish(old,"stale"));
        directory.changedAt(0,210,-1); assertFalse(gate.publish(ticket,"also stale"));
        var merged=gate.begin(); assertEquals(2,merged.dirtySkyColumns().cardinality());
        assertTrue(gate.publish(merged,"new")); assertTrue(gate.begin().dirtySkyColumns().isEmpty());
    }

    @Test void topCellNotifiesCellAndBoundaryInOneRevisionAndOtherScopesIgnoreIt() {
        var sky=directory(true); var gate=sky.get(0).gate(); long before=gate.revision();
        sky.changedAt(-1,BOUNDS.inputTopY(),-1); var ticket=gate.begin();
        assertEquals(before+1,ticket.revision()); assertEquals(1,ticket.dirtyCells().cardinality()); assertEquals(1,ticket.dirtySkyColumns().cardinality());
        long current=gate.revision(); sky.changedAt(BOUNDS.x()+BOUNDS.sizeX()+CapturedRgbRegion.MARGIN,200,-1);
        assertEquals(current,gate.revision());
        var block=directory(false); assertFalse(block.containsInput(-1,200,-1)); block.changedAt(-1,200,-1);
        assertEquals("old",block.get(0).gate().snapshot());
    }

    @Test void chunkReplacementRetiresTheColumnTicketAndDisplay() {
        var directory=directory(true); var gate=directory.get(0).gate(); directory.changedAt(-1,200,-1);
        var old=gate.begin(); directory.loaded(RegionPublication.key(-1,-1),new Object());
        assertFalse(gate.publish(old,"stale")); assertNull(gate.displaySnapshot());
        var replacement=gate.begin(); assertTrue(replacement.fullCapture()); assertTrue(replacement.dirtySkyColumns().isEmpty());
        directory.close(); assertNull(gate.begin()); assertFalse(gate.publish(replacement,"late"));
    }
}
