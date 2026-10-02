package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.PackedRgb;
import com.sumirelabs.pcla.light.engine.RgbBlockAccess;
import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import java.util.*;

/** Bounded multi-section RGB field with a shared 15-cell halo. Only the interior is published. */
public final class CapturedRgbRegion implements RgbBlockAccess {
    public static final int MARGIN = 15;
    private static final int[] DX = {1,-1,0,0,0,0}, DY = {0,0,1,-1,0,0}, DZ = {0,0,0,0,1,-1};
    public record Bounds(int x, int y, int z, int sectionsX, int sectionsY, int sectionsZ) {
        public Bounds(int x, int y, int z) { this(x,y,z,1,1,1); }
        public Bounds {
            if ((x & 15) != 0 || (y & 15) != 0 || (z & 15) != 0) throw new IllegalArgumentException("Unaligned region");
            if (sectionsX < 1 || sectionsX > 2 || sectionsY < 1 || sectionsY > 16 || sectionsZ < 1 || sectionsZ > 2)
                throw new IllegalArgumentException("Supported calculation bounds: up to 2 x 16 x 2 sections");
            Math.subtractExact(x,MARGIN); Math.subtractExact(y,MARGIN); Math.subtractExact(z,MARGIN);
            Math.addExact(x,sectionsX*16+MARGIN); Math.addExact(y,sectionsY*16+MARGIN); Math.addExact(z,sectionsZ*16+MARGIN);
        }
        public int sizeX() { return sectionsX * 16; }
        public int sizeY() { return sectionsY * 16; }
        public int sizeZ() { return sectionsZ * 16; }
        public int inputX() { return sizeX()+2*MARGIN; }
        public int inputY() { return sizeY()+2*MARGIN; }
        public int inputZ() { return sizeZ()+2*MARGIN; }
        public int cells() { return inputX()*inputY()*inputZ(); }
        public int outputCells() { return sizeX()*sizeY()*sizeZ(); }
        public int inputColumns() { return inputX()*inputZ(); }
        public int inputTopY() { return y+sizeY()+MARGIN-1; }
        public boolean containsColumn(int bx,int bz) {
            return bx>=x-MARGIN && bx<x+sizeX()+MARGIN && bz>=z-MARGIN && bz<z+sizeZ()+MARGIN;
        }
        public int inputColumn(int bx,int bz) {
            if(!containsColumn(bx,bz)) throw new IndexOutOfBoundsException("Outside sky column footprint");
            return bx-x+MARGIN+inputX()*(bz-z+MARGIN);
        }
        public boolean containsInput(int bx, int by, int bz) {
            return bx >= x-MARGIN && bx < x+sizeX()+MARGIN && by >= y-MARGIN && by < y+sizeY()+MARGIN
                    && bz >= z-MARGIN && bz < z+sizeZ()+MARGIN;
        }
        public int inputCell(int bx, int by, int bz) {
            if (!containsInput(bx,by,bz)) throw new IndexOutOfBoundsException("Outside RGB input halo");
            return bx-x+MARGIN + inputX()*(bz-z+MARGIN + inputZ()*(by-y+MARGIN));
        }
        public int interiorIndex(int cell) {
            if (cell < 0 || cell >= outputCells()) throw new IndexOutOfBoundsException("Outside RGB output");
            return MARGIN+cell%sizeX() + inputX()*(MARGIN+cell/sizeX()%sizeZ() + inputZ()*(MARGIN+cell/(sizeX()*sizeZ())));
        }
        public Set<Long> chunks() {
            Set<Long> keys = new HashSet<>();
            for (int cz = (z-MARGIN)>>4; cz <= (z+sizeZ()+MARGIN-1)>>4; cz++)
                for (int cx = (x-MARGIN)>>4; cx <= (x+sizeX()+MARGIN-1)>>4; cx++) keys.add(RegionPublication.key(cx,cz));
            return keys;
        }
    }
    /** Scratch output reused across reads; a reader must fill every field. Directions use the core order. */
    public static final class Input {
        public int source, blockedFaces;
        public boolean contextual;
        public final int[] incoming = new int[6];
    }
    @FunctionalInterface public interface Reader { void read(int cell, Input output); }
    public record Capture(int sampledCells, int[] changedCells,int[] opacityChangedCells) {
        public Capture(int sampledCells,int[] changedCells) { this(sampledCells,changedCells,changedCells); }
    }
    public final Bounds bounds;
    private final int sizeX, sizeY, sizeZ;
    private final int worldStart,worldEnd;
    private final short[] sources, lights;
    private final byte[] faces;
    private final short[][] incoming;
    private BitSet contextual = new BitSet();
    private Adoption activeAdoption;
    // Worker-confined scratch. Never exposed through Capture or a published light root.
    private final BitSet sampled = new BitSet();
    private final RgbLightVolume output;
    private SharedOpticalInputs.View shared;
    private boolean initialized;

