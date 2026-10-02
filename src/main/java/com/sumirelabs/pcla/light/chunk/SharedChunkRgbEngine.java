package com.sumirelabs.pcla.light.chunk;

import com.sumirelabs.pcla.light.PackedRgb;
import com.sumirelabs.pcla.light.engine.*;
import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import com.sumirelabs.pcla.light.engine.PackedWordRgb;
import java.util.*;

/**
 * Worker-confined world RGB field. Every loaded chunk has one pair of light volumes;
 * propagation crosses their actual shared edges rather than recomputing owner halos.
 * Callers capture immutable optical inputs before beginning work. Only settled roots
 * may be published; a failed operation requires discarding this engine instance.
 */
public final class SharedChunkRgbEngine {
    public static final class Input {
        public int emission, blockedFaces;
        public final int[] incoming = new int[6];
        public void clear() { emission=blockedFaces=0; Arrays.fill(incoming,0); }
    }
    @FunctionalInterface public interface CellReader {
        void read(int x,int y,int z,Input output);
    }
    public record Work(long decreaseVisits,long increaseVisits,long writes,long absorptionReads) {}
    private record Edit(int cell,Input input) {}
    private final int minY,maxY,height,cells;
    private final int cellShift,cellMask;
    private final boolean hasSky;
    private final Map<Long,Chunk> chunks=new HashMap<>();
    private final ArrayList<Chunk> slots=new ArrayList<>();
    private final ArrayDeque<Integer> freeSlots=new ArrayDeque<>();
    private final Set<Long> changed=new LinkedHashSet<>();
    private final RgbWordBlockPropagator block;
    private final RgbSkyPropagator sky;
    private int[] roots,skyRoots;
    private Chunk removing;
    private Chunk importing;
    private ChunkRgbCache.Data imported;
    private int importCursor;
    private boolean running, skyPhase, failed;
    private Work lastWork=new Work(0,0,0,0);

    private static final class OpticalSection {
        short[] emission;
        byte[] faces;
        final short[][] incoming=new short[6][];
        int emission(int i) { return emission==null?0:emission[i]; }
        int faces(int i) { return faces==null?0:faces[i]; }
        int incoming(int i,int d) { return incoming[d]==null?0:incoming[d][i]; }
        void set(int i,Input in) {
            if(in.emission!=0 && emission==null)emission=new short[4096];
            if(emission!=null)emission[i]=(short)in.emission;
            if(in.blockedFaces!=0 && faces==null)faces=new byte[4096];
            if(faces!=null)faces[i]=(byte)in.blockedFaces;
            for(int d=0;d<6;d++) {
                if(in.incoming[d]!=0 && incoming[d]==null)incoming[d]=new short[4096];
                if(incoming[d]!=null)incoming[d][i]=(short)in.incoming[d];
            }
        }
    }
    private final class Chunk {
        final int x,z,slot,base;
        final OpticalSection[] optical=new OpticalSection[(height+15)/16];
        final RgbLightVolume.WordColumnVolume blockLight=new RgbLightVolume.WordColumnVolume(height);
        final RgbLightVolume skyLight=new RgbLightVolume(16,height,16);
        final RgbLightVolume.UpdatingColumnReader skyReader=skyLight.updatingColumnReader();
        final Chunk[] adjacent=new Chunk[6];
        boolean removed,touched;
        Chunk(int x,int z,int slot) { this.x=x;this.z=z;this.slot=slot;base=Math.multiplyExact(slot,cells); }
        int emission(int local) { var s=optical[local>>>12];return removed||s==null?0:s.emission(local&4095); }
        int faces(int local) { var s=optical[local>>>12];return removed?63:s==null?0:s.faces(local&4095); }
        int incoming(int local,int d) { var s=optical[local>>>12];return removed?0xfff:s==null?0:s.incoming(local&4095,d); }
        void set(int local,Input in) {
            var s=optical[local>>>12];
            boolean zero=in.emission==0 && in.blockedFaces==0;
            for(int d=0;d<6;d++)zero &= in.incoming[d]==0;
            if(s==null) {if(zero)return;optical[local>>>12]=s=new OpticalSection();}
            s.set(local&4095,in);
        }
    }

