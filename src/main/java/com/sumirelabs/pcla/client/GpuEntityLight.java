package com.sumirelabs.pcla.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.item.EntityPainting;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

/** A render-local RGB light coordinate; nested renders restore both native coordinates and GPU state. */
public final class GpuEntityLight {
    record VertexLight(int packed,float scalarBlock) {}
    private static final ThreadLocal<java.util.ArrayDeque<VertexLight>> VERTEX_LIGHT=ThreadLocal.withInitial(java.util.ArrayDeque::new);
    static VertexLight current(){return VERTEX_LIGHT.get().peek();}
    public static Scope begin(Entity entity,boolean outlines) {
        var root=ClientProxy.STORE.snapshot();
        if(!RgbGpuLightmap.ready() || !EntityTint.enabled || outlines || entity.isBurning() || root==null
                || entity.world!=Minecraft.getMinecraft().world)return new Scope(null);
        var key=EntityList.getKey(entity);
        String type=key==null?entity instanceof EntityPlayer?"minecraft:player":null:key.toString();
        if(!ClientDisplaySettings.entityAllowed(root,type))return new Scope(null);
        var pos=new BlockPos(entity.posX,entity.posY+entity.getEyeHeight(),entity.posZ);
        int scalar=entity.world.getCombinedLight(pos,0);
        if(!(entity instanceof EntityPainting) && (entity.getBrightnessForRender()>>4&15)>(scalar>>4&15)+1)return new Scope(null);
        Integer packed=sample(pos,scalar);
        return new Scope(packed,scalar&65535);
    }
    public static Scope neutral(){return new Scope(RgbGpuLightmap.ready()?0x00f000f0:null);}
    static Integer sample(BlockPos pos,int scalar) {
        var root=ClientProxy.STORE.snapshot();if(root==null)return null;
        int block=root.sample(pos.getX(),pos.getY(),pos.getZ()),sky=SkyTint.enabled?root.sampleSky(pos.getX(),pos.getY(),pos.getZ()):-1;
        return block<0 && sky<0?null:RgbLightCoordinates.withFallback(block,sky,scalar);
    }
    public static void painting(EntityPainting painting,float x,float y) {
        if(!RgbGpuLightmap.active() || !EntityTint.enabled || EntityTint.state().owner()!=painting
            || !ClientDisplaySettings.entityAllowed(ClientProxy.STORE.snapshot(),"minecraft:painting"))return;
        var pos=paintingPosition(painting,x,y);
        Integer packed=sample(pos,painting.world.getCombinedLight(pos,0));if(packed!=null)set(packed);
    }
    static BlockPos paintingPosition(EntityPainting painting,float x,float y) {
        double worldX=painting.posX,worldZ=painting.posZ;
        switch(painting.facingDirection) {
            case NORTH -> worldX+=x/16;
            case SOUTH -> worldX-=x/16;
            case WEST -> worldZ-=x/16;
            case EAST -> worldZ+=x/16;
            default -> { }
        }
        return new BlockPos(worldX,painting.posY+y/16,worldZ);
    }
    public static Scope tile(net.minecraft.tileentity.TileEntity tile) {
        if(!RgbGpuLightmap.ready() || !BlockTint.enabled || tile.getWorld()==null)return new Scope(null);
        var pos=tile.getPos();var world=tile.getWorld();
        if(!ClientDisplaySettings.blockAllowed(ClientProxy.STORE.snapshot(),world.getBlockState(pos)))return new Scope(null);
        int scalar=world.getCombinedLight(pos,0);return new Scope(sample(pos,scalar),scalar&65535);
    }
    private static void set(int packed){OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,(short)packed,(short)(packed>>>16));}
    public static final class Scope implements AutoCloseable {
        private final float x,y;private final RgbGpuLightmap.Scope program;private boolean closed;
        private Scope(Integer packed){this(packed,0);}
        private Scope(Integer packed,float scalarBlock) {
            x=OpenGlHelper.lastBrightnessX;y=OpenGlHelper.lastBrightnessY;
            program=packed==null?null:RgbGpuLightmap.entity();if(program!=null && program.bound())set(packed);
            VERTEX_LIGHT.get().push(new VertexLight(program!=null && program.bound()?packed:0,scalarBlock));
            RgbGpuLightmap.entityState();
        }
        public void close() {
            if(closed)return;closed=true;
            VERTEX_LIGHT.get().pop();
            RgbGpuLightmap.entityState();
            if(program!=null){OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,x,y);program.close();}
        }
    }
    private GpuEntityLight(){}
}
