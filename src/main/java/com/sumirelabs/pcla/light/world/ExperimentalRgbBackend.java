package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.Pcla;
import com.sumirelabs.pcla.light.engine.RgbBlockPropagator;
import com.sumirelabs.pcla.light.engine.RgbSkyPropagator;
import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import com.sumirelabs.pulsar.api.lighting.WorldLightingBackend;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import java.util.*;
import org.apache.logging.log4j.LogManager;

/** Opt-in bounded server pilot. Scalar gameplay remains owned by Pulsar. */
public final class ExperimentalRgbBackend implements WorldLightingBackend {
    public record SkyResult(RgbLightVolume.Snapshot light,RgbSkyPropagator.Work work,int sampledColumns,long columnBlockReads) {}
    public record WorkerTiming(long rebuildAttempts,long incrementalAttempts,long rebuildSolveNanos,long incrementalSolveNanos,long sharedCaptureNanos) {}
    public record Result(CapturedRgbRegion.Bounds bounds, long revision, RgbLightVolume.Snapshot light,
                         int sampledInputs, int changedInputs, RgbBlockPropagator.Work work,
                         long totalSampledInputs, long fullCaptureEquivalent, long captures,SkyResult sky) {}
    private enum Stage { CHECK, CAPTURE, ADOPT, BLOCK, SKY_CAPTURE, SKY, PUBLISH }
    private static final int PROPAGATION_QUANTUM=1024;
    private static final int CAPTURE_QUANTUM=512;
    public record PipelineStats(String activeStage,long propagationBatches,long captureBatches,long yieldedJobs,long cancelledJobs,long staleCompletions,
                                long adoptionNanos,long blockNanos,long skyCaptureNanos,long skyNanos,long publicationNanos) {}
    private volatile PipelineStats pipelineStats=new PipelineStats("IDLE",0,0,0,0,0,0,0,0,0,0);
    private final long[] phaseNanos=new long[Stage.values().length];
    private long propagationBatches,captureBatches,yieldedJobs,cancelledJobs,staleCompletions;
    // Only one job pins working state and a ticket across turns.
    private ActiveJob activeJob;
    private static final class ActiveJob {
        final LightDomain<Chunk,Result> entry;final RegionPublication.Ticket<Chunk> ticket;final Working working;final boolean rebuild;
        SharedOpticalInputs.View view;CapturedRgbRegion.Capture capture;
        SharedOpticalInputs<Chunk>.CaptureTask captureTask;
        CapturedRgbRegion.Adoption adoption;
        Stage stage=Stage.CAPTURE;int sampledInputs,changedInputs,sampledSkyColumns;long solveNanos;
        RgbBlockPropagator.Work blockWork=new RgbBlockPropagator.Work(0,0,0,0);
        RgbSkyPropagator.Work skyWork;
        OpticalCheckpoint.Check check;
        CompletedInputs previous;
        ActiveJob(LightDomain<Chunk,Result> entry,RegionPublication.Ticket<Chunk> ticket,Working working,boolean rebuild,SharedOpticalInputs<Chunk>.CaptureTask captureTask,int sampledInputs) {
            this.entry=entry;this.ticket=ticket;this.working=working;this.rebuild=rebuild;this.captureTask=captureTask;this.sampledInputs=sampledInputs;
        }
    }
    private record CompletedInputs(OpticalCheckpoint optics,Result result) {}
    // Worker-owned immutable checkpoints outlive LRU eviction, but never outlive the owner.
    private final Map<LightDomain<Chunk,Result>,CompletedInputs> completedInputs=new IdentityHashMap<>();
    private long unchangedChecks,checkReads;
    public record NoopStats(long unchangedChecks,long worldReads,int retainedCheckpoints) {}
    private volatile NoopStats noopStats=new NoopStats(0,0,0);
    public NoopStats noopStats(){return noopStats;}
    private static final Map<World, ExperimentalRgbBackend> WORLDS = new IdentityHashMap<>();
    private final World world;
    private final WorldHeightContext height;
    private final RegionDirectory<Chunk, Result> regions;
    private final ChunkDomainDirectory<Chunk,Result> columns=new ChunkDomainDirectory<>();
    private final Set<Long> manualColumns=new HashSet<>(),automaticColumns=new HashSet<>(),nativeReady=new HashSet<>();
    private final Set<Long> readinessCandidates=new HashSet<>();
    private final Object inputEvents=new Object();
    private final Object workerLifecycle=new Object();
    private final boolean blockEnabled,skyEnabled;
    // Only the BLOCK worker accesses this cache and its mutable propagation state.
    private final WorkingSet<LightDomain<Chunk,Result>, Working> workers;
    private volatile WorkingSet.Stats workerStats;
    private long rebuildAttempts,incrementalAttempts,rebuildSolveNanos,incrementalSolveNanos,sharedCaptureNanos;
    private volatile WorkerTiming workerTiming=new WorkerTiming(0,0,0,0,0);
    public record ContinuationStats(boolean connected,long turns,long yieldedTurns,long visitedOwners,int remaining,long maximumTurnNanos) {}
    private final ContinuationWork<LightDomain<Chunk,Result>> pendingOwners=new ContinuationWork<>();
    private volatile Runnable wakeBlockWorker;
    private volatile RgbContinuationWorker rgbWorker;
    private volatile boolean closed;
    private boolean maintenanceRequired;
    private final long continuationBudgetNanos;
    private long continuationTurns,continuationYields,continuationVisits,maximumTurnNanos;
    private volatile ContinuationStats continuationStats=new ContinuationStats(false,0,0,0,0,0);
    private final SharedOpticalInputs<Chunk> sharedInputs=new SharedOpticalInputs<>();
    private final SharedMinecraftRgbWorld chunkWorld;
    private final boolean sharedChunks=Boolean.parseBoolean(System.getProperty("pcla.sharedChunks","true"));
    public record SharedChunkStats(boolean enabled,int retainedChunks,long worldReads,long batches,long publications,String stage,long cacheHits,long cacheMisses) {}
    private volatile SharedChunkStats sharedChunkStats=new SharedChunkStats(false,0,0,0,0,"IDLE",0,0);
    public SharedChunkStats sharedChunkStats(){return sharedChunkStats;}
    private volatile SharedOpticalInputs.Stats sharedStats=new SharedOpticalInputs.Stats(0,0,0,0,0);
    private final Map<LightDomain<Chunk,Result>, String> failures = new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Working {
        final CapturedRgbRegion input;
        final RgbBlockPropagator engine;
        final CapturedSkyRegion skyInput;
        final RgbSkyPropagator skyEngine;
        long totalSampledInputs, fullCaptureEquivalent, captures;
        Working(CapturedRgbRegion.Bounds bounds,boolean block,boolean sky,int minimumY,int maximumY) {
            input = new CapturedRgbRegion(bounds,true,minimumY,maximumY); engine = block ? new RgbBlockPropagator(input) : null;
            skyInput=sky ? new CapturedSkyRegion(input,minimumY,maximumY) : null;
            skyEngine=sky ? new RgbSkyPropagator(skyInput) : null;
        }
    }