    public SharedChunkRgbEngine(int minimumY,int maximumY,boolean hasSky) {
        if(maximumY<minimumY || (minimumY&15)!=0 || ((maximumY+1)&15)!=0)
            throw new IllegalArgumentException("Section-aligned inclusive world bounds required");
        minY=minimumY;maxY=maximumY;height=Math.addExact(Math.subtractExact(maxY,minY),1);
        cells=Math.multiplyExact(height,256);this.hasSky=hasSky;
        cellShift=(cells&(cells-1))==0?Integer.numberOfTrailingZeros(cells):-1;
        cellMask=cells-1;
        block=new RgbWordBlockPropagator(new RgbWordBlockAccess() {
            public int cellCount(){return capacity();}
            public int neighbor(int c,int d){return SharedChunkRgbEngine.this.neighbor(c,d);}
            public int emission(int c){return PackedWordRgb.fromRgb12(owner(c).emission(localCell(c)));}
            public int absorption(int c,int d){return PackedWordRgb.fromRgb12(SharedChunkRgbEngine.this.absorption(c,d));}
            public int light(int c){return value(c,false);}
            public void light(int c,int rgb){write(c,rgb,false);}
        });
        sky=new RgbSkyPropagator(new RgbSkyAccess() {
            public int cellCount(){return capacity();}
            public int neighbor(int c,int d){return SharedChunkRgbEngine.this.neighbor(c,d);}
            public int boundaryLight(int c){
                Chunk chunk=owner(c);int local=localCell(c);
                return hasSky && !chunk.removed && local>=cells-256
                        ? RgbSkyRules.attenuate(0xfff,chunk.incoming(local,2),true):0;
            }
            public int absorption(int c,int d){return SharedChunkRgbEngine.this.absorption(c,d);}
            public int light(int c){return value(c,true);}
            public void light(int c,int rgb){write(c,rgb,true);}
        });
    }
    public static long chunkKey(int x,int z) {return (x&0xffffffffL)|((long)z<<32);}
    public int minimumY(){return minY;}
    public int maximumY(){return maxY;}
    public int chunkCount(){return chunks.size();}
    public boolean hasChunk(int x,int z){return chunks.containsKey(chunkKey(x,z));}
    public boolean isRunning(){return running;}
    public Work completedWork(){requireIdle();return lastWork;}
    private int capacity(){return Math.multiplyExact(slots.size(),cells);}
    // Work IDs are nonnegative. Standard power-of-two world heights avoid variable
    // integer division in every light/optical access; unusual heights keep the exact fallback.
    private Chunk owner(int c){return slots.get(cellShift>=0?c>>>cellShift:c/cells);}
    private int localCell(int c){return cellShift>=0?c&cellMask:c%cells;}