    public CapturedRgbRegion(Bounds bounds) { this(bounds,false); }
    public CapturedRgbRegion(Bounds bounds,boolean sharedInputs) { this(bounds,sharedInputs,Integer.MIN_VALUE,Integer.MAX_VALUE); }
    public CapturedRgbRegion(Bounds bounds,boolean sharedInputs,int minimumY,int maximumY) {
        if(maximumY<minimumY) throw new IllegalArgumentException("Invalid world height");
        this.bounds = bounds;
        sizeX=bounds.inputX(); sizeY=bounds.inputY(); sizeZ=bounds.inputZ();
        lights=new short[bounds.cells()];
        long base=(long)bounds.y()-MARGIN,plane=(long)sizeX*sizeZ;
        worldStart=(int)Math.max(0,Math.min(lights.length,((long)minimumY-base)*plane));
        worldEnd=(int)Math.max(0,Math.min(lights.length,((long)maximumY-base+1)*plane));
        sources=sharedInputs?null:new short[lights.length]; faces=sharedInputs?null:new byte[lights.length];
        incoming=sharedInputs?null:new short[6][lights.length];
        output=new RgbLightVolume(bounds.sizeX(),bounds.sizeY(),bounds.sizeZ());
    }

    /** Reread notified cells and neighbors plus contextual inputs; calls must not overlap or reenter. */
    public Capture refresh(Reader reader, BitSet dirty, boolean full) {
        if(sources==null) throw new IllegalStateException("Shared region requires a shared optical view");
        selectSamples(dirty,full);
        int sampledCount=sampled.cardinality();
        int[] changed=new int[sampledCount],opacityChanged=new int[changed.length]; int count=0,opacityCount=0;
        Input value=new Input();
        for (int cell=sampled.nextSetBit(0); cell>=0; cell=sampled.nextSetBit(cell+1)) {
            reader.read(cell,value);
            PackedRgb.validate(value.source);
            if ((value.blockedFaces & ~63)!=0) throw new IllegalArgumentException("Invalid RGB face bits");
            for (int d=0; d<6; d++) PackedRgb.validate(value.incoming[d]);
            boolean opacityDiffers=faces[cell]!=value.blockedFaces;
            boolean differs=sources[cell]!=value.source || opacityDiffers;
            sources[cell]=(short)value.source; faces[cell]=(byte)value.blockedFaces;
            contextual.set(cell,value.contextual);
            for (int d=0; d<6; d++) { opacityDiffers |= incoming[d][cell]!=value.incoming[d]; incoming[d][cell]=(short)value.incoming[d]; }
            differs|=opacityDiffers;
            if (differs) changed[count++]=cell;
            if(opacityDiffers) opacityChanged[opacityCount++]=cell;
        }
        initialized=true;
        return new Capture(sampledCount,Arrays.copyOf(changed,count),Arrays.copyOf(opacityChanged,opacityCount));
    }
    private void selectSamples(BitSet dirty,boolean full) {
        if (dirty.length() > cellCount()) throw new IndexOutOfBoundsException("Invalid dirty input");
        sampled.clear();
        if (full || !initialized) sampled.set(0,cellCount());
        else {
            sampled.or(contextual);
            for (int cell=dirty.nextSetBit(0); cell>=0; cell=dirty.nextSetBit(cell+1)) {
                sampled.set(cell);
                for (int d=0; d<6; d++) { int neighbor=neighbor(cell,d); if(neighbor>=0) sampled.set(neighbor); }
            }
        }
    }

