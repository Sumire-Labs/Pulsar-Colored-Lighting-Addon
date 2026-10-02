package com.sumirelabs.pcla.client;

import com.sumirelabs.pcla.Pcla;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.*;
import net.minecraft.entity.item.EntityPainting;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import java.util.concurrent.atomic.LongAdder;

/** Scoped fixed-function model tint pilot. Does not replace custom shaders or entity vertex-color pipelines. */
public final class EntityTint {
    public static final boolean INSTALLED=com.sumirelabs.pcla.config.RuntimeOptions.entityTint();
    public static volatile boolean enabled=INSTALLED;
    public static final LongAdder tintedRenders=new LongAdder(),paintingTiles=new LongAdder(),excludedRenders=new LongAdder();
    private static final ThreadLocal<TintScopeState> STATE=ThreadLocal.withInitial(TintScopeState::new);
    public static TintScopeState state() { return STATE.get(); }
    public static void reapply() { state().reapply(GlStateManager::color); }
    public static TintScopeState.Scope begin(Entity entity,boolean forceNeutral) {
        var tint=TintScopeState.Tint.WHITE;
        if(!forceNeutral && eligible(entity)) tint=sample(entity,new BlockPos(entity.posX,entity.posY+entity.getEyeHeight(),entity.posZ));
        else excludedRenders.increment();
        if(!tint.equals(TintScopeState.Tint.WHITE)) tintedRenders.increment();
        // A neutral outline pass must also suppress painting's later per-tile override.
        return state().push(forceNeutral ? null : entity,tint,GlStateManager::color);
    }
    public static TintScopeState.Scope neutral() { return state().push(null,TintScopeState.Tint.WHITE,GlStateManager::color); }
    private static boolean eligible(Entity entity) {
        if(!enabled || !Pcla.colorsReady() || entity.world!=Minecraft.getMinecraft().world || entity.isBurning()) return false;
        var key=EntityList.getKey(entity);
        return ClientDisplaySettings.entityAllowed(ClientProxy.STORE.snapshot(),key==null ? entity instanceof EntityPlayer ? "minecraft:player" : null : key.toString());
    }
    private static TintScopeState.Tint sample(Entity entity,BlockPos pos) {
        if(RgbGpuLightmap.ready())return TintScopeState.Tint.WHITE;
        var snapshot=ClientProxy.STORE.snapshot();
        if(snapshot==null || snapshot.dimension()!=entity.dimension || !entity.world.isBlockLoaded(pos)) return TintScopeState.Tint.WHITE;
        int rgb=snapshot.sample(pos.getX(),pos.getY(),pos.getZ());
        int skyRgb=SkyTint.enabled ? snapshot.sampleSky(pos.getX(),pos.getY(),pos.getZ()) : -1;
        if(rgb<0 && skyRgb<0) return TintScopeState.Tint.WHITE;
        int packed=entity.world.getCombinedLight(pos,0), block=packed>>4&15, sky=packed>>20&15;
        // Preserve intrinsic fullbright renderers, rather than dimming them to environmental light.
        if(!(entity instanceof EntityPainting) && (entity.getBrightnessForRender()>>4&15)>block+1) return TintScopeState.Tint.WHITE;
        float day=Math.max(0,Math.min(1,entity.world.getSunBrightness(1)));
        var map=ClientLightmap.snapshot();
        return new TintScopeState.Tint(DisplayTint.channel(rgb,skyRgb,8,block,sky,day,map),DisplayTint.channel(rgb,skyRgb,4,block,sky,day,map),DisplayTint.channel(rgb,skyRgb,0,block,sky,day,map));
    }
    /** Painting draws each 16-pixel tile separately, with its own vanilla lightmap sample. */
    public static void paintingTile(EntityPainting painting,float localX,float localY) {
        if(state().owner()!=painting || !eligible(painting)) return;
        double x=painting.posX,z=painting.posZ;
        switch(painting.facingDirection) {
            case NORTH -> x+=localX/16;
            case SOUTH -> x-=localX/16;
            case WEST -> z-=localX/16;
            case EAST -> z+=localX/16;
            default -> { return; }
        }
        state().replaceTint(sample(painting,new BlockPos(x,painting.posY+localY/16,z)));
        paintingTiles.increment();
    }
}