    public ExperimentalRgbBackend(World world, WorldHeightContext height) {
        this.world = world; this.height = height;
        workers=new WorkingSet<>(Integer.parseInt(System.getProperty("pcla.experimentalWorkerCacheOwners",String.valueOf(com.sumirelabs.pcla.config.RuntimeOptions.defaultWorkingOwners()))));
        workerStats=workers.stats();
        long budgetMillis=Long.parseLong(System.getProperty("pcla.experimentalSliceMillis","4"));
        if(budgetMillis<1 || budgetMillis>1000)throw new IllegalArgumentException("RGB slice milliseconds must be between 1 and 1000");
        continuationBudgetNanos=budgetMillis*1_000_000L;
        blockEnabled=Pcla.startupConfig().feature(com.sumirelabs.pcla.config.Feature.BLOCK_LIGHT);
        skyEnabled=com.sumirelabs.pcla.config.RuntimeOptions.skyRgb() && world.provider.hasSkyLight()
                && Pcla.startupConfig().feature(com.sumirelabs.pcla.config.Feature.SKY_LIGHT);
        regions=new RegionDirectory<>(skyEnabled);
        chunkWorld=new SharedMinecraftRgbWorld(world,height,blockEnabled,skyEnabled);
        synchronized (WORLDS) { WORLDS.put(world, this); }
    }
    public static ExperimentalRgbBackend get(World world) { synchronized (WORLDS) { return WORLDS.get(world); } }
    /** RGB is a sidecar: its color profile never changes Pulsar's scalar nibble format or values. */
    public String cacheKey() { return "pcla:scalar-passthrough1"; }
    /** Allow the host's already-validated scalar cache; RGB has its own independently validated NBT tag. */
    @Override public boolean saveLight(Chunk chunk,net.minecraft.nbt.NBTTagCompound lightTag) {return true;}
    @Override public boolean loadLight(Chunk chunk,net.minecraft.nbt.NBTTagCompound lightTag) {return true;}
    public boolean blockEnabled() { return blockEnabled; }
    public boolean skyEnabled() { return skyEnabled; }
    public SharedOpticalInputs.Stats sharedInputStats() { return sharedStats; }
    public WorkingSet.Stats workerCacheStats() { return workerStats; }
    public WorkerTiming workerTiming() {return workerTiming;}
    public PipelineStats pipelineStats() {return pipelineStats;}
    public ContinuationStats continuationStats() {return continuationStats;}
    public void bindBlockContinuation(Runnable request) {
        Objects.requireNonNull(request);
        synchronized(inputEvents) {
            if(closed)return;
            if(sharedChunks) {
                if(rgbWorker==null)rgbWorker=new RgbContinuationWorker("PCLA-RGB-"+world.provider.getDimension(),this::runBlockContinuation,
                        failure->LogManager.getLogger("PCLA").error("RGB continuation failed; scalar lighting remains active",failure));
                wakeBlockWorker=rgbWorker::signal;
            } else wakeBlockWorker=request;
        }
    }
    public RgbContinuationWorker.Stats rgbWorkerStats() {
        var worker=rgbWorker;return worker==null?new RgbContinuationWorker.Stats(false,false,closed,0):worker.stats();
    }
    /** Diagnostic resumption of selected, already loaded geometry without a scalar light request. */
    public boolean requestRgbRecalculation() {
        Runnable wake=wakeBlockWorker;if(wake==null)return false;
        synchronized(inputEvents) {
            if(closed)return false;
            List<LightDomain<Chunk,Result>> selected=new ArrayList<>(regions.entries());selected.addAll(columns.entries());
            for(var entry:selected) {
                entry.gate().changed(RegionPublication.key(entry.bounds().x()>>4,entry.bounds().z()>>4));
                if(!sharedChunks||!(entry instanceof ChunkDomainDirectory.Entry))pendingOwners.offer(entry);
                if(entry instanceof ChunkDomainDirectory.Entry)for(long key:entry.bounds().chunks())chunkWorld.changed(key);
            }
        }
        wake.run();return true;
    }
    public void chunkLoaded(Chunk chunk) { synchronized(inputEvents) { long key=RegionPublication.key(chunk.x,chunk.z);if(columns.loadedChunk(key)!=chunk)nativeReady.remove(key);regions.loaded(key,chunk); columns.loaded(key,chunk);if(!columns.affected(key).isEmpty())readinessCandidates.add(key); } }
    public void chunkUnloaded(int x, int z) { synchronized(inputEvents) { long key=RegionPublication.key(x,z);nativeReady.remove(key);readinessCandidates.remove(key);regions.unloaded(key); columns.unloaded(key); } }
    public void blockLightQueued(int x, int z) { synchronized(inputEvents) { long key=RegionPublication.key(x,z); regions.changed(key); columns.changed(key); if(sharedChunks && !columns.affected(key).isEmpty()){chunkWorld.changed(key);requestMaintenance();} } }
    public void blockLightQueuedAt(int x, int y, int z) { synchronized(inputEvents) { regions.changedAt(x,y,z); columns.changedAt(x,y,z); if(sharedChunks && columns.containsInput(x,y,z)){chunkWorld.changedAt(x,y,z);requestMaintenance();} } }
    public boolean needsBlockStateUpdate(int x, int y, int z) { return regions.containsInput(x,y,z) || columns.containsInput(x,y,z); }
    public boolean needsBlockWorkForSkyCheck(int x,int y,int z) { return skyEnabled && needsBlockStateUpdate(x,y,z); }
    public void watchColumn(int x,int z) {
        synchronized(inputEvents) {manualColumns.add(RegionPublication.key(x,z));var entry=columns.watch(x,z,columnBounds(x,z));readinessCandidates.addAll(entry.bounds().chunks());}
    }
    public CapturedRgbRegion.Bounds columnBounds(int x,int z) {
        if((height.getMinBlockY()&15)!=0 || (height.getMaxBlockY()&15)!=15) throw new IllegalStateException("Column pilot requires section-aligned world height");
        return new CapturedRgbRegion.Bounds(Math.multiplyExact(x,16),height.getMinBlockY(),Math.multiplyExact(z,16),1,height.getTotalSections(),1);
    }
    public boolean removeColumn(int x,int z) { synchronized(inputEvents) {
        long key=RegionPublication.key(x,z);boolean removed=manualColumns.remove(key);
        if(!automaticColumns.contains(key))removed|=columns.remove(x,z);if(removed)requestMaintenance();return removed;
    } }
    public boolean automaticEnabled(){return com.sumirelabs.pcla.config.RuntimeOptions.AUTOMATIC && (blockEnabled || skyEnabled);}
    public Set<Long> automaticColumns(){synchronized(inputEvents){return Set.copyOf(automaticColumns);}}
    public Result columnDisplaySnapshot(int x,int z){var entry=columns.get(x,z);return entry==null?null:entry.gate().displaySnapshot();}
    private boolean nativeDependenciesReady(CapturedRgbRegion.Bounds bounds){return nativeReady.containsAll(bounds.chunks());}
    /** Caller holds inputEvents; only still-unready selected dependencies are polled. */
    private void refreshNativeReadiness() {
        for(var iterator=readinessCandidates.iterator();iterator.hasNext();) {
            long key=iterator.next();
            if(nativeReady.contains(key)){iterator.remove();continue;}
            Chunk chunk=columns.loadedChunk(key);
            if(chunk instanceof com.sumirelabs.pulsar.api.ExtendedChunk extended && extended.pulsar$isLightReady()) {
                nativeReady.add(key);iterator.remove();
            }
        }
    }
    public void synchronizeAutomaticColumns(Set<Long> desired) {
        synchronized(inputEvents) {
            if(closed || automaticColumns.equals(desired))return;
            for(long key:List.copyOf(automaticColumns))if(!desired.contains(key) && !manualColumns.contains(key)) {
                var entry=columns.get((int)key,(int)(key>>32));if(entry!=null)pendingOwners.remove(entry);
                columns.remove((int)key,(int)(key>>32));
            }
            automaticColumns.clear();automaticColumns.addAll(desired);
            for(long key:desired) {
                var entry=columns.watch((int)key,(int)(key>>32),columnBounds((int)key,(int)(key>>32)));
                for(long dependency:entry.bounds().chunks())if(!nativeReady.contains(dependency))readinessCandidates.add(dependency);
                if(!sharedChunks && entry.gate().snapshot()==null && nativeDependenciesReady(entry.bounds()))pendingOwners.offer(entry);
            }
            if(sharedChunks)refreshNativeReadiness();
            Set<Long> required=new HashSet<>();for(var entry:columns.entries())required.addAll(entry.bounds().chunks());
            readinessCandidates.retainAll(required);
            if(sharedChunks&&Boolean.getBoolean("pcla.networkDiagnostics")) {
                Set<Long> dependencies=new HashSet<>();int ready=0,loaded=0;
                for(var entry:columns.entries()) {
                    dependencies.addAll(entry.bounds().chunks());
                    if(entry.gate().begin()!=null)loaded++;
                    if(nativeDependenciesReady(entry.bounds()))ready++;
                }
                LogManager.getLogger("PCLA").info("RGB SHARED INTEREST timeMs={} owners={} dependencies={} loadedOwners={} nativeReadyOwners={}",
                        System.currentTimeMillis(),columns.entries().size(),dependencies.size(),loaded,ready);
            }
            requestMaintenance();
        }
    }
    public Result columnSnapshot(int x,int z) { var entry=columns.get(x,z); return entry==null?null:entry.gate().snapshot(); }
    public boolean hasColumn(int x,int z) { return columns.get(x,z)!=null; }
    public boolean hasManualColumn(int x,int z){synchronized(inputEvents){return manualColumns.contains(RegionPublication.key(x,z));}}
    public String columnFailure(int x,int z) { var entry=columns.get(x,z); return entry==null?null:failures.get(entry); }
    public void watch(int x, int y, int z) { watch(x,y,z,1); }
    public void watch(int x, int y, int z, int span) { synchronized(inputEvents) { regions.replace(0,bounds(x,y,z,span)); } }
    public int add(int x, int y, int z, int span) { synchronized(inputEvents) { return regions.add(bounds(x,y,z,span)); } }
    public boolean remove(int slot) { synchronized(inputEvents) { boolean removed=regions.remove(slot);if(removed)requestMaintenance();return removed; } }
    private void requestMaintenance() {maintenanceRequired=true;Runnable wake=wakeBlockWorker;if(wake!=null && !closed)wake.run();}
    public List<Integer> slots() { return regions.entries().stream().map(RegionDirectory.Entry::slot).toList(); }
    private CapturedRgbRegion.Bounds bounds(int x, int y, int z, int span) {
        if (span < 1 || span > 2) throw new IllegalArgumentException("Invalid region span");
        int sectionsY = Math.min(span,height.getTotalSections());
        int baseY = Math.min(y & ~15,height.getMaxBlockY()+1-sectionsY*16);
        var bounds = new CapturedRgbRegion.Bounds(x & ~15,baseY,z & ~15,span,sectionsY,span);
        if (y < height.getMinBlockY() || y > height.getMaxBlockY() || bounds.y() < height.getMinBlockY())
            throw new IllegalArgumentException("Outside world height");
        return bounds;
    }
    public Result snapshot() { return snapshot(0); }
    public Result snapshot(int slot) {
        var entry = regions.get(slot);
        return entry == null ? null : entry.gate().snapshot();
    }
    public Result displaySnapshot(int slot) {
        var entry=regions.get(slot);
        return entry==null ? null : entry.gate().displaySnapshot();
    }
    public String failure() { return failure(0); }
    public String failure(int slot) {
        var entry = regions.get(slot);
        return entry == null ? null : failures.get(entry);
    }
    /** Diagnostic identity snapshot; null means at least one actual dependency chunk is unloaded. */
    public Map<Long,Chunk> dependencyChunks(int slot) {
        var entry = regions.get(slot);
        var ticket = entry == null ? null : entry.gate().begin();
        return ticket == null ? null : ticket.chunks();
    }