    /** Worker-owned batch plan; callbacks never receive this array. */
    public int[] planShared(BitSet dirty,boolean full) {
        if(activeAdoption!=null)throw new IllegalStateException("Optical adoption is still active");
        if(sources!=null) throw new IllegalStateException("Not a shared-input region");
        selectSamples(dirty,full); return plannedSamples();
    }
    /** Visits a ticket without materializing its sparse edits as a large bitmap. */
    public <C> SharedOpticalInputs.Request<C> requestShared(RegionPublication.Ticket<C> ticket,boolean full) {
        if(activeAdoption!=null)throw new IllegalStateException("Optical adoption is still active");
        if(sources!=null)throw new IllegalStateException("Not a shared-input region");
        if(ticket.maximumDirtyCell()>=cellCount())throw new IndexOutOfBoundsException("Invalid dirty input");
        if(full || !initialized)return SharedOpticalInputs.Request.full(bounds,ticket.chunks(),shared);
        return new SharedOpticalInputs.Request<>(bounds,ticket.chunks(),planShared(ticket,false),shared);
    }
    public int[] planShared(RegionPublication.Ticket<?> ticket,boolean full) {
        if(activeAdoption!=null)throw new IllegalStateException("Optical adoption is still active");
        if(sources!=null) throw new IllegalStateException("Not a shared-input region");
        if(ticket.maximumDirtyCell()>=cellCount()) throw new IndexOutOfBoundsException("Invalid dirty input");
        sampled.clear();
        if(full || !initialized) sampled.set(0,cellCount());
        else {
            sampled.or(contextual);
            ticket.forEachDirty(cell->{
                sampled.set(cell);
                for(int d=0;d<6;d++) { int next=neighbor(cell,d);if(next>=0) sampled.set(next); }
            });
        }
        return plannedSamples();
    }
    private int[] plannedSamples() {
        int[] cells=new int[sampled.cardinality()]; int index=0;
        for(int cell=sampled.nextSetBit(0);cell>=0;cell=sampled.nextSetBit(cell+1)) cells[index++]=cell;
        return cells;
    }
    public Capture refreshShared(SharedOpticalInputs.View next,int sampledCount) {
        var task=beginRefreshShared(next,sampledCount);while(!task.advance(4096)){}return task.result();
    }
    public OpticalCheckpoint checkpoint() {
        if(shared==null || activeAdoption!=null)throw new IllegalStateException("No completed shared inputs");
        return new OpticalCheckpoint(shared,contextual);
    }
    public Adoption beginRefreshShared(SharedOpticalInputs.View next,int sampledCount) {
        if(activeAdoption!=null)throw new IllegalStateException("Optical adoption is already active");
        if(sources!=null || !bounds.equals(next.bounds())) throw new IllegalArgumentException("Wrong shared optical view");
        if(sampledCount<0)throw new IllegalArgumentException("Negative sample count");
        var task=new Adoption(next,sampledCount);activeAdoption=task;return task;
    }
    /** Private delta lists/contextual mask; old optical inputs remain usable until completion. */
    public final class Adoption {
        private SharedOpticalInputs.View next;
        private SharedOpticalInputs.View.DifferenceTask differences;
        private final int sampledCount;
        private it.unimi.dsi.fastutil.ints.IntArrayList changes,opacity;
        private BitSet nextContextual;
        private Capture result;
        private boolean cancelled;
        private Adoption(SharedOpticalInputs.View next,int sampledCount) {
            this.next=next;this.sampledCount=sampledCount;
            changes=new it.unimi.dsi.fastutil.ints.IntArrayList(Math.min(sampledCount,256));
            opacity=new it.unimi.dsi.fastutil.ints.IntArrayList(Math.min(sampledCount,256));
            differences=next.beginDifferences(shared,(cell,lighting,absorption,dynamic)->{
                if(nextContextual==null && contextual.get(cell)!=dynamic)nextContextual=(BitSet)contextual.clone();
                if(nextContextual!=null)nextContextual.set(cell,dynamic);
                if(lighting)changes.add(cell);if(absorption)opacity.add(cell);
            });
        }
        public Capture result() {if(result==null)throw new IllegalStateException("Optical adoption is not complete");return result;}
        public boolean advance(int maximumCells) {
            if(maximumCells<1)throw new IllegalArgumentException("Positive adoption budget required");
            if(cancelled)throw new IllegalStateException("Optical adoption was cancelled");
            if(result!=null)return true;
            try {
                if(!differences.advance(maximumCells))return false;
                var completed=new Capture(sampledCount,changes.toIntArray(),opacity.toIntArray());
                shared=next;if(nextContextual!=null)contextual=nextContextual;initialized=true;
                result=completed;activeAdoption=null;clear();return true;
            } catch(RuntimeException|Error failure){cancel();throw failure;}
        }
        public void cancel() {
            if(result!=null || cancelled)return;
            cancelled=true;differences.cancel();activeAdoption=null;clear();
        }
        private void clear(){next=null;differences=null;changes=opacity=null;nextContextual=null;}
    }
    public SharedOpticalInputs.View sharedInputs() { return shared; }

