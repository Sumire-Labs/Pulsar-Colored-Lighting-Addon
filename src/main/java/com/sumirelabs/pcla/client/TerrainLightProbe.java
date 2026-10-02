package com.sumirelabs.pcla.client;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.*;
import org.lwjgl.opengl.GL11;

/** Read-only real baked-model pipeline diagnostic. No geometry is uploaded or drawn. */
public final class TerrainLightProbe {
    public static JsonObject run() {
        var mc=Minecraft.getMinecraft();
        if(mc.world==null || mc.player==null)throw new IllegalStateException("A world is required");
        var hit=mc.objectMouseOver;
        var pos=hit!=null && hit.typeOfHit==RayTraceResult.Type.BLOCK?hit.getBlockPos():mc.player.getPosition().down();
        var report=new JsonObject();report.addProperty("position",pos.toString());
        report.addProperty("state",mc.world.getBlockState(pos).toString());
        report.addProperty("blockEnabled",BlockTint.enabled);report.addProperty("skyEnabled",SkyTint.enabled);
        report.addProperty("gpuReady",RgbGpuLightmap.ready());report.addProperty("smooth",BlockTint.smooth);
        var modelState=mc.world.getBlockState(pos).getActualState(mc.world,pos);
        var model=mc.getBlockRendererDispatcher().getModelForState(modelState);
        var extended=modelState.getBlock().getExtendedState(modelState,mc.world,pos);
        var modelQuads=new JsonArray();report.add("modelQuads",modelQuads);
        for(int f=-1;f<6;f++)for(var quad:model.getQuads(extended,f<0?null:EnumFacing.byIndex(f),MathHelper.getPositionRandom(pos))) {
            var item=new JsonObject();modelQuads.add(item);var face=quad.getFace();
            item.addProperty("face",String.valueOf(face));item.addProperty("cullFace",f);
            item.addProperty("fullCube",modelState.isFullCube());
            int geometry=face==null?-1:BlockTint.normalOffset(quad,face);
            item.addProperty("geometry",geometry);item.addProperty("sampleLayer",QuadLightGeometry.sampleLayer(geometry,modelState.isFullCube()));
            var positions=new JsonArray();item.add("localPositions",positions);
            var data=quad.getVertexData();int stride=quad.getFormat().getIntegerSize();
            for(int vertex=0;vertex<4;vertex++) {
                var xyz=new JsonArray();positions.add(xyz);
                for(int axis=0;axis<3;axis++)xyz.add(Float.intBitsToFloat(data[vertex*stride+axis]));
            }
        }
        var neighborhood=new JsonArray();report.add("neighborhood",neighborhood);
        var snapshot=ClientProxy.STORE.snapshot();
        for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++) {
            var at=pos.add(x,y,z);var cell=new JsonObject();neighborhood.add(cell);
            cell.addProperty("dx",x);cell.addProperty("dy",y);cell.addProperty("dz",z);
            cell.addProperty("state",mc.world.getBlockState(at).toString());
            int nativeLight=mc.world.getCombinedLight(at,0);
            cell.addProperty("nativeBlock",nativeLight>>>4&15);cell.addProperty("nativeSky",nativeLight>>>20&15);
            cell.addProperty("rgbBlock",snapshot==null?-1:snapshot.sample(at.getX(),at.getY(),at.getZ()));
            cell.addProperty("rgbSky",snapshot==null?-1:snapshot.sampleSky(at.getX(),at.getY(),at.getZ()));
        }
        try {
            BlockTint.nativeProbeThread=Thread.currentThread();
            report.add("nativeVertices",render(mc,pos));
        } finally {BlockTint.nativeProbeThread=null;}
        report.add("rgbVertices",render(mc,pos));
        report.addProperty("scopeDepth",BlockTint.scopeDepth());
        report.addProperty("note","Actual Forge/native baked-model output; not a Celeritas mesh or a GPU screenshot.");
        return report;
    }
    private static JsonArray render(Minecraft mc,BlockPos pos) {
        var buffer=new BufferBuilder(4096);buffer.begin(GL11.GL_QUADS,DefaultVertexFormats.BLOCK);
        mc.getBlockRendererDispatcher().renderBlock(mc.world.getBlockState(pos),pos,mc.world,buffer);
        buffer.finishDrawing();var bytes=buffer.getByteBuffer();var format=buffer.getVertexFormat();
        int stride=format.getSize(),uv=format.getUvOffsetById(1),color=format.getColorOffset();
        var vertices=new JsonArray();
        for(int v=0;v<buffer.getVertexCount();v++) {
            int at=v*stride;var vertex=new JsonObject();vertices.add(vertex);
            vertex.addProperty("vertex",v);vertex.addProperty("x",bytes.getFloat(at));vertex.addProperty("y",bytes.getFloat(at+4));vertex.addProperty("z",bytes.getFloat(at+8));
            var colors=new JsonArray();for(int c=0;c<4;c++)colors.add(bytes.get(at+color+c)&255);vertex.add("colorBytes",colors);
            int light=(bytes.getShort(at+uv)&0xffff) | (bytes.getShort(at+uv+2)&0xffff)<<16;
            vertex.addProperty("encoded",RgbLightCoordinates.encoded(light));
            if(RgbLightCoordinates.encoded(light)) {
                var block=new JsonArray();var sky=new JsonArray();
                for(int c=0;c<3;c++){block.add(RgbLightCoordinates.component(light,c));sky.add(RgbLightCoordinates.component(light>>>16,c));}
                vertex.add("block",block);vertex.add("sky",sky);
            } else {vertex.addProperty("block",(light&0xffff)/16.0f);vertex.addProperty("sky",(light>>>16)/16.0f);}
        }
        buffer.reset();return vertices;
    }
    private TerrainLightProbe(){}
}
