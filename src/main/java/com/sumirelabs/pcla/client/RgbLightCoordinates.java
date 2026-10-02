package com.sumirelabs.pcla.client;

/** Six half-level channels in the existing two SHORT lightmap coordinates. */
public final class RgbLightCoordinates {
    public static final int MARKER=0x8000;
    public static int lane(float red,float green,float blue) {
        return MARKER | quantize(red)<<10 | quantize(green)<<5 | quantize(blue);
    }
    private static int quantize(float value) {
        if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite RGB light");
        return Math.round(Math.max(0,Math.min(15,value))*2);
    }
    public static int lane(int rgb) {
        com.sumirelabs.pcla.light.PackedRgb.validate(rgb);
        return lane(rgb>>8,rgb>>4&15,rgb&15);
    }
    public static int pair(int block,int sky){return lane(block) | lane(sky)<<16;}
    public static boolean encoded(int packed){return (packed&0x80008000)==0x80008000;}
    public static float component(int lane,int component) {
        if(component<0 || component>2)throw new IllegalArgumentException("Invalid RGB component");
        return (lane>>(10-component*5)&31)*0.5f;
    }
    public static int withFallback(int block,int sky,int scalar) {
        int br=block<0?(scalar>>>4&15)*0x111:block;
        int sr=sky<0?(scalar>>>20&15)*0x111:sky;
        return pair(br,sr);
    }
    /** Keep model-provided emissive UVs. World scalar light is deliberately not a floor. */
    public static int withBakedFloor(int coordinates,int baked) {
        if(!encoded(coordinates) || baked==0)return coordinates;
        return floorLane(coordinates,(baked&0xffff)/16.0f)
            | floorLane(coordinates>>>16,(baked>>>16)/16.0f)<<16;
    }
    private static int floorLane(int coordinates,float floor) {
        return lane(Math.max(component(coordinates,0),floor),Math.max(component(coordinates,1),floor),Math.max(component(coordinates,2),floor));
    }
    private RgbLightCoordinates(){}
}
