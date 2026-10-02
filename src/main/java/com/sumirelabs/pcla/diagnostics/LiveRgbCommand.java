package com.sumirelabs.pcla.diagnostics;

import com.google.gson.*;
import com.sumirelabs.pcla.light.engine.RgbBlockAccess;
import com.sumirelabs.pcla.light.world.*;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.*;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.nio.file.*;
import java.util.*;

/** Developer commands for the explicitly enabled bounded world pilot. */
public final class LiveRgbCommand extends CommandBase {
    private static final Set<Run> RUNS = new HashSet<>();
    public static boolean runningTests() { return !RUNS.isEmpty(); }
    public String getName() { return "pcla_live"; }
    public List<String> getAliases() { return List.of("pcl"); }
    public String getUsage(ICommandSender sender) { return "/pcla_live [multi|add|remove <slot>|status|verify|verifyall|verifysky|test|testmulti|testregions|testresume|testtravel|preview|shapes|entities|sky|fluids|colors|skytest|clearpreview]"; }
    public int getRequiredPermissionLevel() { return 2; }
    @Override public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos target) {
        return args.length==1 ? getListOfStringsMatchingLastWord(args,"multi","add","remove","status","verify","verifyall","verifysky","test","testmulti","testregions","testresume","testtravel","preview","shapes","entities","sky","fluids","colors","skytest","clearpreview") : List.of();
    }
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if(ColumnRgbCommand.running()) throw new CommandException("Finish the column RGB diagnostic first");
        World world = sender.getEntityWorld();
        var backend = ExperimentalRgbBackend.get(world);
        if (backend == null) throw new CommandException("No experimental RGB backend for this world");
        if(ColorPreview.running() && !(args.length==1 && Set.of("clearpreview","status","verify","verifyall","verifysky").contains(args[0])))
            throw new CommandException("Finish the preview with clearpreview first");
        try {
            if (args.length == 0 || args.length == 1 && Set.of("multi","test","testmulti","testregions","testresume").contains(args[0])) {
                if ((!RUNS.isEmpty() || RegionTravelTest.running())) throw new IllegalStateException("A live RGB test is already running");
                BlockPos at = sender.getPosition();
                boolean resume=args.length==1 && args[0].equals("testresume");
                boolean paired = resume || args.length==1 && args[0].equals("testregions");
                backend.watch(at.getX(), at.getY(), at.getZ(), args.length==1 && (args[0].endsWith("multi") || paired) ? 2 : 1);
                int auxiliary = paired ? backend.add(at.getX()-16,at.getY(),at.getZ(),2) : -1;
                if(resume) {
                    if(!backend.requestRgbRecalculation()) {backend.remove(auxiliary);throw new IllegalStateException("This test requires the continuation-capable Pulsar companion");}
                } else ((PulsarWorld)world).pulsar$getLightManager().queueLightCheck(net.minecraft.world.EnumSkyBlock.BLOCK, at.getX(), at.getY(), at.getZ());
                if (args.length == 1 && args[0].startsWith("test")) {
                    Run run = new Run(sender, world, backend, auxiliary);
                    run.report.addProperty("initialScheduling",resume?"backend_continuation_without_scalar_request":"scalar_light_request");
                    RUNS.add(run); MinecraftForge.EVENT_BUS.register(run);
                    say(sender, "PCLA live test started; one air cell will be temporarily changed and restored.");
                } else say(sender, "PCLA region selected. Automatic server RGB updates enabled here; clients may enable the block tint preview.");
            } else if (args.length == 1 && args[0].equals("add")) {
                if ((!RUNS.isEmpty() || RegionTravelTest.running())) throw new IllegalStateException("A live RGB test is already running");
                var at = sender.getPosition();
                int slot = backend.add(at.getX(),at.getY(),at.getZ(),2);
                ((PulsarWorld)world).pulsar$getLightManager().queueLightCheck(net.minecraft.world.EnumSkyBlock.BLOCK,at.getX(),at.getY(),at.getZ());
                say(sender,"PCLA auxiliary region added: slot="+slot);
            } else if (args.length == 2 && args[0].equals("remove")) {
                if ((!RUNS.isEmpty() || RegionTravelTest.running())) throw new IllegalStateException("A live RGB test is already running");
                say(sender,"PCLA region removed="+backend.remove(parseInt(args[1],0,RegionDirectory.MAX_REGIONS-1)));
            } else if(args.length==1 && args[0].equals("skytest")) {
                if(!RUNS.isEmpty() || RegionTravelTest.running()) throw new IllegalStateException("A live test is running");
                try { SkyUpdateTest.start(getCommandSenderAsPlayer(sender),backend); }
                catch(java.io.IOException e) { throw new IllegalStateException(e); }
            } else if(args.length==1 && args[0].equals("colors")) {
                if(!RUNS.isEmpty() || RegionTravelTest.running())throw new IllegalStateException("A live test is running");
                try{ColorPreview.startPalette(getCommandSenderAsPlayer(sender),backend);}catch(java.io.IOException e){throw new IllegalStateException(e);}
            } else if (args.length==1 && Set.of("preview","shapes","entities","sky","fluids").contains(args[0])) {
                if(!RUNS.isEmpty() || RegionTravelTest.running()) throw new IllegalStateException("A live test is running");
                try { ColorPreview.start(getCommandSenderAsPlayer(sender),backend,args[0].equals("shapes"),args[0].equals("entities") || args[0].equals("sky") || args[0].equals("fluids"),args[0].equals("sky"),args[0].equals("fluids")); }
                catch(java.io.IOException e) { throw new IllegalStateException(e); }
            } else if (args.length==1 && args[0].equals("clearpreview")) {
                ColorPreview.stop(); say(sender,"PCLA preview cleanup complete; see recovery log for retained external edits.");
            } else if (args.length == 1 && args[0].equals("testtravel")) {
                if (!RUNS.isEmpty() || RegionTravelTest.running()) throw new IllegalStateException("A live RGB test is already running");
                try { RegionTravelTest.start(getCommandSenderAsPlayer(sender),backend); }
                catch (java.io.IOException e) { throw new IllegalStateException(e); }
            } else if (args.length == 1 && args[0].equals("status")) {
                say(sender,"PCLA active region slots: "+backend.slots());
                for (int slot : backend.slots()) {
                    var result = backend.snapshot(slot);
                    say(sender,result == null ? "slot="+slot+" unavailable; failure="+backend.failure(slot)
                            : "slot="+slot+" ready: revision="+result.revision()+", changedInputs="+result.changedInputs()+", "+result.work());
                }
            } else if (args.length == 1 && args[0].equals("verify")) {
                say(sender, verify(backend).toString());
            } else if(args.length==1 && args[0].equals("verifysky")) {
                say(sender,SkyVerification.verify(backend,0).toString());
            } else if (args.length == 1 && args[0].equals("verifyall")) {
                for (int slot : backend.slots()) say(sender,verify(backend,slot).toString());
            } else throw new WrongUsageException(getUsage(sender));
        } catch (RuntimeException e) { throw new CommandException(e.toString()); }
    }
    private static void say(ICommandSender sender, String message) { sender.sendMessage(new TextComponentString(message)); }

    /** Independent three-scalar relaxation from current world inputs, not the worker's staging arrays. */
    public static JsonObject verify(ExperimentalRgbBackend backend) {
        return verify(backend,0);
    }
    public static JsonObject verify(ExperimentalRgbBackend backend, int slot) {
        var published = backend.snapshot(slot);
        if (published == null) throw new IllegalStateException("RGB result not available yet");
        if(published.light()==null) throw new IllegalStateException("RGB block lane disabled; use verifysky");
        var input = backend.captureForVerification(slot);
        var result=compare(backend,published,input);
        if(backend.snapshot(slot)!=published) throw new IllegalStateException("World changed during verification; retry");
        result.addProperty("slot",slot); return result;
    }
    public static JsonObject verifyColumn(ExperimentalRgbBackend backend,int x,int z) {
        var published=backend.columnSnapshot(x,z);
        if(published==null || published.light()==null) throw new IllegalStateException("Completed column BLOCK light unavailable");
        var result=compare(backend,published,backend.captureColumn(x,z));
        if(backend.columnSnapshot(x,z)!=published) throw new IllegalStateException("Column changed during verification; retry");
        result.addProperty("chunkX",x); result.addProperty("chunkZ",z); return result;
    }
    private static JsonObject compare(ExperimentalRgbBackend backend,ExperimentalRgbBackend.Result published,CapturedRgbRegion input) {
        int[] expected = reference(input);
        int mismatches = 0, colored = 0, lit = 0;
        for (int cell = 0; cell < published.light().cellCount(); cell++) {
            int rgb = published.light().get(cell);
            if (rgb != expected[published.bounds().interiorIndex(cell)]) mismatches++;
            if (rgb != 0) lit++;
            if ((rgb & 15) != (rgb >> 4 & 15) || (rgb & 15) != (rgb >> 8)) colored++;
        }
        var result = new JsonObject();
        result.addProperty("compared", published.light().cellCount()); result.addProperty("mismatches", mismatches);
        result.addProperty("lit", lit); result.addProperty("colored", colored);
        result.addProperty("revision", published.revision()); result.addProperty("changedInputs", published.changedInputs());
        result.addProperty("sampledInputs", published.sampledInputs());
        result.addProperty("totalSampledInputs", published.totalSampledInputs());
        result.addProperty("fullCaptureEquivalent", published.fullCaptureEquivalent());
        result.addProperty("captures", published.captures());
        var shared=backend.sharedInputStats();
        var cache=backend.workerCacheStats();
        result.addProperty("workerCacheLimit",cache.limit());result.addProperty("workerCacheRetained",cache.retained());
        result.addProperty("workerCacheHits",cache.hits());result.addProperty("workerCacheMisses",cache.misses());
        result.addProperty("workerCacheEvictions",cache.evictions());result.addProperty("workerCacheCreations",cache.creations());
        var timing=backend.workerTiming();result.addProperty("workerRebuildAttempts",timing.rebuildAttempts());result.addProperty("workerIncrementalAttempts",timing.incrementalAttempts());
        result.addProperty("workerRebuildSolveNanos",timing.rebuildSolveNanos());result.addProperty("workerIncrementalSolveNanos",timing.incrementalSolveNanos());
        result.addProperty("workerSharedCaptureNanos",timing.sharedCaptureNanos());
        var continuation=backend.continuationStats();result.addProperty("continuationConnected",continuation.connected());
        result.addProperty("continuationTurns",continuation.turns());result.addProperty("continuationYields",continuation.yieldedTurns());
        result.addProperty("continuationVisitedOwners",continuation.visitedOwners());result.addProperty("continuationRemaining",continuation.remaining());
        result.addProperty("continuationMaximumTurnNanos",continuation.maximumTurnNanos());
        var pipeline=backend.pipelineStats();result.addProperty("pipelineStage",pipeline.activeStage());
        result.addProperty("propagationBatches",pipeline.propagationBatches());result.addProperty("yieldedJobs",pipeline.yieldedJobs());
        result.addProperty("captureBatches",pipeline.captureBatches());
        result.addProperty("cancelledJobs",pipeline.cancelledJobs());result.addProperty("staleCompletions",pipeline.staleCompletions());
        result.addProperty("inputAdoptionNanos",pipeline.adoptionNanos());result.addProperty("blockPropagationNanos",pipeline.blockNanos());
        result.addProperty("skyCaptureNanos",pipeline.skyCaptureNanos());result.addProperty("skyPropagationNanos",pipeline.skyNanos());
        result.addProperty("pairedPublicationNanos",pipeline.publicationNanos());
        result.addProperty("sharedWorldReads",shared.worldReads()); result.addProperty("sharedInputSections",shared.sections());
        result.addProperty("sharedInputPages",shared.pages()); result.addProperty("sharedUniformPages",shared.uniformPages());
        result.addProperty("sharedInputArrayPayloadBytes",shared.arrayPayloadBytes());
        result.addProperty("increaseVisits", published.work().increaseVisits());
        result.addProperty("decreaseVisits", published.work().decreaseVisits());
        return result;
    }
    private static int[] reference(RgbBlockAccess view) {
        int[] result = new int[view.cellCount()];
        for (int shift = 0; shift <= 8; shift += 4) {
            int[] lane = new int[result.length];
            for (int cell = 0; cell < lane.length; cell++) lane[cell] = view.emission(cell) >> shift & 15;
            for (int pass = 0; pass < 16; pass++) {
                boolean changed = false;
                for (int cell = 0; cell < lane.length; cell++) for (int d = 0; d < 6; d++) {
                    int from = view.neighbor(cell, d);
                    if (from < 0) continue;
                    int candidate = lane[from] - Math.max(1, view.absorption(from, d ^ 1) >> shift & 15);
                    if (candidate > lane[cell]) { lane[cell] = candidate; changed = true; }
                }
                if (!changed) break;
                if (pass == 15) throw new IllegalStateException("Reference did not converge");
            }
            for (int cell = 0; cell < lane.length; cell++) result[cell] |= lane[cell] << shift;
        }
        return result;
    }

    public static void stopTests() { SkyUpdateTest.stop(); ColorPreview.stop(); RegionTravelTest.stop(); for (Run run : List.copyOf(RUNS)) run.finish("server_stopping"); }

    public static final class Run {
        private static final String[] NAMES = {"baseline", "place_glowstone", "replace_sea_lantern", "replace_glass", "restore_air"};
        private final ICommandSender sender;
        private final World world;
        private final ExperimentalRgbBackend backend;
        private final int auxiliary;
        private long previousAuxiliaryRevision = -1;
        private final JsonObject report = new JsonObject();
        private final JsonArray phases = new JsonArray();
        private BlockPos testPos;
        private IBlockState owned;
        private int stage, ticks;
        private long previousRevision = -1;
        private final Path output = Path.of("logs", "pcla-live-rgb-" + System.currentTimeMillis() + ".json");
        Run(ICommandSender sender, World world, ExperimentalRgbBackend backend, int auxiliary) {
            this.sender = sender; this.world = world; this.backend = backend;
            this.auxiliary = auxiliary;
            report.addProperty("regionCount",auxiliary < 0 ? 1 : 2);
            report.addProperty("scope", "bounded multi-section pilot, automatic block worker callbacks; verifies server RGB values");
            report.add("phases", phases);
        }
        @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
            // Sample before this tick's random block/light rechecks invalidate the prior completed batch.
            if (event.phase != TickEvent.Phase.START) return;
            if (++ticks > 600) { finish("timeout"); return; }
            var result = backend.snapshot();
            if (result == null || result.revision() <= previousRevision) return;
            var other = auxiliary < 0 ? null : backend.snapshot(auxiliary);
            if (auxiliary >= 0 && (other == null || other.revision() <= previousAuxiliaryRevision)) return;
            try {
                if (testPos != null && world.getBlockState(testPos) != owned) throw new IllegalStateException("Test cell changed externally");
                JsonObject check = verify(backend); check.addProperty("phase", NAMES[stage]); phases.add(check);
                if (check.get("mismatches").getAsInt() != 0) throw new IllegalStateException("RGB mismatch");
                if(backend.skyEnabled()) {
                    var sky=SkyVerification.verify(backend,0);check.add("sky",sky);
                    if(sky.get("mismatches").getAsInt()!=0)throw new IllegalStateException("SKY mismatch");
                }
                if (auxiliary >= 0) {
                    var otherCheck = verify(backend,auxiliary);
                    otherCheck.addProperty("bounds",other.bounds().toString());
                    check.add("auxiliary",otherCheck);
                    if (otherCheck.get("mismatches").getAsInt() != 0) throw new IllegalStateException("Auxiliary RGB mismatch");
                    if(backend.skyEnabled()) {
                        var sky=SkyVerification.verify(backend,auxiliary);otherCheck.add("sky",sky);
                        if(sky.get("mismatches").getAsInt()!=0)throw new IllegalStateException("Auxiliary SKY mismatch");
                    }
                    previousAuxiliaryRevision = other.revision();
                }
                if (stage == 4) { finish("passed"); return; }
                if (testPos == null) {
                    var b = result.bounds();
                    report.addProperty("outputSections", b.sectionsX()*b.sectionsY()*b.sectionsZ());
                    report.addProperty("inputCells", b.cells());
                    report.addProperty("bounds", b.toString());
                    // Prefer a source just across the watched chunk border, so its light crosses into the output section.
                    outer: for (int x : new int[]{16, -1, 8}) for (int y = 2; y < 14; y++) for (int z = 2; z < 14; z++) {
                        BlockPos candidate = new BlockPos(b.x()+x, b.y()+y, b.z()+z);
                        if (world.getBlockState(candidate).getBlock() == Blocks.AIR && world.getTileEntity(candidate) == null) {
                            testPos = candidate; break outer;
                        }
                    }
                    if (testPos == null) throw new IllegalStateException("No safe air cell for test");
                    report.addProperty("testPosition", testPos.toString());
                    report.addProperty("originalBlock", "minecraft:air");
                    report.addProperty("crossesChunkBorder", testPos.getX() >> 4 != b.x() >> 4);
                    save(); // Recovery record exists before any world mutation.
                }
                previousRevision = result.revision(); ticks = 0;
                IBlockState next = switch (++stage) {
                    case 1 -> Blocks.GLOWSTONE.getDefaultState();
                    case 2 -> Blocks.SEA_LANTERN.getDefaultState();
                    case 3 -> Blocks.STAINED_GLASS.getDefaultState();
                    default -> Blocks.AIR.getDefaultState();
                };
                if (!world.setBlockState(testPos, next, 2)) throw new IllegalStateException("Test edit rejected");
                owned = next;
                // Deliberately do not queue manually: this must travel through normal block notification hooks.
            } catch (Exception e) { finish(e.toString()); }
        }
        private void save() throws java.io.IOException {
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
        private void finish(String status) {
            boolean restored = testPos == null;
            if (testPos != null && world.isBlockLoaded(testPos)) {
                IBlockState current = world.getBlockState(testPos);
                if (current == owned || current.getBlock() == Blocks.AIR) {
                    world.setBlockState(testPos, Blocks.AIR.getDefaultState(), 2);
                    restored = world.getBlockState(testPos).getBlock() == Blocks.AIR;
                }
            }
            report.addProperty("status", status); report.addProperty("restoredAir", restored);
            if (auxiliary >= 0) report.addProperty("auxiliaryRemoved",backend.remove(auxiliary));
            try { save(); } catch (java.io.IOException e) { status += "; report save failed: " + e; }
            MinecraftForge.EVENT_BUS.unregister(this); RUNS.remove(this);
            say(sender, "PCLA live RGB test " + status + "; restoredAir=" + restored + "; " + output);
        }
    }
}
