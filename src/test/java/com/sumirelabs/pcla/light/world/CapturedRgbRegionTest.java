package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.engine.RgbBlockPropagator;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedRgbRegionTest {
    private static final class Inputs implements CapturedRgbRegion.Reader {
        final int[] source, faces, loss;
        final BitSet contextual=new BitSet();
        int reads;
        Inputs(int cells) { source=new int[cells]; faces=new int[cells]; loss=new int[cells]; }
        public void read(int cell,CapturedRgbRegion.Input value) {
            reads++; value.source=source[cell]; value.blockedFaces=faces[cell];
            value.contextual=contextual.get(cell); Arrays.fill(value.incoming,loss[cell]);
        }
    }
    private static BitSet dirty(int...cells) { BitSet set=new BitSet(); for(int cell:cells)set.set(cell); return set; }

    @Test void unchangedStaticInputsAreSkippedAndDirtyNeighborhoodIsBounded() {
        var b=new CapturedRgbRegion.Bounds(0,0,0);
        var view=new CapturedRgbRegion(b); var inputs=new Inputs(b.cells());
        assertEquals(b.cells(),view.refresh(inputs,dirty(),false).sampledCells());
        assertEquals(0,view.refresh(inputs,dirty(),false).sampledCells());
        int center=b.inputCell(8,8,8); inputs.source[center]=0xf00;
        var update=view.refresh(inputs,dirty(center),false);
        assertEquals(7,update.sampledCells()); assertArrayEquals(new int[]{center},update.changedCells());
        assertEquals(0,view.refresh(inputs,dirty(center),false).changedCells().length);
        assertThrows(IndexOutOfBoundsException.class,()->view.refresh(inputs,dirty(b.cells()),false));
    }

    @Test void contextualValuesAreRefreshedEvenWhenAnotherCellTriggeredWork() {
        var b=new CapturedRgbRegion.Bounds(0,0,0);
        var view=new CapturedRgbRegion(b); var inputs=new Inputs(b.cells());
        int at=b.inputCell(1,1,1), other=b.inputCell(12,12,12);
        inputs.contextual.set(at); inputs.source[at]=0xf00;
        view.refresh(inputs,dirty(),true);
        inputs.source[at]=0x00f;
        var update=view.refresh(inputs,dirty(other),false);
        assertEquals(8,update.sampledCells()); assertArrayEquals(new int[]{at},update.changedCells());
        inputs.contextual.clear(at); view.refresh(inputs,dirty(at),false);
        assertEquals(7,view.refresh(inputs,dirty(other),false).sampledCells());
        inputs.contextual.set(other); view.refresh(inputs,dirty(other),false);
        assertEquals(1,view.refresh(inputs,dirty(),false).sampledCells());
    }

    @Test void incrementalInputsAndJointPublicationMatchFreshRebuildsAcrossEightSections() {
        var b=new CapturedRgbRegion.Bounds(-16,-16,-16,2,2,2);
        var view=new CapturedRgbRegion(b); var inputs=new Inputs(b.cells());
        var engine=new RgbBlockPropagator(view);
        int source=b.inputCell(-1,-1,-1), blue=b.inputCell(1,1,1), filter=b.inputCell(0,-1,-1);
        inputs.source[source]=0xf00; inputs.source[blue]=0x00f; inputs.contextual.set(blue);
        view.refresh(inputs,dirty(),true); engine.relight();
        var original=view.interior();
        int[] retained=new int[b.outputCells()]; for(int i=0;i<retained.length;i++)retained[i]=original.get(i);
        Random random=new Random(20260911);
        for(int step=0;step<12;step++) {
            inputs.source[source]=step%3==0 ? 0 : random.nextInt(4096);
            inputs.source[blue]=random.nextInt(4096); // Context update intentionally absent from dirty coordinates.
            inputs.loss[filter]=random.nextInt(4096); inputs.faces[filter]=step%2==0 ? 1 : 0;
            var capture=view.refresh(inputs,dirty(source,filter),false);
            assertTrue(capture.sampledCells()<20);
            engine.update(capture.changedCells());
            var actual=view.interior();
            var rebuilt=new CapturedRgbRegion(b);
            rebuilt.refresh(inputs,dirty(),true); new RgbBlockPropagator(rebuilt).relight();
            var expected=rebuilt.interior();
            for(int cell=0;cell<retained.length;cell++) {
                assertEquals(expected.get(cell),actual.get(cell),"step="+step+" cell="+cell);
                assertEquals(retained[cell],original.get(cell),"old root must be immutable");
            }
        }
        assertSame(view.interior(),view.interior(),"no full output scan/copy for no-op publication");
    }

    @Test void fullRefreshAfterChunkReplacementReconcilesUnnotifiedInputs() {
        var b=new CapturedRgbRegion.Bounds(0,0,0);
        var view=new CapturedRgbRegion(b); var inputs=new Inputs(b.cells());
        int cell=b.inputCell(8,8,8); inputs.source[cell]=0xfff;
        view.refresh(inputs,dirty(),true); var engine=new RgbBlockPropagator(view); engine.relight();
        var old=view.interior();
        inputs.source[cell]=0;
        var captured=view.refresh(inputs,dirty(),true); engine.update(captured.changedCells());
        var now=view.interior();
        assertEquals(b.cells(),captured.sampledCells());
        assertTrue(old.get(8+16*(8+16*8))>0);
        for(int i=0;i<now.cellCount();i++)assertEquals(0,now.get(i));
    }

    @Test void highLowAndEmptyRefreshesDoNotRetainPreviousSampledCells() {
        var b=new CapturedRgbRegion.Bounds(0,0,0,2,2,2);
        var view=new CapturedRgbRegion(b); var inputs=new Inputs(b.cells());
        view.refresh(inputs,dirty(),true);
        int last=b.cells()-1, stride=b.inputX()*b.inputZ();
        Set<Integer> seen=new HashSet<>();
        CapturedRgbRegion.Reader reader=(cell,value)->{assertTrue(seen.add(cell));inputs.read(cell,value);};
        inputs.source[last]=0xf00;
        var first=view.refresh(reader,dirty(last),false);
        assertEquals(Set.of(last,last-1,last-b.inputX(),last-stride),seen);
        assertArrayEquals(new int[]{last},first.changedCells());
        seen.clear(); inputs.source[0]=0x00f;
        var second=view.refresh(reader,dirty(0),false);
        assertEquals(Set.of(0,1,b.inputX(),stride),seen);
        assertArrayEquals(new int[]{0},second.changedCells());
        seen.clear(); assertEquals(0,view.refresh(reader,dirty(),false).sampledCells());
        assertTrue(seen.isEmpty());
        assertArrayEquals(new int[]{last},first.changedCells(),"later captures cannot mutate retained results");
    }

    @Test void fullRecaptureCanRetireAllContextualSamplesBeforeSparseWork() {
        var b=new CapturedRgbRegion.Bounds(0,0,0,2,2,2);
        var view=new CapturedRgbRegion(b); var inputs=new Inputs(b.cells());
        inputs.contextual.set(b.cells()-1); view.refresh(inputs,dirty(),true);
        assertEquals(1,view.refresh(inputs,dirty(),false).sampledCells());
        inputs.contextual.clear();
        assertEquals(b.cells(),view.refresh(inputs,dirty(),true).sampledCells());
        assertEquals(0,view.refresh(inputs,dirty(),false).sampledCells());
        assertEquals(4,view.refresh(inputs,dirty(0),false).sampledCells());
    }
}
