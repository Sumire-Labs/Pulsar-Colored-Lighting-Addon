package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.chunk.SharedChunkRgbEngine;
import com.sumirelabs.pcla.light.engine.RgbBlockPropagator;
import com.sumirelabs.pcla.light.engine.RgbSkyPropagator;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import java.util.*;

/** Single BLOCK-worker adapter. Each retained world chunk owns exactly one RGB field. */
final class SharedMinecraftRgbWorld {
    private static final class Dirty {
        boolean full;
        final BitSet cells=new BitSet();
    }
    private record Capture(long key,Chunk chunk,int[] cells,boolean full,short[] source,byte[] faces,short[][] incoming) {}
    private final World world;
    private final WorldHeightContext height;
    private final boolean block,sky;
    private SharedChunkRgbEngine engine;
    private final Map<Long,Chunk> identities=new HashMap<>();
    private final Map<Long,BitSet> contextual=new HashMap<>();
    private final Map<Long,Dirty> events=new HashMap<>();
    private final ArrayDeque<Capture> captures=new ArrayDeque<>();
    private final ArrayDeque<Long> removals=new ArrayDeque<>();
    private final Map<ChunkDomainDirectory.Entry<Chunk,ExperimentalRgbBackend.Result>,RegionPublication.Ticket<Chunk>> tickets=new LinkedHashMap<>();
    private final Set<Long> epochNeeded=new LinkedHashSet<>();
    private final Set<Long> pendingChunks=new HashSet<>();
    private final Set<Long> cacheDirty=new LinkedHashSet<>();
    private final String cacheProfile;
    private long cacheHits,cacheMisses;
    private Long propagatingChunk;
    private Capture current;
    private LoadedRgbAccess currentAccess;
    private int cursor;
    private boolean epoch;
    private long reads,batches,publications;
    private long eventGeneration,failedGeneration=-1;
    private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
    private final CapturedRgbRegion.Input scratch=new CapturedRgbRegion.Input();

    SharedMinecraftRgbWorld(World world,WorldHeightContext height,boolean block,boolean sky) {
        this.world=world;this.height=height;this.block=block;this.sky=sky;
        cacheProfile="shared-rgb1:block="+block+":sky="+sky+":"+com.sumirelabs.pcla.Pcla.startupConfig().simulationProfile();
        engine=new SharedChunkRgbEngine(height.getMinBlockY(),height.getMaxBlockY(),sky);
    }
    synchronized void changed(long key) {eventGeneration++;events.computeIfAbsent(key,k->new Dirty()).full=true;}
    synchronized void changedAt(int x,int y,int z) {
        eventGeneration++;
        // Contextual opacity may depend on an adjacent block, including across a chunk edge.
        dirtyCell(x,y,z);dirtyCell(x+1,y,z);dirtyCell(x-1,y,z);dirtyCell(x,y+1,z);
        dirtyCell(x,y-1,z);dirtyCell(x,y,z+1);dirtyCell(x,y,z-1);
    }
    private void dirtyCell(int x,int y,int z) {
        if(y<height.getMinBlockY()||y>height.getMaxBlockY())return;
        events.computeIfAbsent(RegionPublication.key(x>>4,z>>4),k->new Dirty()).cells.set((x&15)|((z&15)<<4)|((y-height.getMinBlockY())<<8));
    }
    boolean running(){return epoch;}
    int retained(){return engine.chunkCount();}
    long reads(){return reads;}
    long batches(){return batches;}
    long publications(){return publications;}
    long cacheHits(){return cacheHits;}
    long cacheMisses(){return cacheMisses;}
    synchronized boolean retryAllowed(){return failedGeneration!=eventGeneration;}
    void failed(){synchronized(this){failedGeneration=eventGeneration;}discard();}
    void discard() {
        discardField();tickets.clear();
        synchronized(this){events.clear();}
        epoch=false;
    }
    private void discardField() {
        engine=new SharedChunkRgbEngine(height.getMinBlockY(),height.getMaxBlockY(),sky);
        identities.clear();contextual.clear();captures.clear();removals.clear();
        pendingChunks.clear();cacheDirty.clear();propagatingChunk=null;current=null;currentAccess=null;
    }
    String stage(){return current!=null?"CHUNK_CAPTURE":engine.isRunning()?"CHUNK_PROPAGATE":epoch?"CHUNK_PUBLISH":"IDLE";}
    private void enqueue(Capture next,boolean priority) {
        for(var iterator=captures.iterator();iterator.hasNext();) {
            var queued=iterator.next();if(queued.key!=next.key)continue;
            iterator.remove();
            if(queued.chunk==next.chunk) {
                if(queued.full)next=queued;
                else {
                    BitSet merged=new BitSet();for(int cell:queued.cells)merged.set(cell);for(int cell:next.cells)merged.set(cell);
                    next=new Capture(next.key,next.chunk,merged.stream().toArray(),next.full,null,null,null);
                }
            }
            break;
        }
        if(priority)captures.addFirst(next);else captures.addLast(next);
        pendingChunks.add(next.key);
    }

