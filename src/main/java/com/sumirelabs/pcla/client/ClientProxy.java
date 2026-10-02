package com.sumirelabs.pcla.client;

import com.sumirelabs.pcla.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.apache.logging.log4j.LogManager;

/** All world/connection changes and packet application are serialized on the client thread. */
public final class ClientProxy extends CommonProxy {
    public static final ClientRegionStore STORE=new ClientRegionStore();
    private Object connection;
    private World world;
    private int skyBucket=-1;
    private final java.util.Set<Integer> awaitingResync=new java.util.HashSet<>();
    public static long deltasApplied,resyncRequests;
    private final SettingsSession settingsSession=new SettingsSession();
    public void configure(com.sumirelabs.pcla.config.LightingConfig fallback,java.nio.file.Path directory) {
        var path=directory.resolve("pcla-client.json");
        ClientDisplaySettings.configure(java.nio.file.Files.exists(path)?com.sumirelabs.pcla.config.ConfigFile.load(path):fallback);
    }
    public void initialize() {
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new RenderPerformanceProbe());
        MinecraftForge.EVENT_BUS.register(new VisualLatencyProbe());
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new ClientRgbCommand());
    }
    private void refreshContext() {
        var mc=Minecraft.getMinecraft();
        if(connection!=mc.getConnection() || world!=mc.world)VisualLatencyProbe.close();
        if(connection!=mc.getConnection()) { ClientLightmap.clear();FluidTint.clearProbes();STORE.clear();settingsSession.clear();ClientDisplaySettings.clear();awaitingResync.clear();deltasApplied=resyncRequests=0; connection=mc.getConnection(); world=mc.world; }
        else if(world!=mc.world) {ClientLightmap.clear();FluidTint.clearProbes();var old=STORE.snapshot();settingsSession.retire(old==null?0:old.epoch());ClientDisplaySettings.clear();STORE.invalidateWorld();awaitingResync.clear(); world=mc.world; }
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.START) return;
        refreshContext();
        ClientLightmap.tick();
        if(BlockTint.INSTALLED && world!=null) {
            int bucket=Math.round(Math.max(0,Math.min(1,world.getSunBrightness(1)))*16);
            BlockTint.daylight=bucket/16.0f;
            if(bucket!=skyBucket) { skyBucket=bucket; if(ClientLightmap.snapshot()==null)invalidate(STORE.snapshot()); }
        }
    }
    static void invalidate(ClientRegionStore.Snapshot snapshot) {
        var mc=Minecraft.getMinecraft();
        if(!BlockTint.INSTALLED || snapshot==null || mc.world==null) return;
        for(var frame:snapshot.regions().values()) {
            invalidateFrame(frame);
        }
    }
    private static void invalidateFrame(RegionFrame frame) {
        var mc=Minecraft.getMinecraft();if(!BlockTint.INSTALLED || frame==null || mc.world==null)return;
        var b=frame.bounds;mc.world.markBlockRangeForRenderUpdate(b.x()-1,b.y()-1,b.z()-1,b.x()+b.sizeX(),b.y()+b.sizeY(),b.z()+b.sizeZ());
    }
    public void receive(RegionFrame frame,MessageContext context) {
        var sender=context.getClientHandler();
        Minecraft.getMinecraft().addScheduledTask(()-> {
            var mc=Minecraft.getMinecraft(); refreshContext();
            if(mc.getConnection()!=sender || mc.world==null) return;
            // Settings-capable peers start with one settings+hello packet, never an unpaired hello.
            if(frame.kind==RegionFrame.Kind.HELLO && RegisteredRgbPeers.supportsSettings(sender.getNetworkManager()))return;
            applyFrame(frame,context,null);
        });
    }
    public void receiveSettings(ServerSettings settings,MessageContext context) {
        var sender=context.getClientHandler();
        Minecraft.getMinecraft().addScheduledTask(()-> {
            var mc=Minecraft.getMinecraft();refreshContext();
            if(mc.getConnection()!=sender || mc.world==null || !settingsSession.accept(settings,mc.world.provider.getDimension()))return;
            applyFrame(settings.hello(),context,settings);
        });
    }
    private void applyFrame(RegionFrame frame,MessageContext context,ServerSettings settings) {
            var mc=Minecraft.getMinecraft();
            var previous=STORE.snapshot();
            if(!STORE.apply(frame,mc.world.provider.getDimension())) return;
            if(frame.kind==RegionFrame.Kind.HELLO)ClientDisplaySettings.install(frame,settings);
            if(frame.kind==RegionFrame.Kind.HELLO)awaitingResync.clear();else awaitingResync.remove(frame.slot);
            if(STORE.snapshot()!=previous) {
                if(frame.kind==RegionFrame.Kind.HELLO)invalidate(previous);
                else {
                    var old=previous==null?null:previous.regions().get(frame.slot);var next=STORE.snapshot().regions().get(frame.slot);
                    if(old!=null && (next==null || !old.bounds.equals(next.bounds)))invalidateFrame(old);
                    invalidateFrame(next);
                }
            }
            if(frame.kind==RegionFrame.Kind.HELLO) {
                RgbNetwork.channel(frame.protocol).sendToServer(new RgbNetwork.AckMessage(frame.session));
                LogManager.getLogger("PCLA").debug("RGB client session {} epoch {}",frame.session,frame.epoch);
            } else if(Boolean.getBoolean("pcla.networkDiagnostics")) {
                var installed=STORE.snapshot().regions().get(frame.slot);
                LogManager.getLogger("PCLA").info("RGB RECEIVE protocol={} session={} seq={} slot={} kind={} digest={} skyDigest={} timeMs={} x={} z={}",
                        frame.protocol,frame.session,frame.sequence,frame.slot,frame.kind,installed==null ? "removed" : FrameDigest.sha256(installed),installed==null ? "removed" : FrameDigest.skySha256(installed),System.currentTimeMillis(),frame.bounds==null?null:frame.bounds.x(),frame.bounds==null?null:frame.bounds.z());
            }
    }
    public static boolean forgetCurrentColumn() {
        var mc=Minecraft.getMinecraft();var snapshot=STORE.snapshot();if(snapshot==null || mc.player==null)return false;
        var pos=mc.player.getPosition();
        for(var entry:snapshot.regions().entrySet())if(entry.getKey()>=4 && entry.getValue().contains(pos.getX(),pos.getY(),pos.getZ())) {
            var removed=STORE.forgetColumn(entry.getKey());if(removed==null)return false;invalidateFrame(removed);return true;
        }
        return false;
    }
    public void receiveDelta(SectionDelta delta,MessageContext context) {
        var sender=context.getClientHandler();
        Minecraft.getMinecraft().addScheduledTask(()-> {
            var mc=Minecraft.getMinecraft();refreshContext();
            if(mc.getConnection()!=sender || mc.world==null)return;
            var result=STORE.applyDelta(delta,mc.world.provider.getDimension());
            if(result==ClientRegionStore.DeltaResult.RESYNC) {
                if(awaitingResync.add(delta.slot)) {
                    resyncRequests++;RgbNetwork.CHANNEL_V4.sendToServer(new RgbNetwork.ResyncMessage(delta.session,delta.epoch,delta.slot));
                    LogManager.getLogger("PCLA").debug("RGB baseline missing; resync slot {}",delta.slot);
                }
                return;
            }
            if(result!=ClientRegionStore.DeltaResult.APPLIED)return;
            deltasApplied++;
            if(BlockTint.INSTALLED)for(int y=0;y<delta.bounds.sectionsY();y++)if(((delta.blockMask|delta.skyMask)&(1<<y))!=0) {
                var b=delta.bounds;int low=b.y()+16*y;
                mc.world.markBlockRangeForRenderUpdate(b.x()-1,low-1,b.z()-1,b.x()+16,low+16,b.z()+16);
            }
            if(Boolean.getBoolean("pcla.networkDiagnostics")) {
                var installed=STORE.snapshot().regions().get(delta.slot);
                LogManager.getLogger("PCLA").info("RGB RECEIVE protocol=4 session={} seq={} slot={} kind=PATCH digest={} skyDigest={}",
                        delta.session,delta.sequence,delta.slot,FrameDigest.sha256(installed),FrameDigest.skySha256(installed));
            }
        });
    }
}