    public void afterBlockTask(int x, int z) {
        synchronized(inputEvents) {
            if(closed)return;
            long key=RegionPublication.key(x,z);if(columns.loadedChunk(key)!=null)nativeReady.add(key);readinessCandidates.remove(key);
            List<LightDomain<Chunk,Result>> affected=new ArrayList<>(regions.affected(RegionPublication.key(x,z))); affected.addAll(columns.affected(RegionPublication.key(x,z)));
            for(var entry:affected)if(entry.gate().snapshot()==null) {
                if(entry instanceof ChunkDomainDirectory.Entry<Chunk,Result> column && automaticColumns.contains(column.chunkKey()) && !manualColumns.contains(column.chunkKey()) && !nativeDependenciesReady(entry.bounds()))continue;
                if(!sharedChunks||!(entry instanceof ChunkDomainDirectory.Entry))pendingOwners.offer(entry);
            }
            if(pendingOwners.size()==0 && !maintenanceRequired
                    && (!sharedChunks || affected.stream().noneMatch(entry->entry instanceof ChunkDomainDirectory.Entry)))return;
        }
        Runnable wake=wakeBlockWorker;
        if(wake!=null)wake.run();
        else {
            // Older companion hosts have no continuation callback. Preserve their
            // synchronous completion instead of leaving deferred work stranded.
            do {runBlockContinuation();}while(!closed && (pendingOwners.size()>0 || activeJob!=null || chunkWorld.running()));
        }
    }
    public void runBlockContinuation() {
        synchronized(workerLifecycle){runBlockContinuationLocked();}
    }
    private void runBlockContinuationLocked() {
        if(closed)return;
        long turnStart=System.nanoTime();List<LightDomain<Chunk,Result>> active;
        synchronized(inputEvents) {
            if(closed)return;
            active=new ArrayList<>(regions.entries());if(!sharedChunks)active.addAll(columns.entries());
            pendingOwners.retain(active);
            maintenanceRequired=false;
            if(sharedChunks) {
                refreshNativeReadiness();
                if(!chunkWorld.running())chunkWorld.begin(columns.entries(),Set.copyOf(nativeReady));
                else chunkWorld.reconcile(columns.entries(),Set.copyOf(nativeReady));
            }
        }
        workers.retain(active);
        completedInputs.keySet().retainAll(active);
        failures.keySet().removeIf(entry -> !active.contains(entry));
        sharedInputs.retain(active.stream().map(LightDomain::bounds).toList());
        try {
            try {
                while(sharedChunks && chunkWorld.running() && !closed && System.nanoTime()-turnStart<continuationBudgetNanos)chunkWorld.advance(CAPTURE_QUANTUM);
                if(sharedChunks)for(var entry:columns.entries())if(entry.gate().snapshot()!=null)failures.remove(entry);
            } catch(RuntimeException failure) {
                chunkWorld.failed();
                for(var entry:columns.entries()){entry.gate().failed();failures.put(entry,failure.toString());}
                LogManager.getLogger("PCLA").error("Shared RGB world failed; scalar lighting remains active until the next input change",failure);
            }
            int touched=activeJob==null?0:1;
            do {
                if(activeJob==null) {
                    if(touched>=workers.limit())break;
                    var entry=pendingOwners.poll();if(entry==null)break;
                    touched++;continuationVisits++;
                    prepareJob(entry);
                    if(activeJob==null)continue;
                    if(System.nanoTime()-turnStart>=continuationBudgetNanos)break;
                }
                advanceActive();
            } while(!closed && System.nanoTime()-turnStart<continuationBudgetNanos);
        } finally {
            sharedChunkStats=new SharedChunkStats(sharedChunks,chunkWorld.retained(),chunkWorld.reads(),chunkWorld.batches(),chunkWorld.publications(),chunkWorld.stage(),chunkWorld.cacheHits(),chunkWorld.cacheMisses());
            workerStats=workers.stats();sharedStats=sharedInputs.stats();
            noopStats=new NoopStats(unchangedChecks,checkReads,completedInputs.size());
            workerTiming=new WorkerTiming(rebuildAttempts,incrementalAttempts,rebuildSolveNanos,incrementalSolveNanos,sharedCaptureNanos);
            continuationTurns++;int remaining=pendingOwners.size()+(activeJob==null?0:1)+(chunkWorld.running()?1:0);if(remaining>0)continuationYields++;
            if(activeJob!=null)yieldedJobs++;
            String stage=activeJob==null?chunkWorld.stage():activeJob.stage==Stage.CAPTURE?"CAPTURE_"+activeJob.captureTask.stage():activeJob.stage.name();
            pipelineStats=new PipelineStats(stage,propagationBatches,captureBatches,yieldedJobs,cancelledJobs,staleCompletions,
                    phaseNanos[Stage.ADOPT.ordinal()],phaseNanos[Stage.BLOCK.ordinal()],phaseNanos[Stage.SKY_CAPTURE.ordinal()],phaseNanos[Stage.SKY.ordinal()],phaseNanos[Stage.PUBLISH.ordinal()]);
            maximumTurnNanos=Math.max(maximumTurnNanos,System.nanoTime()-turnStart);
            Runnable wake=wakeBlockWorker;
            continuationStats=new ContinuationStats(wake!=null,continuationTurns,continuationYields,continuationVisits,remaining,maximumTurnNanos);
            boolean needsWake;
            synchronized(inputEvents) {
                needsWake=!closed && (remaining>0 || (!sharedChunks || chunkWorld.retryAllowed()) && columns.entries().stream().anyMatch(e->e.gate().snapshot()==null && e.gate().begin()!=null && nativeDependenciesReady(e.bounds())));
            }
            if(needsWake&&wake!=null)wake.run();
        }
    }
    private void prepareJob(LightDomain<Chunk,Result> entry) {
        // Native callbacks can enqueue a column after retain() released inputEvents.
        // Shared columns must never enter the detached compatibility solver.
        if(sharedChunks&&entry instanceof ChunkDomainDirectory.Entry)return;
        RegionPublication.Ticket<Chunk> ticket;
        synchronized(inputEvents) {
            if(closed || entry.gate().snapshot()!=null)return;
            if(entry instanceof ChunkDomainDirectory.Entry<Chunk,Result> column && automaticColumns.contains(column.chunkKey()) && !manualColumns.contains(column.chunkKey()) && !nativeDependenciesReady(entry.bounds()))return;
            ticket=entry.gate().begin();
        }
        if(ticket==null)return;
        var previous=completedInputs.get(entry);
        if(!workers.contains(entry) && !ticket.fullCapture() && previous!=null
                && previous.result()==entry.gate().displaySnapshot()) {
            var b=entry.bounds();var pos=new net.minecraft.util.math.BlockPos.MutableBlockPos();
            var check=previous.optics().check(ticket,(cell,value)->MinecraftRgbInputs.read(world,height,ticket.chunks(),pos,
                b.x()-15+cell%b.inputX(),b.y()-15+cell/(b.inputX()*b.inputZ()),b.z()-15+cell/b.inputX()%b.inputZ(),value));
            activeJob=new ActiveJob(entry,ticket,null,false,null,0);activeJob.stage=Stage.CHECK;
            activeJob.check=check;activeJob.previous=previous;return;
        }
        prepareWorking(entry,ticket);
    }
    private void prepareWorking(LightDomain<Chunk,Result> entry,RegionPublication.Ticket<Chunk> ticket) {
        try {
            var access=workers.acquire(entry,()->new Working(entry.bounds(),blockEnabled,skyEnabled,height.getMinBlockY(),height.getMaxBlockY()));
            var working=access.value();boolean rebuild=access.created();
            var request=working.input.requestShared(ticket,ticket.fullCapture()||rebuild);
            long start=System.nanoTime();SharedOpticalInputs<Chunk>.CaptureTask task;
            try {
                var pos=new net.minecraft.util.math.BlockPos.MutableBlockPos();
                task=sharedInputs.beginCapture(List.of(request),(chunk,bx,by,bz,value)->MinecraftRgbInputs.read(world,height,chunk,pos,bx,by,bz,value));
            } finally {sharedCaptureNanos+=System.nanoTime()-start;}
            activeJob=new ActiveJob(entry,ticket,working,rebuild,task,request.selectedCellCount());
        } catch(RuntimeException e) {failed(entry,e);}
    }
    private void advanceActive() {
        var job=activeJob;
        // A new revision alone is NOT grounds for restarting a full relight forever.
        // Finish its stable inputs, reject stale publication, then reconcile the next ticket.
        if(!job.entry.gate().ownsChunks(job.ticket)) {
            if(job.captureTask!=null)job.captureTask.cancel();
            if(job.adoption!=null)job.adoption.cancel();
            if(job.working!=null && job.working.engine!=null && job.working.engine.isRunning())job.working.engine.cancel();
            if(job.working!=null && job.working.skyEngine!=null && job.working.skyEngine.isRunning())job.working.skyEngine.cancel();
            workers.remove(job.entry);cancelledJobs++;recordAttempt(job);activeJob=null;return;
        }
        Stage stage=job.stage;long start=System.nanoTime();boolean finished=false;
        try {
            var working=job.working;
            switch(stage) {
                case CHECK -> {
                    int before=job.check.reads();boolean complete=job.check.advance(CAPTURE_QUANTUM);checkReads+=job.check.reads()-before;
                    if(!complete)break;
                    if(job.check.unchanged()) {
                        var old=job.previous.result();
                        var next=new Result(old.bounds(),job.ticket.revision(),old.light(),job.check.reads(),0,
                            new RgbBlockPropagator.Work(0,0,0,0),old.totalSampledInputs()+job.check.reads(),old.fullCaptureEquivalent(),old.captures()+1,old.sky());
                        if(job.entry.gate().publish(job.ticket,next)) {
                            completedInputs.put(job.entry,new CompletedInputs(job.previous.optics(),next));unchangedChecks++;
                        } else {staleCompletions++;pendingOwners.offer(job.entry);}
                        activeJob=null;
                    } else {
                        activeJob=null;prepareWorking(job.entry,job.ticket);
                    }
                    finished=true;
                }
                case CAPTURE -> {
                    captureBatches++;
                    if(job.captureTask.advance(CAPTURE_QUANTUM)) {
                        job.view=job.captureTask.result().getFirst();job.captureTask=null;job.stage=Stage.ADOPT;
                    }
                }
                case ADOPT -> {
                    if(job.adoption==null){job.adoption=working.input.beginRefreshShared(job.view,job.sampledInputs);job.view=null;}
                    if(!job.adoption.advance(CAPTURE_QUANTUM))break;
                    job.capture=job.adoption.result();job.adoption=null;
                    job.changedInputs=job.capture.changedCells().length;
                    if(working.engine==null)job.stage=Stage.SKY_CAPTURE;
                    else {
                        if(job.rebuild)working.engine.startRelight();else working.engine.startUpdate(job.capture.changedCells());
                        job.stage=Stage.BLOCK;
                    }
                }
                case BLOCK -> {
                    propagationBatches++;
                    if(working.engine.advance(PROPAGATION_QUANTUM)){job.blockWork=working.engine.completedWork();job.stage=Stage.SKY_CAPTURE;}
                }
                case SKY_CAPTURE -> {
                    if(working.skyEngine==null)job.stage=Stage.PUBLISH;
                    else {
                        var columns=working.skyInput.refreshColumns(world,height,job.ticket.chunks(),job.ticket.dirtySkyColumns(),job.ticket.fullCapture()||job.rebuild);
                        job.sampledSkyColumns=columns.sampledColumns();
                        if(job.rebuild)working.skyEngine.startRelight();
                        else working.skyEngine.startUpdate(working.skyInput.changedRoots(job.capture.opacityChangedCells(),columns.changedColumns()));
                        job.stage=Stage.SKY;
                    }
                    job.capture=null;
                }
                case SKY -> {
                    propagationBatches++;
                    if(working.skyEngine.advance(PROPAGATION_QUANTUM)){job.skyWork=working.skyEngine.completedWork();job.stage=Stage.PUBLISH;}
                }
                case PUBLISH -> {
                    working.totalSampledInputs+=job.sampledInputs;working.fullCaptureEquivalent+=working.input.cellCount();working.captures++;
                    SkyResult sky=working.skyEngine==null?null:new SkyResult(working.skyInput.interior(),job.skyWork,job.sampledSkyColumns,working.skyInput.columnBlockReads());
                    var result=new Result(job.entry.bounds(),job.ticket.revision(),
                            blockEnabled?working.input.interior():null,job.sampledInputs,job.changedInputs,job.blockWork,
                            working.totalSampledInputs,working.fullCaptureEquivalent,working.captures,sky);
                    boolean accepted=job.entry.gate().publish(job.ticket,result);
                    if(accepted) {
                        failures.remove(job.entry);
                        // Full-height columns have a constant virtual SKY boundary above the world.
                        // Bounded manual regions may have changing upper-column inputs and cannot use this check.
                        var b=job.entry.bounds();
                        if(job.entry instanceof ChunkDomainDirectory.Entry && b.y()==height.getMinBlockY()
                                && b.y()+b.sizeY()-1==height.getMaxBlockY())
                            completedInputs.put(job.entry,new CompletedInputs(working.input.checkpoint(),result));
                    }
                    else {
                        staleCompletions++;
                        if(job.entry.gate().ownsChunks(job.ticket))pendingOwners.offer(job.entry);
                    }
                    activeJob=null;finished=true;
                }
            }
        } catch(RuntimeException e) {
            if(job.captureTask!=null)job.captureTask.cancel();if(job.adoption!=null)job.adoption.cancel();
            failed(job.entry,e);activeJob=null;finished=true;
        }
        finally {
            long elapsed=System.nanoTime()-start;phaseNanos[stage.ordinal()]+=elapsed;
            if(stage==Stage.CAPTURE)sharedCaptureNanos+=elapsed;else job.solveNanos+=elapsed;
            if(finished)recordAttempt(job);
        }
    }
    private void recordAttempt(ActiveJob job) {
        if(job.working==null)return;
        if(job.rebuild){rebuildAttempts++;rebuildSolveNanos+=job.solveNanos;}
        else{incrementalAttempts++;incrementalSolveNanos+=job.solveNanos;}
    }
    private void failed(LightDomain<Chunk,Result> entry,RuntimeException e) {
        workers.remove(entry); entry.gate().failed(); failures.put(entry,e.toString());
        LogManager.getLogger("PCLA").error("Experimental RGB domain {} failed; scalar lighting remains active",entry.bounds(),e);
    }

