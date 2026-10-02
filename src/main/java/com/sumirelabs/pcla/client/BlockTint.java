package com.sumirelabs.pcla.client;

import com.sumirelabs.pcla.Pcla;
import com.sumirelabs.pcla.network.ClientRegionStore;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.LongAdder;

/** Shared quad sampler for GPU light coordinates and the legacy color-multiplier fallback. */
public final class BlockTint {
    public static final boolean INSTALLED=com.sumirelabs.pcla.config.RuntimeOptions.blockTint();
    public static volatile boolean enabled=INSTALLED;
    public static volatile boolean smooth=true;
    public static volatile float daylight=1;
    public static final LongAdder tintedQuads=new LongAdder();
    public static final LongAdder smoothedQuads=new LongAdder();
    public static final LongAdder insetQuads=new LongAdder();
    public static final LongAdder celeritasQuads=new LongAdder();
    public static final LongAdder gpuQuads=new LongAdder();
    private static final int[] PULSAR_FACE={5,4,3,2,1,0};
    private static final ThreadLocal<ArrayDeque<Context>> CONTEXT=ThreadLocal.withInitial(ArrayDeque::new);
    // Read-only terrain diagnostic bypass, scoped to the calling thread only.
    static volatile Thread nativeProbeThread;
    public static final class Context {
        final IBlockAccess access;
        final BlockPos pos;
        final ClientRegionStore.Snapshot snapshot;
        final float daylight;
        final DisplayLightmap lightmap;
        final int emission;
        final boolean smooth;
        final boolean skyTint;
        final boolean gpu;
        final boolean fullCube;
        int[] lightCoordinates;
        boolean coordinatesReady;
        float[][] lightCorners,skyCorners;
        float[][] corners;
        float[] multipliers;
        int[] samples;
        int[] skySamples;
        BlockPos.MutableBlockPos samplePos;
        EnumFacing face;
        BakedQuad quad;
        Context(IBlockAccess access,IBlockState state,BlockPos pos) {
            this.access=access; this.pos=pos.toImmutable(); this.daylight=BlockTint.daylight;
            lightmap=ClientLightmap.snapshot();
            var received=ClientProxy.STORE.snapshot();
            snapshot=nativeProbeThread!=Thread.currentThread() && enabled && Pcla.colorsReady() && ClientDisplaySettings.blockAllowed(received,state) ? received : null;
            emission=snapshot==null ? 0 : Math.max(0,Math.min(15,state.getLightValue(access,pos)));
            smooth=BlockTint.smooth;
            skyTint=SkyTint.enabled;
            gpu=RgbGpuLightmap.encodeMesh();
            fullCube=state.isFullCube();
        }
    }
    public static void begin(IBlockAccess access,IBlockState state,BlockPos pos) { CONTEXT.get().push(new Context(access,state,pos)); }
    public static Context colorContext(IBlockAccess access,IBlockState state,BlockPos pos) { return new Context(access,state,pos); }
    public static void end() { var stack=CONTEXT.get(); if(!stack.isEmpty()) stack.pop(); }
    public static int scopeDepth() { return CONTEXT.get().size(); }
    public static void quad(BakedQuad quad) {
        var context=CONTEXT.get().peek();
        if(context!=null) { context.face=quad.getFace(); context.quad=quad; }
    }
    public static void apply(BufferBuilder buffer) {
        var context=CONTEXT.get().peek();
        if(buffer.getVertexCount()<4) return;
        var format=buffer.getVertexFormat(); if(!format.hasColor() || !prepare(context)) return;
        int stride=format.getIntegerSize()*4, color=format.getColorOffset();
        var bytes=buffer.getByteBuffer();
        if(context.gpu) {
            if(context.lightCoordinates==null || !format.hasUvOffset(1))return;
            int offset=format.getUvOffsetById(1);
            for(int vertex=0;vertex<4;vertex++) {
                int target=(buffer.getVertexCount()-4+vertex)*stride+offset,packed=context.lightCoordinates[vertex];
                bytes.putShort(target,(short)packed);bytes.putShort(target+2,(short)(packed>>>16));
            }
            return;
        }
        for(int vertex=0;vertex<4;vertex++) DisplayTint.multiply(bytes,
                (buffer.getVertexCount()-4+vertex)*stride+color,context.multipliers[vertex*3],context.multipliers[vertex*3+1],context.multipliers[vertex*3+2]);
    }
    /** Source-vertex ABGR colors, before any renderer-specific vertex reordering. */
    public static void applyColors(Context context,BakedQuad quad,int[] colors) {
        if(colors.length!=4) throw new IllegalArgumentException("Expected four vertex colors");
        context.face=quad.getFace(); context.quad=quad;
        if(!prepare(context)) return;
        if(context.gpu){celeritasQuads.increment();return;}
        for(int vertex=0;vertex<4;vertex++) colors[vertex]=DisplayTint.multiplyAbgr(colors[vertex],
                context.multipliers[vertex*3],context.multipliers[vertex*3+1],context.multipliers[vertex*3+2]);
        celeritasQuads.increment();
    }
    private static boolean prepare(Context context) {
        if(context!=null)context.coordinatesReady=false;
        if(context==null || context.snapshot==null || context.face==null) return false;
        if(context.gpu)return prepareLight(context);
        int geometry=normalOffset(context.quad,context.face);
        int layer=QuadLightGeometry.sampleLayer(geometry,context.fullCube);
        var pos=layer==0 ? context.pos : context.pos.offset(context.face);
        int rgb=context.snapshot.sample(pos.getX(),pos.getY(),pos.getZ());
        int skyRgb=context.skyTint ? context.snapshot.sampleSky(pos.getX(),pos.getY(),pos.getZ()) : -1;
        if(rgb<0 && skyRgb<0) return false;
        if(context.multipliers==null) context.multipliers=new float[12];
        if(context.smooth && geometry>=0) {
            if(context.corners==null) {
                context.corners=new float[12][]; context.samples=new int[9]; context.skySamples=new int[9]; context.samplePos=new BlockPos.MutableBlockPos();
            }
            int cacheIndex=context.face.getIndex()*2+layer;
            var corners=context.corners[cacheIndex];
            if(corners==null) {
                for(int v=-1;v<=1;v++) for(int u=-1;u<=1;u++) {
                    int index=u+1+3*(v+1); context.samples[index]=sample(context,u,v,layer,index);
                }
                corners=new float[12]; RgbFaceTint.corners(context.samples,context.skySamples,context.daylight,corners,context.lightmap);
                context.corners[cacheIndex]=corners;
            }
            int[] vertices=context.quad.getVertexData(); int sourceStride=context.quad.getFormat().getIntegerSize();
            boolean changed=false;
            for(int vertex=0;vertex<4;vertex++) {
                float x=Float.intBitsToFloat(vertices[vertex*sourceStride]);
                float y=Float.intBitsToFloat(vertices[vertex*sourceStride+1]);
                float z=Float.intBitsToFloat(vertices[vertex*sourceStride+2]);
                float u=context.face.getAxis()==EnumFacing.Axis.X ? z : x;
                float v=context.face.getAxis()==EnumFacing.Axis.Y ? z : y;
                float vr=RgbFaceTint.interpolate(corners,0,u,v), vg=RgbFaceTint.interpolate(corners,1,u,v), vb=RgbFaceTint.interpolate(corners,2,u,v);
                context.multipliers[vertex*3]=vr; context.multipliers[vertex*3+1]=vg; context.multipliers[vertex*3+2]=vb;
                changed |= vr!=1 || vg!=1 || vb!=1;
            }
            smoothedQuads.increment(); if(layer==0) insetQuads.increment(); if(changed) tintedQuads.increment();
            return true;
        }
        int packed=context.access.getCombinedLight(pos,0), block=packed>>4&15, sky=packed>>20&15;
        if(context.emission>0) {
            int own=context.snapshot.sample(context.pos.getX(),context.pos.getY(),context.pos.getZ());
            if(rgb>=0 && own>=0) rgb=com.sumirelabs.pcla.light.PackedRgb.union(rgb,own);
            block=Math.max(block,context.emission);
        }
        float r=DisplayTint.channel(rgb,skyRgb,8,block,sky,context.daylight,context.lightmap);
        float g=DisplayTint.channel(rgb,skyRgb,4,block,sky,context.daylight,context.lightmap);
        float b=DisplayTint.channel(rgb,skyRgb,0,block,sky,context.daylight,context.lightmap);
        if(r==1 && g==1 && b==1) return false;
        for(int vertex=0;vertex<4;vertex++) {
            context.multipliers[vertex*3]=r; context.multipliers[vertex*3+1]=g; context.multipliers[vertex*3+2]=b;
        }
        tintedQuads.increment();
        return true;
    }
    public static int coordinate(Context context,int vertex,int fallback) {
        return context!=null && context.gpu && context.coordinatesReady?context.lightCoordinates[vertex]:fallback;
    }
    private static boolean prepareLight(Context context) {
        int geometry=normalOffset(context.quad,context.face),layer=QuadLightGeometry.sampleLayer(geometry,context.fullCube);
        var pos=layer==0?context.pos:context.pos.offset(context.face);
        int rgb=context.snapshot.sample(pos.getX(),pos.getY(),pos.getZ());
        int sky=context.skyTint?context.snapshot.sampleSky(pos.getX(),pos.getY(),pos.getZ()):-1;
        if(rgb<0 && sky<0){context.lightCoordinates=null;return false;}
        if(context.lightCoordinates==null)context.lightCoordinates=new int[4];
        if(context.smooth && geometry>=0) {
            if(context.lightCorners==null) {
                context.lightCorners=new float[12][];context.skyCorners=new float[12][];
                context.samples=new int[9];context.skySamples=new int[9];context.samplePos=new BlockPos.MutableBlockPos();
            }
            int cache=context.face.getIndex()*2+layer;
            if(context.lightCorners[cache]==null) {
                for(int v=-1;v<=1;v++)for(int u=-1;u<=1;u++) {
                    int index=u+1+3*(v+1);context.samples[index]=sample(context,u,v,layer,index);
                }
                context.lightCorners[cache]=new float[12];context.skyCorners[cache]=new float[12];
                RgbFaceTint.lightCorners(context.samples,context.skySamples,context.lightCorners[cache],context.skyCorners[cache]);
            }
            var vertices=context.quad.getVertexData();int stride=context.quad.getFormat().getIntegerSize();
            for(int vertex=0;vertex<4;vertex++) {
                float x=Float.intBitsToFloat(vertices[vertex*stride]),y=Float.intBitsToFloat(vertices[vertex*stride+1]),z=Float.intBitsToFloat(vertices[vertex*stride+2]);
                float u=context.face.getAxis()==EnumFacing.Axis.X?z:x,v=context.face.getAxis()==EnumFacing.Axis.Y?z:y;
                var b=context.lightCorners[cache];var s=context.skyCorners[cache];
                context.lightCoordinates[vertex]=RgbLightCoordinates.lane(RgbFaceTint.interpolate(b,0,u,v),RgbFaceTint.interpolate(b,1,u,v),RgbFaceTint.interpolate(b,2,u,v))
                    | RgbLightCoordinates.lane(RgbFaceTint.interpolate(s,0,u,v),RgbFaceTint.interpolate(s,1,u,v),RgbFaceTint.interpolate(s,2,u,v))<<16;
            }
        } else {
            if(context.emission>0 && rgb>=0) {
                int own=context.snapshot.sample(context.pos.getX(),context.pos.getY(),context.pos.getZ());
                if(own>=0)rgb=com.sumirelabs.pcla.light.PackedRgb.union(rgb,own);
            }
            java.util.Arrays.fill(context.lightCoordinates,RgbLightCoordinates.withFallback(rgb,sky,context.access.getCombinedLight(pos,context.emission)));
        }
        // Forge/Celeritas merge light UVs stored by the baked model into their calculated
        // lighting. Replacing the result must retain that minimum (e.g. glowing faces).
        var format=context.quad.getFormat();
        if(format.hasUvOffset(1)) {
            int offset=format.getUvOffsetById(1);
            for(var element:format.getElements())if(element.getUsage()==VertexFormatElement.EnumUsage.UV && element.getIndex()==1
                    && element.getType()==VertexFormatElement.EnumType.SHORT && element.getElementCount()==2 && (offset&3)==0) {
                int stride=format.getIntegerSize();var data=context.quad.getVertexData();
                for(int vertex=0;vertex<4;vertex++)context.lightCoordinates[vertex]=RgbLightCoordinates.withBakedFloor(
                    context.lightCoordinates[vertex],data[vertex*stride+(offset>>2)]);
                break;
            }
        }
        context.coordinatesReady=true;gpuQuads.increment();return true;
    }
    private static int sample(Context context,int u,int v,int layer,int sampleIndex) {
        int dx=0,dy=0,dz=0;
        switch(context.face.getAxis()) {
            case X -> { dz=u; dy=v; }
            case Y -> { dx=u; dz=v; }
            case Z -> { dx=u; dy=v; }
        }
        var pos=context.samplePos.setPos(context.pos.getX()+context.face.getXOffset()*layer+dx,
                context.pos.getY()+context.face.getYOffset()*layer+dy,context.pos.getZ()+context.face.getZOffset()*layer+dz);
        int rgb=context.snapshot.sample(pos.getX(),pos.getY(),pos.getZ());
        int skyRgb=context.skyTint ? context.snapshot.sampleSky(pos.getX(),pos.getY(),pos.getZ()) : -1;
        context.skySamples[sampleIndex]=skyRgb;
        if(rgb<0 && skyRgb<0) return -1;
        int packed=context.access.getCombinedLight(pos,0), block=packed>>4&15;
        if(context.emission>0) {
            int own=context.snapshot.sample(context.pos.getX(),context.pos.getY(),context.pos.getZ());
            if(rgb>=0 && own>=0) rgb=com.sumirelabs.pcla.light.PackedRgb.union(rgb,own);
            block=Math.max(block,context.emission);
        }
        var state=context.access.getBlockState(pos);
        int info=LightInfo.resolveContextual(LightInfo.of(state),state,context.access,pos,pos.getX(),pos.getY(),pos.getZ());
        int face=PULSAR_FACE[QuadLightGeometry.occludingFace(context.face.getIndex(),layer)];
        boolean occluded=state.isOpaqueCube() || LightInfo.absorption(info,state,face)>=15;
        return RgbFaceTint.pack(rgb<0 ? block*0x111 : rgb,block,packed>>20&15,occluded);
    }
    static int normalOffset(BakedQuad quad,EnumFacing face) {
        if(quad==null) return -1;
        var format=quad.getFormat();
        if(format.getElement(0).getUsage()!=VertexFormatElement.EnumUsage.POSITION || format.getElement(0).getType()!=VertexFormatElement.EnumType.FLOAT) return -1;
        int stride=format.getIntegerSize(); int[] data=quad.getVertexData();
        int normal=switch(face.getAxis()) { case X -> 0; case Y -> 1; case Z -> 2; };
        return QuadLightGeometry.normalOffset(data,stride,normal,face.getAxisDirection()==EnumFacing.AxisDirection.POSITIVE);
    }
}
