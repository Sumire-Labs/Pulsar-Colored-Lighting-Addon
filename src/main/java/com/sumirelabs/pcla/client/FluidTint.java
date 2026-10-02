package com.sumirelabs.pcla.client;

import com.sumirelabs.pcla.network.ClientRegionStore;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Scoped vanilla-liquid hook, also used by Celeritas's fallback for non-model geometry. */
public final class FluidTint {
    public static final LongAdder waterVertices=new LongAdder(),lavaVertices=new LongAdder(),tintedVertices=new LongAdder(),
            celeritasVertices=new LongAdder(),excludedRenders=new LongAdder();
    private static final ThreadLocal<ArrayDeque<Context>> CONTEXT=ThreadLocal.withInitial(ArrayDeque::new);
    private static final class ProbeSession {
        final BlockPos section;final Map<String,Probe> samples=new TreeMap<>();
        ProbeSession(BlockPos section){this.section=section;}
    }
    private static volatile ProbeSession probeSession;
    public record Probe(String block,int x,int y,int z,int sampleX,int sampleY,int sampleZ,int scalar,
                        int rgb,int aboveRgb,int sky,int aboveSky,float daylight,float red,float green,float blue,String access) {}
    private static final class Context {
        final ClientRegionStore.Snapshot snapshot;
        final BlockPos origin;
        final String block,access;
        final int emission;
        final float daylight;
        final DisplayLightmap lightmap;
        final boolean sky;
        int coordinates;
        boolean gpu;
        final float[] tint={1,1,1};
        Context(IBlockAccess access,IBlockState state,BlockPos pos) {
            origin=pos.toImmutable();block=String.valueOf(state.getBlock().getRegistryName());this.access=access.getClass().getName();
            var received=ClientProxy.STORE.snapshot();var liquid=state.getBlock();
            boolean vanilla=liquid==Blocks.WATER || liquid==Blocks.FLOWING_WATER || liquid==Blocks.LAVA || liquid==Blocks.FLOWING_LAVA;
            snapshot=vanilla && BlockTint.enabled && ClientDisplaySettings.blockAllowed(received,state)?received:null;
            emission=snapshot==null?0:Math.max(0,Math.min(15,state.getLightValue(access,pos)));
            daylight=BlockTint.daylight;sky=SkyTint.enabled;
            lightmap=ClientLightmap.snapshot();
            if(snapshot==null)excludedRenders.increment();
        }
    }
    public static void begin(IBlockAccess access,IBlockState state,BlockPos pos){CONTEXT.get().push(new Context(access,state,pos));}
    public static void end(){var stack=CONTEXT.get();if(!stack.isEmpty())stack.pop();}
    public static int depth(){return CONTEXT.get().size();}
    public static void sample(BlockPos pos,int packedLight) {
        var context=CONTEXT.get().peek();if(context==null || context.snapshot==null)return;
        var root=context.snapshot;int x=pos.getX(),y=pos.getY(),z=pos.getZ();
        int rgb=root.sample(x,y,z),above=root.sample(x,y+1,z);
        int sky=context.sky?root.sampleSky(x,y,z):-1,aboveSky=context.sky?root.sampleSky(x,y+1,z):-1;
        int source=context.emission>0?root.sample(context.origin.getX(),context.origin.getY(),context.origin.getZ()):-1;
        context.gpu=RgbGpuLightmap.encodeMesh();
        if(context.gpu) {
            int block=FluidLightTint.merge(rgb,above),skyLight=FluidLightTint.merge(sky,aboveSky);
            if(context.emission>0 && block>=0 && source>=0)block=com.sumirelabs.pcla.light.PackedRgb.union(block,source);
            context.coordinates=RgbLightCoordinates.withFallback(block,skyLight,packedLight);
        } else FluidLightTint.compute(rgb,above,sky,aboveSky,source,context.emission,packedLight,context.daylight,context.tint,context.lightmap);
        var probe=probeSession;var section=probe==null?null:probe.section;
        if(section!=null && (context.origin.getX()>>4)==section.getX() && (context.origin.getY()>>4)==section.getY() && (context.origin.getZ()>>4)==section.getZ()) {
            String key=context.block+":"+context.origin+":"+pos;
            synchronized(probe){if(probe.samples.size()<96 || probe.samples.containsKey(key))probe.samples.put(key,new Probe(context.block,context.origin.getX(),context.origin.getY(),context.origin.getZ(),x,y,z,
                    packedLight,rgb,above,sky,aboveSky,context.daylight,context.tint[0],context.tint[1],context.tint[2],context.access));}
        }
    }
    public static int coordinates(int fallback) {
        var context=CONTEXT.get().peek();return context!=null && context.gpu?context.coordinates:fallback;
    }
    public static BufferBuilder color(BufferBuilder buffer,float red,float green,float blue,float alpha) {
        var context=CONTEXT.get().peek();
        if(context==null || context.snapshot==null)return buffer.color(red,green,blue,alpha);
        if(context.block.endsWith("water"))waterVertices.increment();else lavaVertices.increment();
        // The tested Forge 1.12.2 build retains Embeddium's WorldSliceLocal package.
        if(context.access.startsWith("org.taumc.celeritas.")
                || context.access.equals("org.embeddedt.embeddium.impl.asm.WorldSliceLocal"))celeritasVertices.increment();
        if(context.gpu)return buffer.color(red,green,blue,alpha);
        var tint=context.tint;if(tint[0]!=1 || tint[1]!=1 || tint[2]!=1)tintedVertices.increment();
        return buffer.color(red*tint[0],green*tint[1],blue*tint[2],alpha);
    }
    /** Diagnostic only: capture actual renderer samples in the player's current section. */
    public static void probe(BlockPos player){probeSession=new ProbeSession(new BlockPos(player.getX()>>4,player.getY()>>4,player.getZ()>>4));}
    public static void clearProbes(){probeSession=null;}
    public static List<Probe> probes(){var probe=probeSession;if(probe==null)return List.of();synchronized(probe){return List.copyOf(probe.samples.values());}}
    private FluidTint() {}
}