    /** Fresh server-thread inputs, isolated from each worker's mutable arrays. */
    public CapturedRgbRegion captureForVerification() { return captureForVerification(0); }
    public CapturedRgbRegion captureForVerification(int slot) {
        return capture(regions.get(slot));
    }
    public CapturedRgbRegion captureColumn(int x,int z) { return capture(columns.get(x,z)); }
    /** Read-only diagnostic of one loaded input; never obtains an unloaded chunk. */
    public CapturedRgbRegion.Input sampleInput(int x,int y,int z) {
        if(!Boolean.getBoolean("pcla.networkDiagnostics"))throw new IllegalStateException("Input sampling requires networkDiagnostics");
        Chunk chunk; synchronized(inputEvents){chunk=columns.loadedChunk(RegionPublication.key(x>>4,z>>4));}
        if(chunk==null)throw new IllegalStateException("Input chunk unavailable");
        var value=new CapturedRgbRegion.Input();
        MinecraftRgbInputs.read(world,height,chunk,new net.minecraft.util.math.BlockPos.MutableBlockPos(),x,y,z,value);return value;
    }
    private CapturedRgbRegion capture(LightDomain<Chunk,Result> entry) {
        var ticket = entry == null ? null : entry.gate().begin();
        if (ticket == null) throw new IllegalStateException("Region halo unavailable");
        var captured = new CapturedRgbRegion(entry.bounds(),false,height.getMinBlockY(),height.getMaxBlockY());
        captured.capture(world,height,ticket.chunks());
        return captured;
    }
    /** Fresh sky boundary and geometry inputs, separate from worker staging. */
    public CapturedSkyRegion captureSkyForVerification(int slot) {
        return captureSky(regions.get(slot));
    }
    public CapturedSkyRegion captureColumnSky(int x,int z) { return captureSky(columns.get(x,z)); }
    private CapturedSkyRegion captureSky(LightDomain<Chunk,Result> entry) {
        if(!skyEnabled) throw new IllegalStateException("RGB sky is not enabled in this world");
        var ticket=entry==null ? null : entry.gate().begin();
        if(ticket==null) throw new IllegalStateException("Sky region halo unavailable");
        var input=new CapturedRgbRegion(entry.bounds(),false,height.getMinBlockY(),height.getMaxBlockY()); input.capture(world,height,ticket.chunks());
        var captured=new CapturedSkyRegion(input,height.getMinBlockY(),height.getMaxBlockY());
        captured.refreshColumns(world,height,ticket.chunks(),new BitSet(),true);
        return captured;
    }
    public void close() {
        synchronized(inputEvents) { closed=true;pendingOwners.close();manualColumns.clear();automaticColumns.clear();nativeReady.clear();readinessCandidates.clear();regions.close(); columns.close(); }
        var worker=rgbWorker;if(worker!=null)worker.requestStop();
        // The host requests worker termination after closing its backend. Wait for
        // only the current bounded slice before releasing all chunk references.
        synchronized(workerLifecycle) {
            chunkWorld.discard();workers.retain(List.of());completedInputs.clear();failures.clear();sharedInputs.retain(List.of());
            activeJob=null;workerStats=workers.stats();sharedStats=sharedInputs.stats();noopStats=new NoopStats(unchangedChecks,checkReads,0);
            sharedChunkStats=new SharedChunkStats(sharedChunks,0,chunkWorld.reads(),chunkWorld.batches(),chunkWorld.publications(),"IDLE",chunkWorld.cacheHits(),chunkWorld.cacheMisses());
            continuationStats=new ContinuationStats(wakeBlockWorker!=null,continuationTurns,continuationYields,continuationVisits,0,maximumTurnNanos);
            pipelineStats=new PipelineStats("IDLE",propagationBatches,captureBatches,yieldedJobs,cancelledJobs,staleCompletions,
                    phaseNanos[Stage.ADOPT.ordinal()],phaseNanos[Stage.BLOCK.ordinal()],phaseNanos[Stage.SKY_CAPTURE.ordinal()],phaseNanos[Stage.SKY.ordinal()],phaseNanos[Stage.PUBLISH.ordinal()]);
        }
        if(worker!=null&&!worker.awaitStopped(1000))LogManager.getLogger("PCLA").warn("RGB worker is still completing shutdown for dimension {}",world.provider.getDimension());
        synchronized (WORLDS) { WORLDS.remove(world); }
    }
}
