package com.sumirelabs.pcla.client;

import org.embeddedt.embeddium.impl.gl.attribute.*;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.*;
import java.util.Map;
import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/** Preserves Celeritas's uncompressed position/UV precision and all 32 lighting bits. */
public final class CeleritasWideRgbVertex implements ChunkVertexType {
    public static final CeleritasWideRgbVertex INSTANCE=new CeleritasWideRgbVertex();
    public static final int STRIDE=36;
    private static final java.lang.invoke.MethodHandle RDH=rdhField();
    private static java.lang.invoke.MethodHandle rdhField() {
        try{return java.lang.invoke.MethodHandles.publicLookup().findGetter(ChunkVertexEncoder.Vertex.class,"rdhFactor",int.class);}
        catch(NoSuchFieldException missing){return null;}
        catch(IllegalAccessException error){throw new IllegalStateException(error);}
    }
    private static int rdh(ChunkVertexEncoder.Vertex vertex) {
        if(RDH==null)return 0;
        try{return (int)RDH.invokeExact(vertex);}catch(Throwable error){throw new IllegalStateException(error);}
    }
    private static final GlVertexFormat FORMAT=GlVertexFormat.builder(STRIDE)
        .addElement("a_PosId",0,GlVertexAttributeFormat.FLOAT,3,false,false)
        .addElement("a_Color",12,GlVertexAttributeFormat.UNSIGNED_BYTE,4,true,false)
        .addElement("a_TexCoord",16,GlVertexAttributeFormat.FLOAT,2,false,false)
        .addElement("a_LightCoord",24,GlVertexAttributeFormat.UNSIGNED_SHORT,2,false,true)
        .addElement("pcla_MaterialDraw",28,GlVertexAttributeFormat.UNSIGNED_SHORT,2,false,true)
        .addElement("a_RdhFactor",32,GlVertexAttributeFormat.BYTE,4,true,false).build();
    public float getPositionScale(){return 1;}
    public float getPositionOffset(){return 0;}
    public float getTextureScale(){return 1;}
    public GlVertexFormat getVertexFormat(){return FORMAT;}
    public Map<String,String> getDefines(){var defines=ChunkVertexType.super.getDefines();defines.put("PCLA_WIDE_LIGHT_COORD","");return defines;}
    public ChunkVertexEncoder createEncoder() {
        return (address,material,vertex,section)->{
            LWJGL.memPutFloat(address,vertex.x);LWJGL.memPutFloat(address+4,vertex.y);LWJGL.memPutFloat(address+8,vertex.z);
            LWJGL.memPutInt(address+12,vertex.color);
            LWJGL.memPutFloat(address+16,Math.min(0.99999997F,vertex.u));LWJGL.memPutFloat(address+20,Math.min(0.99999997F,vertex.v));
            LWJGL.memPutInt(address+24,vertex.light);
            LWJGL.memPutShort(address+28,(short)(material.bits()&255));LWJGL.memPutShort(address+30,(short)(section&255));
            LWJGL.memPutInt(address+32,rdh(vertex));return address+STRIDE;
        };
    }
}