    /** Incorporate new edits between worker slices, ahead of unrelated initial captures. */
    void reconcile(List<ChunkDomainDirectory.Entry<Chunk,ExperimentalRgbBackend.Result>> owners,Set<Long> nativeReady) {
        if(!epoch)return;
        if(owners.isEmpty()){discard();return;}
        Map<Long,Dirty> pending;
        synchronized(this){pending=new HashMap<>(events);events.clear();}
        int cells=height.getTotalSections()*4096;
        Map<Long,Chunk> needed=new LinkedHashMap<>();
        tickets.keySet().retainAll(owners);
        for(var owner:owners) {
            var ticket=owner.gate().begin();
            if(ticket!=null && nativeReady.containsAll(ticket.chunks().keySet())) {
                needed.putAll(ticket.chunks());
                if(owner.gate().snapshot()==null)tickets.put(owner,ticket);
            }
        }
        if(!identities.isEmpty()&&(Collections.disjoint(identities.keySet(),needed.keySet())
                ||identities.size()>2L*Math.max(1,needed.size())))discardField();
        epochNeeded.clear();epochNeeded.addAll(needed.keySet());
        captures.removeIf(capture->!epochNeeded.contains(capture.key));
        pendingChunks.removeIf(key->!epochNeeded.contains(key)&&!Objects.equals(propagatingChunk,key));
        // A capture owns no mutable engine state until adoption, so unused work
        // can be discarded immediately when a player moves or disconnects.
        if(current!=null&&needed.get(current.key)!=current.chunk){current=null;currentAccess=null;}
        removals.removeIf(epochNeeded::contains);
        for(long key:identities.keySet())if(!epochNeeded.contains(key)&&!Objects.equals(propagatingChunk,key)&&!removals.contains(key)) {
            removals.add(key);pendingChunks.add(key);
        }
        for(long key:epochNeeded) {
            Chunk wanted=needed.get(key);
            if(identities.get(key)!=wanted) {
                boolean capturing=current!=null&&current.key==key&&current.chunk==wanted;
                boolean queued=captures.stream().anyMatch(capture->capture.key==key&&capture.chunk==wanted);
                if(!capturing&&!queued) {
                    int[] ids=new int[cells];for(int i=0;i<cells;i++)ids[i]=i;
                    enqueue(new Capture(key,wanted,ids,true,null,null,null),false);
                }
                if(!capturing)continue;
            }
            Dirty dirty=pending.get(key);BitSet selection=new BitSet();
            if(dirty!=null){if(dirty.full)selection.set(0,cells);else selection.or(dirty.cells);}
            var context=contextual.get(key);if(!pending.isEmpty()&&context!=null)selection.or(context);
            if(selection.isEmpty())continue;
            Chunk chunk=wanted;
            if(chunk==null) {
                // An untouched queued full capture will already see this edit.
                // A partially read current capture needs a second pass afterwards.
                if(current==null||current.key!=key)continue;
                chunk=current.chunk;
            }
            int[] ids=selection.stream().toArray();
            enqueue(new Capture(key,chunk,ids,false,null,null,null),true);
        }
    }

