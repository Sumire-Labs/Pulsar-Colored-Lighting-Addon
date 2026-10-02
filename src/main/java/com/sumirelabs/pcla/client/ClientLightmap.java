package com.sumirelabs.pcla.client;

import net.minecraft.client.Minecraft;

/** Copies finished native lightmap colors; render workers never touch mutable renderer arrays. */
public final class ClientLightmap {
    private static final LightmapRefreshGate gate=new LightmapRefreshGate();
    private static volatile DisplayLightmap current;
    private static long tick;
    public static long publications;
    public static DisplayLightmap snapshot(){return current;}
    public static LightmapRefreshGate.Key key(){return gate.key();}
    public static void tick(){tick++;}
    public static void clear(){current=null;gate.clear();publications=0;RgbGpuLightmap.clear();}
    public static void capture(int[] colors,float nightVision) {
        var mc=Minecraft.getMinecraft();if(mc.world==null || mc.player==null)return;
        float gamma=mc.gameSettings.gammaSetting;
        if(!Float.isFinite(gamma) || !Float.isFinite(nightVision))return;
        var key=new LightmapRefreshGate.Key(Math.round(Math.max(0,Math.min(1,mc.world.getSunBrightness(1)))*16),
                Float.floatToIntBits(gamma),Math.round(Math.max(0,Math.min(1,nightVision))*16),mc.world.provider.getDimension());
        if(!gate.accept(key,tick))return;
        current=new DisplayLightmap(colors);publications++;
        if(!RgbGpuLightmap.ready())ClientProxy.invalidate(ClientProxy.STORE.snapshot());
    }
    private ClientLightmap(){}
}