    /** Reader is consumed synchronously; no Minecraft/world access is performed during advance. */
    public void beginLoad(int x,int z,CellReader reader) {
        beginLoad(x,z,reader,null,"");
    }
    /**
     * Restores a validated cache only into a newly attached chunk. Horizontal faces
     * are always invalidated/reseeded before publication: unchanged own optics do
     * not imply unchanged neighbors. Returns whether a warm start was accepted.
     */
    public boolean beginLoad(int x,int z,CellReader reader,byte[] cache,String profileId) {
        requireIdle();Objects.requireNonNull(reader);
        // Capture before attaching/mutating the live field so failed readers leave it intact.
        int slot=chunks.containsKey(chunkKey(x,z))?chunks.get(chunkKey(x,z)).slot:
                freeSlots.isEmpty()?slots.size():freeSlots.peekFirst();
        if(slot>=Integer.MAX_VALUE/cells-1)throw new IllegalStateException("RGB cell address capacity exhausted");
        Chunk captured=new Chunk(x,z,slot);Input in=new Input();
        for(int i=0;i<cells;i++) {read(reader,captured,i,in);captured.set(i,in);}
        Chunk chunk=chunks.get(chunkKey(x,z));
        boolean newlyAttached=chunk==null;
        ChunkRgbCache.Data warm=chunk==null && cache!=null
                ? ChunkRgbCache.decode(cache,minY,maxY,hasSky,Objects.requireNonNull(profileId),opticalHash(captured),cells):null;
        if(chunk==null) {
            chunk=captured;
            if(slot==slots.size())slots.add(chunk);else {freeSlots.removeFirst();slots.set(slot,chunk);}
            chunks.put(chunkKey(x,z),chunk);connect(chunk);
        } else System.arraycopy(captured.optical,0,chunk.optical,0,chunk.optical.length);
        markChanged(chunk);
        int[] changedCells,changedSky;
        if(warm!=null) {
            imported=warm;importing=chunk;importCursor=0;
            // Every external light path crosses one of these faces. Decrease follows
            // old component dependencies, then increase uses CURRENT neighboring light.
            changedCells=new int[height*60];int cursor=0;
            for(int y=0;y<height;y++)for(int zz=0;zz<16;zz++)for(int xx=0;xx<16;xx++)
                if(xx==0||xx==15||zz==0||zz==15)changedCells[cursor++]=chunk.base+(y<<8)+(zz<<4)+xx;
            changedSky=changedCells;
        } else if(newlyAttached) {
            // Zero initial values cannot have dependents. Only local sources and
            // boundary roots can increase this field; do not queue every dark air cell.
            int[] blockSeeds=new int[cells],skySeeds=new int[height*60+196];int b=0,s=0;
            for(int i=0;i<cells;i++) {
                int xx=i&15,zz=i>>>4&15;
                boolean face=xx==0||xx==15||zz==0||zz==15;
                if(face||chunk.emission(i)!=0)blockSeeds[b++]=chunk.base+i;
                if(hasSky&&(face||i>=cells-256))skySeeds[s++]=chunk.base+i;
            }
            changedCells=Arrays.copyOf(blockSeeds,b);changedSky=Arrays.copyOf(skySeeds,s);
        } else {
            changedCells=new int[cells];for(int i=0;i<cells;i++)changedCells[i]=chunk.base+i;
            changedSky=changedCells;
        }
        begin(changedCells,changedSky);
        return warm!=null;
    }
    /** Filesystem-free cache payload; callers own NBT/disk storage and their revision gate. */
    public byte[] exportCache(int x,int z,String profileId) {
        requireIdle();Chunk c=requireChunk(x,z);short[] blocks=new short[cells],skies=hasSky?new short[cells]:null;
        c.blockLight.snapshot().copyTo(blocks);if(hasSky)c.skyLight.snapshot().copyTo(skies);
        return ChunkRgbCache.encode(minY,maxY,hasSky,Objects.requireNonNull(profileId),opticalHash(c),blocks,skies);
    }
    private byte[] opticalHash(Chunk c) {
        var digest=ChunkRgbCache.digest();byte[] buffer=new byte[15*256];int cursor=0;
        for(int i=0;i<cells;i++) {
            int emission=c.emission(i);buffer[cursor++]=(byte)(emission>>>8);buffer[cursor++]=(byte)emission;
            buffer[cursor++]=(byte)c.faces(i);
            for(int d=0;d<6;d++){int v=c.incoming(i,d);buffer[cursor++]=(byte)(v>>>8);buffer[cursor++]=(byte)v;}
            if(cursor==buffer.length){digest.update(buffer);cursor=0;}
        }
        if(cursor!=0)digest.update(buffer,0,cursor);return digest.digest();
    }
    /** Local cell order is x + 16*z + 256*(y-minY). Optical edits may change both lanes. */
    public void beginUpdate(int x,int z,int[] localCells,CellReader reader) {
        requireIdle();Objects.requireNonNull(localCells);Objects.requireNonNull(reader);
        Chunk chunk=requireChunk(x,z);List<Edit> captured=new ArrayList<>();Input input=new Input();
        int[] changedCells=new int[localCells.length],changedSky=new int[localCells.length];
        int blockCount=0,skyCount=0;
        for(int i=0;i<localCells.length;i++) {
            int cell=localCells[i];Objects.checkIndex(cell,cells);
            read(reader,chunk,cell,input);
            boolean opacityDiffers=chunk.faces(cell)!=input.blockedFaces;
            for(int d=0;d<6;d++)opacityDiffers|=chunk.incoming(cell,d)!=input.incoming[d];
            if(opacityDiffers || chunk.emission(cell)!=input.emission) {
                changedCells[blockCount++]=chunk.base+cell;captured.add(new Edit(cell,input));input=new Input();
            }
            if(opacityDiffers)changedSky[skyCount++]=chunk.base+cell;
        }
        for(Edit edit:captured)chunk.set(edit.cell,edit.input);
        begin(Arrays.copyOf(changedCells,blockCount),Arrays.copyOf(changedSky,skyCount));
    }
    /** Keeps reciprocal edges until dependent light in surviving neighbors is removed. */
    public void beginUnload(int x,int z) {
        requireIdle();Chunk chunk=requireChunk(x,z);chunk.removed=true;removing=chunk;
        int[] changedCells=new int[cells];for(int i=0;i<cells;i++)changedCells[i]=chunk.base+i;
        begin(changedCells,changedCells);
    }
    private void begin(int[] changedCells,int[] changedSky) {
        roots=changedCells;skyRoots=changedSky;skyPhase=false;running=true;block.startUpdate(roots);
    }
    /** A work unit adopts a cached cell, scans a root, or visits its six propagation edges. */
    public boolean advance(int maximumOperations) {
        if(maximumOperations<1)throw new IllegalArgumentException("Positive operation budget required");
        if(failed)throw new IllegalStateException("Discard failed RGB engine");
        if(!running)return true;
        try {
            if(imported!=null) {
                int end=(int)Math.min((long)cells,(long)importCursor+maximumOperations);
                for(;importCursor<end;importCursor++) {
                    importing.blockLight.setRgb12(importCursor,imported.block()[importCursor]);
                    if(hasSky)importing.skyLight.set(importCursor,imported.sky()[importCursor]);
                }
                if(importCursor==cells){imported=null;importing=null;}
                return false;
            }
            if(!skyPhase) {
                if(!block.advance(maximumOperations))return false;
                var w=block.completedWork();lastWork=new Work(w.decreaseVisits(),w.increaseVisits(),w.writes(),w.absorptionReads());
                if(hasSky && skyRoots.length!=0) {sky.startUpdate(skyRoots);skyPhase=true;return false;}
            } else {
                if(!sky.advance(maximumOperations))return false;
                var w=sky.completedWork();lastWork=new Work(lastWork.decreaseVisits+w.decreaseVisits(),
                        lastWork.increaseVisits+w.increaseVisits(),lastWork.writes+w.writes(),lastWork.absorptionReads+w.absorptionReads());
            }
            if(removing!=null) {
                Chunk c=removing;
                for(int d:new int[]{0,1,4,5})if(c.adjacent[d]!=null)c.adjacent[d].adjacent[d^1]=null;
                chunks.remove(chunkKey(c.x,c.z));slots.set(c.slot,null);freeSlots.add(c.slot);
                changed.remove(chunkKey(c.x,c.z));removing=null;
            }
            roots=skyRoots=null;running=false;
            for(long key:changed) {Chunk c=chunks.get(key);if(c!=null){c.blockLight.publish();c.skyLight.publish();c.touched=false;}}
            return true;
        } catch(RuntimeException|Error e) {failed=true;throw e;}
    }
    public Set<Long> drainChangedChunks() {
        requireIdle();Set<Long> result=Set.copyOf(changed);changed.clear();return result;
    }
    public RgbLightVolume.Snapshot snapshot(int x,int z,boolean skyLane) {
        requireIdle();Chunk c=requireChunk(x,z);return (skyLane?c.skyLight.snapshot():c.blockLight.snapshot());
    }
    public int light(int x,int y,int z,boolean skyLane) {
        requireIdle();if(y<minY||y>maxY)return 0;
        Chunk c=chunks.get(chunkKey(x>>4,z>>4));
        return c==null?0:(skyLane?c.skyLight.snapshot():c.blockLight.snapshot()).get((x&15)|((z&15)<<4)|((y-minY)<<8));
    }
    private void requireIdle() {
        if(failed)throw new IllegalStateException("Discard failed RGB engine");
        if(running)throw new IllegalStateException("Complete RGB propagation before modifying or publishing");
    }
    private Chunk requireChunk(int x,int z) {
        Chunk c=chunks.get(chunkKey(x,z));if(c==null)throw new IllegalArgumentException("Chunk is not attached");return c;
    }
    private void read(CellReader reader,Chunk c,int local,Input in) {
        in.clear();reader.read((c.x<<4)+(local&15),minY+(local>>>8),(c.z<<4)+(local>>>4&15),in);
        PackedRgb.validate(in.emission);if((in.blockedFaces&~63)!=0)throw new IllegalArgumentException("Invalid faces");
        for(int v:in.incoming)PackedRgb.validate(v);
    }
    private void connect(Chunk c) {
        int[] dx={1,-1,0,0,0,0},dz={0,0,0,0,1,-1};
        for(int d:new int[]{0,1,4,5}) {
            Chunk n=chunks.get(chunkKey(c.x+dx[d],c.z+dz[d]));c.adjacent[d]=n;
            if(n!=null)n.adjacent[d^1]=c;
        }
    }
    private int neighbor(int cell,int direction) {
        Chunk c=owner(cell);int local=localCell(cell),x=local&15,z=local>>>4&15;
        return switch(direction) {
            case 0 -> x<15?cell+1:c.adjacent[0]==null?-1:c.adjacent[0].base+local-15;
            case 1 -> x>0?cell-1:c.adjacent[1]==null?-1:c.adjacent[1].base+local+15;
            case 2 -> local<cells-256?cell+256:-1;
            case 3 -> local>=256?cell-256:-1;
            case 4 -> z<15?cell+16:c.adjacent[4]==null?-1:c.adjacent[4].base+local-240;
            case 5 -> z>0?cell-16:c.adjacent[5]==null?-1:c.adjacent[5].base+local+240;
            default -> throw new IllegalArgumentException("Direction");
        };
    }
    private int absorption(int cell,int direction) {
        Chunk from=owner(cell);if((from.faces(localCell(cell))&1<<direction)!=0)return 0xfff;
        int next=neighbor(cell,direction);return next<0?0xfff:owner(next).incoming(localCell(next),direction^1);
    }
    private int value(int cell,boolean skyLane) {
        Chunk c=owner(cell);int local=localCell(cell);return skyLane?c.skyReader.get(local):c.blockLight.getUpdatingWord(local);
    }
    private void write(int cell,int rgb,boolean skyLane) {
        Chunk c=owner(cell);if(skyLane)c.skyLight.set(localCell(cell),rgb);else c.blockLight.setUpdatingWord(localCell(cell),rgb);markChanged(c);
    }
    private void markChanged(Chunk chunk) {
        if(!chunk.touched){changed.add(chunkKey(chunk.x,chunk.z));chunk.touched=true;}
    }
}