    /** Snapshot revisions before touching inputs; subsequent edits are reconciled next epoch. */
    boolean begin(List<ChunkDomainDirectory.Entry<Chunk,ExperimentalRgbBackend.Result>> owners,Set<Long> nativeReady) {
        if(owners.isEmpty()){if(engine.chunkCount()!=0||epoch)discard();return false;}
        if(!retryAllowed())return false;
        if(epoch)return true;
        epochNeeded.clear();tickets.clear();pendingChunks.clear();
        for(var owner:owners) {
            var ticket=owner.gate().begin();
            if(ticket==null||!nativeReady.containsAll(ticket.chunks().keySet()))continue;
            tickets.put(owner,ticket);epochNeeded.addAll(ticket.chunks().keySet());
        }
        Map<Long,Chunk> needed=new LinkedHashMap<>();
        for(var ticket:tickets.values())needed.putAll(ticket.chunks());
        if(!identities.isEmpty()&&(Collections.disjoint(identities.keySet(),needed.keySet())
                ||identities.size()>2L*Math.max(1,needed.size())))discardField();
        Map<Long,Dirty> pending;
        synchronized(this){pending=new HashMap<>(events);events.clear();}
        for(long key:List.copyOf(identities.keySet()))if(!needed.containsKey(key)){removals.add(key);pendingChunks.add(key);}
        int cells=height.getTotalSections()*4096;
        for(var entry:needed.entrySet()) {
            long key=entry.getKey();Chunk chunk=entry.getValue();Dirty dirty=pending.get(key);
            boolean full=identities.get(key)!=chunk||dirty!=null&&dirty.full;
            BitSet selected=new BitSet();
            if(full)selected.set(0,cells);
            else {
                if(dirty!=null)selected.or(dirty.cells);
                // A contextual getter may inspect more than its immediate neighbors.
                if(!pending.isEmpty()){BitSet context=contextual.get(key);if(context!=null)selected.or(context);}
            }
            if(selected.isEmpty())continue;
            int[] ids=selected.stream().toArray();
            enqueue(new Capture(key,chunk,ids,identities.get(key)!=chunk,null,null,null),false);
        }
        boolean needsPublication=tickets.keySet().stream().anyMatch(owner->owner.gate().snapshot()==null);
        epoch=!captures.isEmpty()||!removals.isEmpty()||needsPublication;
        if(!epoch)tickets.clear();
        return epoch;
    }

