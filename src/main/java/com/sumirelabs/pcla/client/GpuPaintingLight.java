package com.sumirelabs.pcla.client;

import com.sumirelabs.pulsar.light.PaintingLightGrid;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.item.EntityPainting;

/** RGB counterpart of Pulsar's smooth painting tile grid; kept only for one render call. */
public final class GpuPaintingLight {
    private static final ThreadLocal<java.util.ArrayDeque<Scope>> SCOPES=ThreadLocal.withInitial(java.util.ArrayDeque::new);
    public static long writtenVertices;
    public static Scope begin(EntityPainting painting,int width,int height){return new Scope(painting,width,height);}
    public static int vertex(BufferBuilder buffer,int fallback) {
        var scope=SCOPES.get().peek();if(scope==null || scope.grid==null)return fallback;
        int offset=buffer.getVertexCount()*buffer.getVertexFormat().getSize();var bytes=buffer.getByteBuffer();
        float x=bytes.getFloat(offset),y=bytes.getFloat(offset+4);
        int r=scope.grid[0].getLight(x,y),g=scope.grid[1].getLight(x,y),b=scope.grid[2].getLight(x,y);
        writtenVertices++;
        return RgbLightCoordinates.lane((r&65535)/16.0f,(g&65535)/16.0f,(b&65535)/16.0f)
            |RgbLightCoordinates.lane((r>>>16)/16.0f,(g>>>16)/16.0f,(b>>>16)/16.0f)<<16;
    }
    public static final class Scope implements AutoCloseable {
        private PaintingLightGrid[] grid;
        private Scope(EntityPainting painting,int widthPixels,int heightPixels) {
            var root=ClientProxy.STORE.snapshot();
            if(RgbGpuLightmap.active() && EntityTint.enabled && EntityTint.state().owner()==painting
                    && ClientDisplaySettings.entityAllowed(root,"minecraft:painting")) {
                int width=widthPixels/16,height=heightPixels/16;
                if(width>0 && height>0) {
                    int[][] tiles=new int[3][width*height];boolean found=false;
                    for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                        var at=GpuEntityLight.paintingPosition(painting,-widthPixels*0.5f+(x+0.5f)*16,-heightPixels*0.5f+(y+0.5f)*16);
                        int scalar=painting.world.getCombinedLight(at,0);
                        int block=root.sample(at.getX(),at.getY(),at.getZ()),sky=SkyTint.enabled?root.sampleSky(at.getX(),at.getY(),at.getZ()):-1;
                        found|=block>=0 || sky>=0;
                        for(int c=0;c<3;c++) {
                            int shift=8-c*4,bl=block<0?scalar>>4&15:block>>shift&15,sk=sky<0?scalar>>20&15:sky>>shift&15;
                            tiles[c][x+y*width]=bl<<4|sk<<20;
                        }
                    }
                    if(found){grid=new PaintingLightGrid[3];for(int c=0;c<3;c++)grid[c]=PaintingLightGrid.fromTileLight(width,height,tiles[c]);}
                }
            }
            SCOPES.get().push(this);
        }
        public void close(){if(SCOPES.get().peek()!=this)throw new IllegalStateException("Painting render scope mismatch");SCOPES.get().pop();}
    }
    private GpuPaintingLight(){}
}
