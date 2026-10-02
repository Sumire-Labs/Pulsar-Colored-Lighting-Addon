package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.PackedRgb;
import com.sumirelabs.pcla.light.engine.*;
import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import java.util.*;

/** Sky storage over the block pilot's stable geometry, with separately captured upper columns. */
public final class CapturedSkyRegion implements RgbSkyAccess {
    private final CapturedRgbRegion input;
    private final int minimumY,maximumY,sizeX,sizeY,sizeZ,boundaryLayer;
    private final short[] lights;
    private final RgbLightVolume output;
    private final SkyColumnCache columns;
    private long columnBlockReads;
    public CapturedSkyRegion(CapturedRgbRegion input,int minimumY) { this(input,minimumY,Integer.MAX_VALUE); }
    public CapturedSkyRegion(CapturedRgbRegion input,int minimumY,int maximumY) {
        if(maximumY<minimumY) throw new IllegalArgumentException("Invalid world height");
        this.input=Objects.requireNonNull(input); this.minimumY=minimumY; this.maximumY=maximumY;
        var b=input.bounds; sizeX=b.inputX(); sizeY=b.inputY(); sizeZ=b.inputZ();
        boundaryLayer=(int)(Math.min((long)b.inputTopY(),(long)maximumY+1)-(b.y()-CapturedRgbRegion.MARGIN));
        if(boundaryLayer<0 || boundaryLayer>=sizeY) throw new IllegalArgumentException("Sky source lies outside input bounds");
        lights=new short[b.cells()]; output=new RgbLightVolume(b.sizeX(),b.sizeY(),b.sizeZ());
        columns=new SkyColumnCache(b.inputColumns());
    }
    public SkyColumnCache.Capture refreshColumns(SkyColumnCache.Reader reader,BitSet dirty,boolean full) { return columns.refresh(reader,dirty,full); }
    public SkyColumnCache.Capture refreshColumns(World world,WorldHeightContext height,Map<Long,Chunk> chunks,BitSet dirty,boolean full) {
        var reader=new WorldColumnReader(world,height); var value=new CapturedRgbRegion.Input(); var bounds=input.bounds;
        return columns.refresh((column,out)-> {
            reader.x=bounds.x()-CapturedRgbRegion.MARGIN+column%sizeX; reader.z=bounds.z()-CapturedRgbRegion.MARGIN+column/sizeX;
            reader.chunk=chunks.get(RegionPublication.key(reader.x>>4,reader.z>>4));
            if(reader.chunk==null) throw new IllegalStateException("Missing sky column chunk");
            columnBlockReads+=SkyColumnScanner.scan(height.getMaxBlockY(),bounds.y()-CapturedRgbRegion.MARGIN+boundaryLayer,world.provider.hasSkyLight()?0xfff:0,reader,value,out);
        },dirty,full);
    }
    private static final class WorldColumnReader implements SkyColumnScanner.Reader {
        final World world; final WorldHeightContext height; final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        Chunk chunk; int x,z;
        WorldColumnReader(World world,WorldHeightContext height) { this.world=world; this.height=height; }
        public void read(int y,CapturedRgbRegion.Input value) { MinecraftRgbInputs.read(world,height,chunk,pos,x,y,z,value); }
        public int emptyBottom(int y) {
            // Stored fluid can exist independently of the host block storage array.
            if(com.sumirelabs.pulsar.compat.FluidLightBridge.LOADED)return y;
            int index=height.getStorageIndex(y>>4); var storage=chunk.getBlockStorageArray();
            return index<0 || index>=storage.length || storage[index]==null ? y&~15 : y;
        }
    }
    public long columnBlockReads() { return columnBlockReads; }
    public int[] changedRoots(int[] opacityChanges,int[] changedColumns) {
        int[] roots=Arrays.copyOf(opacityChanges,opacityChanges.length+changedColumns.length);
        int base=boundaryLayer*sizeX*sizeZ;
        for(int i=0;i<changedColumns.length;i++) roots[opacityChanges.length+i]=base+changedColumns[i];
        return roots;
    }
    public int cellCount() { return lights.length; }
    public int neighbor(int cell,int direction) { return input.neighbor(cell,direction); }
    public int boundaryLight(int cell) { return cell/(sizeX*sizeZ)==boundaryLayer ? columns.mask(cell%(sizeX*sizeZ)) : 0; }
    public int absorption(int cell,int direction) {
        int target=neighbor(cell,direction);
        if(target<0 || worldY(cell)<minimumY || worldY(target)<minimumY) return 0xfff;
        if((long)worldY(cell)>(long)maximumY+1 || (long)worldY(target)>(long)maximumY+1) return 0xfff;
        // The virtual sky source is outside the block world; it has no configurable air face of its own.
        if((long)worldY(cell)==(long)maximumY+1 && direction==3) return input.incomingAbsorption(target,2);
        return input.absorption(cell,direction);
    }
    private int worldY(int cell) { return input.bounds.y()-CapturedRgbRegion.MARGIN+cell/(sizeX*sizeZ); }
    public int light(int cell) { return lights[cell]; }
    public void light(int cell,int rgb) {
        PackedRgb.validate(rgb); if(lights[cell]==rgb) return; lights[cell]=(short)rgb;
        int x=cell%sizeX-CapturedRgbRegion.MARGIN,z=cell/sizeX%sizeZ-CapturedRgbRegion.MARGIN,y=cell/(sizeX*sizeZ)-CapturedRgbRegion.MARGIN;
        var bounds=input.bounds;
        if(x>=0 && x<bounds.sizeX() && y>=0 && y<bounds.sizeY() && z>=0 && z<bounds.sizeZ())
            output.set(x+bounds.sizeX()*(z+bounds.sizeZ()*y),rgb);
    }
    public RgbLightVolume.Snapshot interior() { return output.publish(); }
}