    /** Bounded world sampling and propagation. Caller supplies its remaining worker deadline. */
    boolean advance(int quantum) {
        if(!epoch)return true;
        if(engine.isRunning()) {
            if(engine.advance(quantum)){
                cacheDirty.addAll(engine.drainChangedChunks());
                pendingChunks.remove(propagatingChunk);propagatingChunk=null;publishReady();
            }
            return false;
        }
        if(current!=null) {
            int end=Math.min(current.cells.length,cursor+quantum);
            BitSet contexts=contextual.computeIfAbsent(current.key,k->new BitSet());
            if(cursor==0&&current.full)contexts.clear();
            for(;cursor<end;cursor++) {
                int cell=current.cells[cursor],x=((int)current.key<<4)+(cell&15),z=((int)(current.key>>32)<<4)+((cell>>4)&15),y=height.getMinBlockY()+(cell>>8);
                MinecraftRgbInputs.read(world,height,current.chunk,position,x,y,z,scratch,currentAccess);reads++;
                current.source[cursor]=(short)(block?scratch.source:0);current.faces[cursor]=(byte)scratch.blockedFaces;
                for(int d=0;d<6;d++)current.incoming[d][cursor]=(short)scratch.incoming[d];
                contexts.set(cell,scratch.contextual);
            }
            if(cursor<current.cells.length)return false;
            var captured=current;
            SharedChunkRgbEngine.CellReader reader=(x,y,z,out)->{
                int cell=(x&15)|((z&15)<<4)|((y-height.getMinBlockY())<<8);
                int index=captured.full?cell:Arrays.binarySearch(captured.cells,cell);
                if(index<0)throw new IllegalStateException("Uncaptured RGB input "+cell);
                out.emission=captured.source[index]&0xfff;out.blockedFaces=captured.faces[index]&63;
                for(int d=0;d<6;d++)out.incoming[d]=captured.incoming[d][index]&0xfff;
            };
            int cx=(int)captured.key,cz=(int)(captured.key>>32);
            if(captured.full) {
                if(engine.beginLoad(cx,cz,reader,RgbChunkPersistence.read(captured.chunk),cacheProfile))cacheHits++;else cacheMisses++;
            } else engine.beginUpdate(cx,cz,captured.cells,reader);
            propagatingChunk=captured.key;
            identities.put(captured.key,captured.chunk);batches++;current=null;currentAccess=null;return false;
        }
        if(!captures.isEmpty()){
            var next=captures.remove();int count=next.cells.length;
            if(next.full&&Boolean.getBoolean("pcla.networkDiagnostics"))org.apache.logging.log4j.LogManager.getLogger("PCLA").info(
                    "RGB SHARED CAPTURE timeMs={} chunk=({}, {}) queued={}",System.currentTimeMillis(),(int)next.key,(int)(next.key>>32),captures.size());
            current=new Capture(next.key,next.chunk,next.cells,next.full,new short[count],new byte[count],new short[6][count]);
            Map<Long,Chunk> inputs=new HashMap<>(identities);for(var ticket:tickets.values())inputs.putAll(ticket.chunks());
            inputs.put(next.key,next.chunk);
            currentAccess=new LoadedRgbAccess(world,height,inputs);
            cursor=0;return false;
        }
        if(!removals.isEmpty()) {
            long key=removals.remove();engine.beginUnload((int)key,(int)(key>>32));propagatingChunk=key;identities.remove(key);contextual.remove(key);return false;
        }
        engine.drainChangedChunks();
        if(!cacheDirty.isEmpty()) {
            long key=cacheDirty.iterator().next();cacheDirty.remove(key);Chunk chunk=identities.get(key);
            if(chunk!=null&&engine.hasChunk((int)key,(int)(key>>32)))RgbChunkPersistence.store(chunk,engine.exportCache((int)key,(int)(key>>32),cacheProfile));
            return false;
        }
        publishReady();tickets.clear();epoch=false;return true;
    }
    private void publishReady() {
        // Every horizontal propagation edge loses at least one level. Once this
        // column and its eight guard chunks settle, later distant captures cannot
        // affect its interior. Publish it without waiting for unrelated players.
        for(var iterator=tickets.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next();
            var owner=entry.getKey();var ticket=entry.getValue();boolean currentIdentities=true;
            var complete=owner.gate().snapshot();
            if(complete!=null&&complete.revision()==ticket.revision()){iterator.remove();continue;}
            if(ticket.chunks().keySet().stream().anyMatch(key->pendingChunks.contains(key)
                    || captures.stream().anyMatch(capture->capture.key==key)))continue;
            for(var dependency:ticket.chunks().entrySet())if(identities.get(dependency.getKey())!=dependency.getValue()){currentIdentities=false;break;}
            if(!currentIdentities||!owner.gate().ownsChunks(ticket)){iterator.remove();continue;}
            int cx=(int)owner.chunkKey(),cz=(int)(owner.chunkKey()>>32);
            var old=owner.gate().displaySnapshot();
            var blockLight=block?engine.snapshot(cx,cz,false):null;
            var skyLight=sky?engine.snapshot(cx,cz,true):null;
            var skyResult=sky?new ExperimentalRgbBackend.SkyResult(skyLight,new RgbSkyPropagator.Work(0,0,0,0),0,0):null;
            var result=new ExperimentalRgbBackend.Result(owner.bounds(),ticket.revision(),blockLight,0,0,new RgbBlockPropagator.Work(0,0,0,0),reads,reads,old==null?1:old.captures()+1,skyResult);
            if(owner.gate().publish(ticket,result)) {
                publications++;
                if(old==null&&Boolean.getBoolean("pcla.networkDiagnostics"))org.apache.logging.log4j.LogManager.getLogger("PCLA").info(
                        "RGB SHARED PUBLISH timeMs={} chunk=({}, {})",System.currentTimeMillis(),cx,cz);
            }
            iterator.remove();
        }
    }
}
