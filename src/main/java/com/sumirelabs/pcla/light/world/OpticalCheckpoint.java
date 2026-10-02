package com.sumirelabs.pcla.light.world;

import java.util.BitSet;

/** Immutable inputs of a completed column, without retaining its propagation queues/light halo. */
public final class OpticalCheckpoint {
    private final SharedOpticalInputs.View view;
    private final BitSet contextual;
    public OpticalCheckpoint(SharedOpticalInputs.View view,BitSet contextual) {
        this.view=java.util.Objects.requireNonNull(view);this.contextual=(BitSet)contextual.clone();
    }
    public Check check(RegionPublication.Ticket<?> ticket,CapturedRgbRegion.Reader reader) {
        if(ticket.fullCapture())throw new IllegalArgumentException("A full invalidation requires recapture");
        var b=view.bounds();if(ticket.maximumDirtyCell()>=b.cells())throw new IndexOutOfBoundsException("Dirty cell outside checkpoint");
        var selected=(BitSet)contextual.clone();int nx=b.inputX(),nz=b.inputZ(),plane=nx*nz;
        ticket.forEachDirty(cell->{
            selected.set(cell);int x=cell%nx,z=cell/nx%nz,y=cell/plane;
            if(x>0)selected.set(cell-1);if(x+1<nx)selected.set(cell+1);
            if(z>0)selected.set(cell-nx);if(z+1<nz)selected.set(cell+nx);
            if(y>0)selected.set(cell-plane);if(y+1<b.inputY())selected.set(cell+plane);
        });
        return new Check(selected,reader);
    }
    public final class Check {
        private final BitSet selected;
        private CapturedRgbRegion.Reader reader;
        private final CapturedRgbRegion.Input value=new CapturedRgbRegion.Input();
        private int cursor,reads;private boolean changed,done;
        private Check(BitSet selected,CapturedRgbRegion.Reader reader){this.selected=selected;this.reader=java.util.Objects.requireNonNull(reader);}
        public boolean advance(int maximumReads) {
            if(maximumReads<1)throw new IllegalArgumentException("Positive read budget required");
            if(done)return true;
            for(int i=0;i<maximumReads;i++) {
                int cell=selected.nextSetBit(cursor);
                if(cell<0){done=true;reader=null;return true;}
                cursor=cell+1;reader.read(cell,value);reads++;
                changed=value.source!=view.emission(cell) || value.blockedFaces!=view.faces(cell) || value.contextual!=view.contextual(cell);
                for(int direction=0;direction<6;direction++)changed|=value.incoming[direction]!=view.incoming(cell,direction);
                if(changed){done=true;reader=null;return true;}
            }
            return false;
        }
        public boolean unchanged(){if(!done)throw new IllegalStateException("Input check incomplete");return !changed;}
        public int reads(){return reads;}
    }
}
