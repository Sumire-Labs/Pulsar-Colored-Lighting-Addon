package com.sumirelabs.pcla.client;

/** Client-thread publication policy; torch flicker alone never requests a mesh rebuild. */
public final class LightmapRefreshGate {
    public record Key(int daylightBucket,int gammaBits,int nightVisionBucket,int dimension) {}
    private Key published;
    private long lastTick;
    public boolean accept(Key key,long tick) {
        if(key.equals(published))return false;
        if(published!=null && tick-lastTick<10 && tick>=lastTick)return false;
        published=key;lastTick=tick;return true;
    }
    public Key key(){return published;}
    public void clear(){published=null;lastTick=0;}
}
