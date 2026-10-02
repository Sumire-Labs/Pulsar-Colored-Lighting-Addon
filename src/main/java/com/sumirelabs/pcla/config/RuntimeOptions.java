package com.sumirelabs.pcla.config;

/** Automatic lighting is available on installation; early startup overrides select diagnostic fallbacks. */
public final class RuntimeOptions {
    public static final boolean AUTOMATIC=Boolean.parseBoolean(System.getProperty("pcla.automaticWorldRgb","true"));
    public static boolean worldRgb(){return AUTOMATIC || Boolean.getBoolean("pcla.experimentalWorldRgb");}
    public static boolean skyRgb(){return AUTOMATIC || Boolean.getBoolean("pcla.experimentalSkyRgb");}
    public static boolean blockTint(){return AUTOMATIC || Boolean.getBoolean("pcla.experimentalBlockTint");}
    public static boolean entityTint(){return AUTOMATIC || Boolean.getBoolean("pcla.experimentalEntityTint");}
    public static boolean skyTint(){return AUTOMATIC || Boolean.getBoolean("pcla.experimentalSkyTint");}
    public static boolean rgbLightmap(){return rendererOverride(System.getProperty("pcla.rgbLightmap"),AUTOMATIC);}
    public static boolean celeritasTint(){return rendererOverride(System.getProperty("pcla.experimentalCeleritasTint"),AUTOMATIC);}
    static boolean rendererOverride(String explicit,boolean automatic){return explicit==null?automatic:Boolean.parseBoolean(explicit);}
    public static int automaticRadius() {
        int radius=Integer.parseInt(System.getProperty("pcla.automaticRadiusChunks","2"));
        if(radius<0 || radius>4)throw new IllegalArgumentException("Automatic RGB radius must be between 0 and 4 chunks");
        return radius;
    }
    public static int defaultWorkingOwners(){if(!AUTOMATIC)return 4;int width=2*automaticRadius()+1;return Math.min(64,width*width);}
    private RuntimeOptions() {}
}
