package com.sumirelabs.pcla.client;

/** Reference for the GPU lightmap: channel lookup, joint night vision, then native gamma. */
public final class RgbLightmapCurve {
    private final float[] raw;
    private final float gamma,night;
    public RgbLightmapCurve(float[] raw,float gamma,float night) {
        if(raw.length!=256*3 || !Float.isFinite(gamma) || !Float.isFinite(night))throw new IllegalArgumentException("Invalid lightmap");
        this.raw=raw.clone();for(int i=0;i<this.raw.length;i++) {
            if(!Float.isFinite(this.raw[i]))throw new IllegalArgumentException("Non-finite lightmap");
            this.raw[i]=clamp(this.raw[i]);
        }
        this.gamma=gamma;this.night=clamp(night);
    }
    public float[] raw(){return raw.clone();}
    public float gamma(){return gamma;}
    public float night(){return night;}
    public float[] sample(float[] block,float[] sky) {
        if(block.length!=3 || sky.length!=3)throw new IllegalArgumentException("Three light channels required");
        float[] result=new float[3];
        for(int c=0;c<3;c++)result[c]=lookup(block[c],sky[c],c);
        finish(result,gamma,night);return result;
    }
    public static void finish(float[] rgb,float gamma,float night) {
        float peak=Math.max(rgb[0],Math.max(rgb[1],rgb[2]));
        for(int c=0;c<3;c++) {
            float value=rgb[c];if(peak>0)value=value*(1-night)+value/peak*night;
            value=clamp(value);float inv=1-value;
            value=(value*(1-gamma)+(1-inv*inv*inv*inv)*gamma)*0.96f+0.03f;
            rgb[c]=clamp(value);
        }
    }
    private float lookup(float block,float sky,int component) {
        if(!Float.isFinite(block) || !Float.isFinite(sky))throw new IllegalArgumentException("Invalid light level");
        block=Math.max(0,Math.min(15,block));sky=Math.max(0,Math.min(15,sky));
        int x=(int)block,y=(int)sky,x1=Math.min(15,x+1),y1=Math.min(15,y+1);
        float u=block-x,v=sky-y;
        return (raw[(y*16+x)*3+component]*(1-u)+raw[(y*16+x1)*3+component]*u)*(1-v)
             +(raw[(y1*16+x)*3+component]*(1-u)+raw[(y1*16+x1)*3+component]*u)*v;
    }
    private static float clamp(float v){return Math.max(0,Math.min(1,v));}
}