    public Capture refresh(World world, WorldHeightContext height, Map<Long,Chunk> chunks, BitSet dirty, boolean full) {
        var pos=new BlockPos.MutableBlockPos();
        return refresh((cell,value)-> {
            int x=bounds.x-MARGIN+cell%sizeX, z=bounds.z-MARGIN+cell/sizeX%sizeZ, y=bounds.y-MARGIN+cell/(sizeX*sizeZ);
            MinecraftRgbInputs.read(world,height,chunks,pos,x,y,z,value);
        },dirty,full);
    }
    public void capture(World world, WorldHeightContext height, Map<Long,Chunk> chunks) { refresh(world,height,chunks,new BitSet(),true); }
    public int cellCount() { return lights.length; }
    public int neighbor(int cell,int d) {
        int x=cell%sizeX+DX[d], z=cell/sizeX%sizeZ+DZ[d], y=cell/(sizeX*sizeZ)+DY[d];
        return x<0 || x>=sizeX || y<0 || y>=sizeY || z<0 || z>=sizeZ ? -1 : x+sizeX*(z+sizeZ*y);
    }
    public int emission(int cell) {
        Objects.checkIndex(cell,lights.length);
        return cell<worldStart || cell>=worldEnd ? 0 : sources==null?shared.emission(cell):sources[cell];
    }
    public int incomingAbsorption(int cell,int direction) { return incoming==null?shared.incoming(cell,direction):incoming[direction][cell]; }
    public int absorption(int cell,int d) {
        int target=neighbor(cell,d);
        if(target<0) return 0xfff;
        if(cell<worldStart || cell>=worldEnd || target<worldStart || target>=worldEnd) return 0xfff;
        int mask=faces==null?shared.faces(cell):faces[cell];
        return (mask&1<<d)!=0 ? 0xfff : incomingAbsorption(target,d^1);
    }
    public int light(int cell) { return lights[cell]; }
    public void light(int cell,int rgb) {
        PackedRgb.validate(rgb);
        if(lights[cell]==rgb) return;
        lights[cell]=(short)rgb;
        int x=cell%sizeX-MARGIN, z=cell/sizeX%sizeZ-MARGIN, y=cell/(sizeX*sizeZ)-MARGIN;
        if(x>=0 && x<bounds.sizeX() && y>=0 && y<bounds.sizeY() && z>=0 && z<bounds.sizeZ())
            output.set(x+bounds.sizeX()*(z+bounds.sizeZ()*y),rgb);
    }
    /** One root publishes all changed sections; halo values never escape through this API. */
    public RgbLightVolume.Snapshot interior() { return output.publish(); }
}
