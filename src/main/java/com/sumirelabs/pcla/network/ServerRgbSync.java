package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.Pcla;
import com.sumirelabs.pcla.config.RuntimeOptions;
import com.sumirelabs.pcla.light.world.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Paired replacements with capability negotiation and bounded per-player automatic streams. */
public final class ServerRgbSync {
    private static final AtomicLong EPOCHS=new AtomicLong();
    private static final Map<EntityPlayerMP,Stream> STREAMS=new IdentityHashMap<>();
    private static final Map<EntityPlayerMP,Set<Long>> TARGETS=new IdentityHashMap<>();
    private record Desired(World world,int x,int z,int radius,List<Long> columns) {}
    private static final Map<EntityPlayerMP,Desired> DESIRED=new IdentityHashMap<>();
    private static final Set<World> AUTOMATIC_WORLDS=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final int AUTOMATIC_SENDS_PER_TICK=Math.max(1,Math.min(64,Integer.getInteger("pcla.sendColumnsPerTick",16)));
    private static long fullMessages,deltaMessages,encodedBytes,fullEquivalentBytes,resyncs;
    public record TransferStats(long fullMessages,long deltaMessages,long encodedBytes,long fullEquivalentBytes,long resyncs) {}
    public static TransferStats transferStats(){return new TransferStats(fullMessages,deltaMessages,encodedBytes,fullEquivalentBytes,resyncs);}
    public record LifecycleStats(int streams,int acknowledgedStreams,int targetPlayers,int desiredPlayers,int trackedWorlds,int retainedSentSlots) {}
    /** Diagnostic read on the server thread; never exposes player objects or mutable transfer state. */
    public static LifecycleStats lifecycleStats() {
        var server=FMLCommonHandler.instance().getMinecraftServerInstance();
        if(server!=null && !server.isCallingFromMinecraftThread())throw new IllegalStateException("Server-thread diagnostic required");
        int acknowledged=0,retained=0;
        for(var stream:STREAMS.values()){if(stream.acknowledged)acknowledged++;for(var result:stream.sent)if(result!=null)retained++;}
        return new LifecycleStats(STREAMS.size(),acknowledged,TARGETS.size(),DESIRED.size(),AUTOMATIC_WORLDS.size(),retained);
    }
    private static final class Stream {
        final World world;final UUID session=UUID.randomUUID();final long epoch=EPOCHS.incrementAndGet();
        final ExperimentalRgbBackend.Result[] sent;
        final long[] sentSequence;
        final ResyncGate recovery;
        final boolean settingsRequired;
        final AutomaticSlots automatic=new AutomaticSlots();
        boolean acknowledged;int helloTicks,sendCursor;final int protocol;long sequence;
        Stream(World world,int protocol,boolean settingsRequired){this.world=world;this.protocol=protocol;this.settingsRequired=settingsRequired;sent=new ExperimentalRgbBackend.Result[protocol>=3?RegionFrame.MAX_STREAM_SLOTS:RegionDirectory.MAX_REGIONS];sentSequence=new long[sent.length];recovery=new ResyncGate(sent.length);}
    }
    public static void acknowledge(EntityPlayerMP player,UUID session) {
        var stream=STREAMS.get(player);
        if(stream!=null && stream.world==player.world && stream.session.equals(session))stream.acknowledged=true;
    }
    public static Set<Long> automaticTargets(EntityPlayerMP player){return TARGETS.getOrDefault(player,Set.of());}
    public static void resynchronize(EntityPlayerMP player,UUID session,long epoch,int slot) {
        var stream=STREAMS.get(player);
        if(stream==null || stream.protocol!=4 || stream.world!=player.world || !stream.acknowledged
                || !stream.session.equals(session) || stream.epoch!=epoch || slot<4 || slot>=stream.sent.length
                || stream.sent[slot]==null)return;
        if(stream.recovery.request(slot))resyncs++;
    }
    public static void clear() {
        for(var world:AUTOMATIC_WORLDS){var backend=ExperimentalRgbBackend.get(world);if(backend!=null)backend.synchronizeAutomaticColumns(Set.of());}
        AUTOMATIC_WORLDS.clear();TARGETS.clear();DESIRED.clear();STREAMS.clear();
        fullMessages=deltaMessages=encodedBytes=fullEquivalentBytes=resyncs=0;
    }
    private static boolean hasAddon(EntityPlayerMP player) {
        var dispatcher=NetworkDispatcher.get(player.connection.netManager);
        return dispatcher!=null && dispatcher.getModList().containsKey("pcla");
    }
    private static void updateInterests(List<EntityPlayerMP> players) {
        DESIRED.keySet().retainAll(players);
        var desired=new IdentityHashMap<World,Map<EntityPlayerMP,List<Long>>>();
        if(RuntimeOptions.AUTOMATIC)for(var player:players) {
            var backend=ExperimentalRgbBackend.get(player.world);
            if(backend==null || !backend.automaticEnabled() || !hasAddon(player)
                    || !(RegisteredRgbPeers.supportsV3(player.connection.netManager)||RegisteredRgbPeers.supportsV4(player.connection.netManager)))continue;
            var pos=player.getPosition();
            int x=pos.getX()>>4,z=pos.getZ()>>4,radius=RuntimeOptions.automaticRadius();var cached=DESIRED.get(player);
            if(cached==null || cached.world()!=player.world || cached.x()!=x || cached.z()!=z || cached.radius()!=radius){cached=new Desired(player.world,x,z,radius,AutomaticInterest.around(x,z,radius));DESIRED.put(player,cached);}
            desired.computeIfAbsent(player.world,w->new LinkedHashMap<>()).put(player,cached.columns());
        }
        var nextTargets=new IdentityHashMap<EntityPlayerMP,Set<Long>>();
        for(var entry:desired.entrySet()) {
            var allocation=AutomaticInterest.allocate(entry.getValue(),AutomaticInterest.WORLD_LIMIT);
            ExperimentalRgbBackend.get(entry.getKey()).synchronizeAutomaticColumns(allocation.columns());
            nextTargets.putAll(allocation.players());
        }
        for(var old:AUTOMATIC_WORLDS)if(!desired.containsKey(old)){var backend=ExperimentalRgbBackend.get(old);if(backend!=null)backend.synchronizeAutomaticColumns(Set.of());}
        if(Boolean.getBoolean("pcla.networkDiagnostics"))nextTargets.forEach((player,columns)->{
            if(!columns.equals(TARGETS.get(player)))org.apache.logging.log4j.LogManager.getLogger("PCLA").info("RGB AUTO interest player={} dimension={} center=({}, {}) columns={}",
                    player.getName(),player.dimension,player.getPosition().getX()>>4,player.getPosition().getZ()>>4,columns.size());
        });
        TARGETS.clear();TARGETS.putAll(nextTargets);AUTOMATIC_WORLDS.clear();AUTOMATIC_WORLDS.addAll(desired.keySet());
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        var server=FMLCommonHandler.instance().getMinecraftServerInstance();if(server==null)return;
        var players=server.getPlayerList().getPlayers();STREAMS.keySet().retainAll(players);updateInterests(players);
        for(var player:players) {
            var backend=ExperimentalRgbBackend.get(player.world);
            boolean settings=RegisteredRgbPeers.supportsSettings(player.connection.netManager);
            if(backend==null && !settings){STREAMS.remove(player);continue;}if(!hasAddon(player))continue;
            int protocol=backend!=null && backend.automaticEnabled() && RegisteredRgbPeers.supportsV4(player.connection.netManager)?4:
                    backend!=null && backend.automaticEnabled() && RegisteredRgbPeers.supportsV3(player.connection.netManager)?3:RegisteredRgbPeers.supportsV2(player.connection.netManager)?2:1;
            var stream=STREAMS.get(player);
            if(stream==null || stream.world!=player.world || stream.protocol!=protocol || stream.settingsRequired!=settings){stream=new Stream(player.world,protocol,settings);STREAMS.put(player,stream);}
            if(!stream.acknowledged){if(stream.helloTicks++%20==0) {
                if(settings)RgbNetwork.SETTINGS.sendTo(new RgbNetwork.SettingsMessage(new ServerSettings(stream.session,stream.epoch,player.dimension,stream.protocol,Pcla.startupConfig().simulationProfile(),Pcla.startupConfig())),player);
                else send(player,hello(stream,player.dimension));
            }continue;}
            if(backend==null)continue;
            for(int slot=0;slot<RegionDirectory.MAX_REGIONS;slot++)syncSlot(player,stream,slot,backend.displaySnapshot(slot));
            if(protocol<3)continue;
            var desired=automaticTargets(player);
            if(!stream.automatic.chunks().equals(desired))stream.sendCursor=0;
            for(var removed:stream.automatic.update(desired))syncSlot(player,stream,removed.slot(),null);
            var order=new ArrayList<>(desired);int sent=0,count=order.size();
            long deadline=System.nanoTime()+2_000_000L,bytesBefore=encodedBytes;
            // Existing visible columns get their edits before newly admitted distant columns.
            // The count/time/byte bounds apply across both passes; one packet may exceed
            // the cooperative time target, so this is not a hard server-thread deadline.
            sendColumns: for(int pass=0;pass<2;pass++)for(int checked=0;checked<count;checked++) {
                // Newly ready near columns must not sit behind the rotating edit
                // cursor after farther initial columns have already been sent.
                int index=pass==0?(stream.sendCursor+checked)%count:checked;long key=order.get(index);int slot=stream.automatic.slot(key);
                if((stream.sent[slot]!=null)!=(pass==0))continue;
                if(syncSlot(player,stream,slot,backend.columnDisplaySnapshot((int)key,(int)(key>>32)))) {
                    if(++sent==AUTOMATIC_SENDS_PER_TICK || encodedBytes-bytesBefore>=2_097_152L || System.nanoTime()>=deadline){stream.sendCursor=(index+1)%count;break sendColumns;}
                }
            }
        }
    }
    private static boolean syncSlot(EntityPlayerMP player,Stream stream,int slot,ExperimentalRgbBackend.Result result) {
        var previous=stream.sent[slot];int protocol=stream.protocol;
        if(result!=null && result.light()==null && (protocol==1 || result.sky()==null))result=null;
        if(result==null) {
            stream.recovery.retire(slot);stream.sentSequence[slot]=0;
            if(previous==null)return false;
            send(player,remove(stream,++stream.sequence,player.dimension,slot));stream.sent[slot]=null;return true;
        }
        boolean resend=stream.recovery.pending(slot);
        if(resend && !stream.recovery.ready(slot,player.world.getTotalWorldTime()))return false;
        if(!resend && previous!=null && previous.light()==result.light() && (protocol==1 || skyRoot(previous)==skyRoot(result)) && previous.bounds().equals(result.bounds()))return false;
        var backend=ExperimentalRgbBackend.get(player.world);
        if(backend==null)return false;
        var latest=slot<4?backend.displaySnapshot(slot):backend.columnDisplaySnapshot(result.bounds().x()>>4,result.bounds().z()>>4);
        if(latest!=result)return false;
        if(protocol==4 && slot>=4 && !resend && previous!=null && previous.bounds().equals(result.bounds())
                && (previous.light()==null)==(result.light()==null) && (skyRoot(previous)==null)==(skyRoot(result)==null)) {
            var delta=SectionDelta.between(stream.session,stream.epoch,stream.sequence+1,stream.sentSequence[slot],player.dimension,slot,result.bounds(),
                    previous.light(),skyRoot(previous),result.light(),skyRoot(result));
            if(delta==null){stream.sent[slot]=result;return false;}
            int fullSize=56+result.bounds().outputCells()*2*((result.light()!=null?1:0)+(skyRoot(result)!=null?1:0));
            if(delta.encodedBytes()<fullSize) {
                stream.sequence++;RgbNetwork.CHANNEL_V4.sendTo(new RgbNetwork.FrameMessageV4(RegionFrameV4Codec.Packet.patch(delta)),player);
                deltaMessages++;encodedBytes+=delta.encodedBytes();fullEquivalentBytes+=fullSize;
                if(Boolean.getBoolean("pcla.networkDiagnostics")) {
                    var expected=upsertResult(stream,stream.sequence,player.dimension,slot,result);
                    org.apache.logging.log4j.LogManager.getLogger("PCLA").info("RGB SEND protocol=4 session={} seq={} slot={} kind=PATCH digest={} skyDigest={} bytes={} fullBytes={} base={} blockMask={} skyMask={} timeMs={} x={} z={}",
                            stream.session,stream.sequence,slot,FrameDigest.sha256(expected),FrameDigest.skySha256(expected),delta.encodedBytes(),fullSize,delta.baseSequence,delta.blockMask,delta.skyMask,System.currentTimeMillis(),result.bounds().x(),result.bounds().z());
                }
                stream.sent[slot]=result;stream.sentSequence[slot]=stream.sequence;return true;
            }
        }
        send(player,upsertResult(stream,++stream.sequence,player.dimension,slot,result));
        stream.sent[slot]=result;stream.sentSequence[slot]=stream.sequence;stream.recovery.sent(slot,player.world.getTotalWorldTime());return true;
    }
    private static RegionFrame hello(Stream stream,int dimension) {
        String profile=Pcla.startupConfig().simulationProfile();
        return stream.protocol==4?RegionFrame.helloV4(stream.session,stream.epoch,dimension,profile):stream.protocol==3?RegionFrame.helloV3(stream.session,stream.epoch,dimension,profile):stream.protocol==2?RegionFrame.helloV2(stream.session,stream.epoch,dimension,profile):RegionFrame.hello(stream.session,stream.epoch,dimension,profile);
    }
    private static RegionFrame remove(Stream stream,long sequence,int dimension,int slot) {
        return stream.protocol==4?RegionFrame.removeV4(stream.session,stream.epoch,sequence,dimension,slot):stream.protocol==3?RegionFrame.removeV3(stream.session,stream.epoch,sequence,dimension,slot):stream.protocol==2?RegionFrame.removeV2(stream.session,stream.epoch,sequence,dimension,slot):RegionFrame.remove(stream.session,stream.epoch,sequence,dimension,slot);
    }
    private static RegionFrame upsertResult(Stream stream,long sequence,int dimension,int slot,ExperimentalRgbBackend.Result result) {
        if(stream.protocol>=3 && slot>=4)return RegionFrame.upsertSnapshot(stream.protocol,stream.session,stream.epoch,
                sequence,dimension,slot,result.bounds(),result.light(),skyRoot(result));
        return upsert(stream,sequence,dimension,slot,result.bounds(),copy(result.light()),stream.protocol>=2?copy(skyRoot(result)):null);
    }
    private static RegionFrame upsert(Stream stream,long sequence,int dimension,int slot,CapturedRgbRegion.Bounds bounds,short[] block,short[] sky) {
        return stream.protocol==4?RegionFrame.upsertV4(stream.session,stream.epoch,sequence,dimension,slot,bounds,block,sky):stream.protocol==3?RegionFrame.upsertV3(stream.session,stream.epoch,sequence,dimension,slot,bounds,block,sky):stream.protocol==2?RegionFrame.upsertV2(stream.session,stream.epoch,sequence,dimension,slot,bounds,block,sky):RegionFrame.upsert(stream.session,stream.epoch,sequence,dimension,slot,bounds,block);
    }
    private static com.sumirelabs.pcla.light.storage.RgbLightVolume.Snapshot skyRoot(ExperimentalRgbBackend.Result result){return result.sky()==null?null:result.sky().light();}
    private static short[] copy(com.sumirelabs.pcla.light.storage.RgbLightVolume.Snapshot root){if(root==null)return null;short[] values=new short[root.cellCount()];root.copyTo(values);return values;}
    private static void send(EntityPlayerMP player,RegionFrame frame) {
        if(frame.protocol==4)RgbNetwork.CHANNEL_V4.sendTo(new RgbNetwork.FrameMessageV4(RegionFrameV4Codec.Packet.full(frame)),player);
        else if(frame.protocol==3)RgbNetwork.CHANNEL_V3.sendTo(new RgbNetwork.FrameMessageV3(frame),player);
        else if(frame.protocol==2)RgbNetwork.CHANNEL_V2.sendTo(new RgbNetwork.FrameMessageV2(frame),player);
        else RgbNetwork.CHANNEL.sendTo(new RgbNetwork.FrameMessage(frame),player);
        if(frame.protocol==4 && frame.kind==RegionFrame.Kind.UPSERT) {
            long size=56L+frame.cells()*2L*((frame.hasBlock()?1:0)+(frame.hasSky()?1:0));
            fullMessages++;encodedBytes+=size;fullEquivalentBytes+=size;
        }
        if(Boolean.getBoolean("pcla.networkDiagnostics") && frame.kind!=RegionFrame.Kind.HELLO)
            org.apache.logging.log4j.LogManager.getLogger("PCLA").info("RGB SEND protocol={} session={} seq={} slot={} kind={} digest={} skyDigest={} timeMs={} x={} z={}",
                    frame.protocol,frame.session,frame.sequence,frame.slot,frame.kind,frame.kind==RegionFrame.Kind.REMOVE?"removed":FrameDigest.sha256(frame),FrameDigest.skySha256(frame),System.currentTimeMillis(),frame.bounds==null?null:frame.bounds.x(),frame.bounds==null?null:frame.bounds.z());
    }
}
