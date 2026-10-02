package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.PackedRgb;
import java.util.*;

/** Worker-owned, world-aligned immutable optical pages. Never stores mutable light levels. */
public final class SharedOpticalInputs<C> {
    private static final long OPACITY_HIGH=(1L<<36)-1, CONTEXTUAL=1L<<36;
    private record Key(int x,int y,int z) {}
    private static final class Section<C> {
        final java.lang.ref.WeakReference<C> chunk;
        final Page[] pages;
        final int pageCount,uniformCount;
        Section(java.lang.ref.WeakReference<C> chunk,Page[] pages,int pageCount,int uniformCount) {
            this.chunk=chunk;this.pages=pages;
            this.pageCount=pageCount;this.uniformCount=uniformCount;
        }
    }
    private static final class Page {
        final long valid,low,high;
        final long[] lows,highs;
        Page(long valid,long low,long high,long[] lows,long[] highs) {
            this.valid=valid; this.low=low; this.high=high; this.lows=lows; this.highs=highs;
        }
        long low(int cell) { return lows==null ? low : lows[cell]; }
        long high(int cell) { return highs==null ? high : highs[cell]; }
    }
    public record Request<C>(CapturedRgbRegion.Bounds bounds,Map<Long,C> chunks,int[] cells,View previous,boolean fullRange) {
        public Request(CapturedRgbRegion.Bounds bounds,Map<Long,C> chunks,int[] cells) { this(bounds,chunks,cells,null); }
        public Request(CapturedRgbRegion.Bounds bounds,Map<Long,C> chunks,int[] cells,View previous) {this(bounds,chunks,cells,previous,false);}
        public Request { Objects.requireNonNull(bounds); chunks=Map.copyOf(chunks); cells=cells.clone();
            if(fullRange && cells.length!=0)throw new IllegalArgumentException("A full range cannot also specify sparse cells");
            if(previous!=null && !bounds.equals(previous.bounds)) throw new IllegalArgumentException("Wrong previous optical view"); }
        public static <C> Request<C> full(CapturedRgbRegion.Bounds bounds,Map<Long,C> chunks,View previous) {
            return new Request<>(bounds,chunks,new int[0],previous,true);
        }
        public int selectedCellCount(){return fullRange?bounds.cells():cells.length;}
        private int cellAt(int index){return fullRange?index:cells[index];}
        /** Compatibility accessor; the capture cursor consumes the range without materializing it. */
        @Override public int[] cells() { return fullRange?java.util.stream.IntStream.range(0,bounds.cells()).toArray():cells.clone(); }
    }
    @FunctionalInterface public interface Reader<C> {
        void read(C chunk,int x,int y,int z,CapturedRgbRegion.Input result);
    }
    @FunctionalInterface public interface ChangeConsumer {
        void changed(int cell,boolean lighting,boolean opacity,boolean contextual);
    }
    public record Stats(long worldReads,int sections,int pages,int uniformPages,long arrayPayloadBytes) {}
    private static final class Selection<C> {
        final C chunk;
        final long[] masks=new long[64];
        Selection(C chunk) { this.chunk=chunk; }
    }
    private final Map<Key,Section<C>> sections=new HashMap<>();
    private long worldReads;
    // Committed store only: immutable views and private staging pages are not owned here.
    private int pageCount,uniformCount;
    private long structureVersion,retainedStructureVersion=-1;
    private List<CapturedRgbRegion.Bounds> retainedBounds;
    private CaptureTask activeCapture;
    private List<CapturedRgbRegion.Bounds> deferredRetention;

    /** Complete all reads and view validation before changing the committed store. */
    public List<View> capture(List<Request<C>> requests,Reader<C> reader) {
        var task=beginCapture(requests,reader);
        while(!task.advance(4096)){}
        return task.result();
    }
    public CaptureTask beginCapture(List<Request<C>> requests,Reader<C> reader) {
        if(activeCapture!=null)throw new IllegalStateException("An optical capture is already active");
        var task=new CaptureTask(requests,reader);activeCapture=task;return task;
    }
    private void releaseCapture(CaptureTask task) {
        if(activeCapture!=task)throw new IllegalStateException("Wrong active optical capture");
        activeCapture=null;
        var retention=deferredRetention;deferredRetention=null;
        if(retention!=null)prune(retention);
    }
    private final class RequestCursor {
        final Request<C> request;final CapturedRgbRegion.Bounds bounds;
        final int sx,sy,sz,nx,nz;
        final Selection<C>[] lookup;int cellIndex;
        @SuppressWarnings("unchecked") RequestCursor(Request<C> request) {
            this.request=request;bounds=request.bounds;
            sx=(bounds.x()-15)>>4;sy=(bounds.y()-15)>>4;sz=(bounds.z()-15)>>4;
            nx=((bounds.x()+bounds.sizeX()+14)>>4)-sx+1;nz=((bounds.z()+bounds.sizeZ()+14)>>4)-sz+1;
            int ny=((bounds.y()+bounds.sizeY()+14)>>4)-sy+1;
            lookup=(Selection<C>[])new Selection<?>[nx*ny*nz];
        }
    }
    /** Single-worker session. Private staging pages do not escape before complete validation. */
    public final class CaptureTask {
        private enum Phase { SELECT, READ, VIEWS, DONE, CANCELLED }
        private Phase phase=Phase.SELECT;
        private List<Request<C>> requests;
        private Reader<C> reader;
        private final Map<Key,Selection<C>> selection=new LinkedHashMap<>();
        private final Map<Key,Section<C>> changed=new HashMap<>();
        private final List<View> views=new ArrayList<>();
        private List<View> result;
        private ViewBuilder viewBuilder;
        private long indexBuildCells,viewValidationSteps;
        private final CapturedRgbRegion.Input value=new CapturedRgbRegion.Input();
        private RequestCursor cursor;
        private int requestIndex,viewIndex;
        private Iterator<Map.Entry<Key,Selection<C>>> entries;
        private Key key;private Selection<C> chosen;private Page[] pages;
        private int pageIndex,sectionPages,sectionUniform;private boolean copied,pageOpen;
        private Page previous;
        private long mask,remaining,valid;
        private long[] lows,highs;
        private long selectedCells,readCells;
        private CaptureTask(List<Request<C>> requests,Reader<C> reader) {
            this.requests=List.copyOf(requests);this.reader=Objects.requireNonNull(reader);
        }
        public String stage() {return phase==Phase.VIEWS && viewBuilder!=null?(viewBuilder.indexReady?"VIEWS_VALIDATE":"VIEWS_INDEX"):phase.name();}
        public long indexBuildCells() {return indexBuildCells;}
        public long viewValidationSteps() {return viewValidationSteps;}
        public long selectedCells() {return selectedCells;}
        public long readCells() {return readCells;}
        public boolean complete() {return phase==Phase.DONE;}
        public List<View> result() {
            if(!complete())throw new IllegalStateException("Optical capture is not complete");
            return result;
        }
        /** One unit selects one cell, reads one cell, or validates one complete view. */
        public boolean advance(int maximumOperations) {
            if(maximumOperations<1)throw new IllegalArgumentException("Positive capture budget required");
            if(phase==Phase.CANCELLED)throw new IllegalStateException("Optical capture was cancelled");
            try {
                for(int operation=0;operation<maximumOperations;operation++) {
                    settle();
                    switch(phase) {
                        case SELECT -> selectOne();
                        case READ -> readOne();
                        case VIEWS -> {
                            if(viewBuilder==null)viewBuilder=new ViewBuilder(requests.get(viewIndex),changed,sections);
                            if(viewBuilder.indexReady)viewValidationSteps++;else indexBuildCells++;
                            viewBuilder.step();
                            if(viewBuilder.complete()){views.add(viewBuilder.result);viewBuilder=null;viewIndex++;}
                        }
                        case DONE -> {return true;}
                        default -> throw new IllegalStateException("Unexpected capture phase");
                    }
                }
                settle();return complete();
            } catch(RuntimeException|Error failure) {cancel();throw failure;}
        }
        public void cancel() {
            if(phase==Phase.DONE || phase==Phase.CANCELLED)return;
            phase=Phase.CANCELLED;clearStaging();releaseCapture(this);
        }
        private void settle() {
            if(phase==Phase.SELECT) {
                while(cursor==null || cursor.cellIndex==cursor.request.selectedCellCount()) {
                    if(requestIndex==requests.size()) {cursor=null;entries=selection.entrySet().iterator();phase=Phase.READ;break;}
                    cursor=new RequestCursor(requests.get(requestIndex++));
                }
            }
            if(phase==Phase.READ && remaining==0) {
                while(true) {
                    if(pageOpen) {finishPage();pageOpen=false;pageIndex++;}
                    if(chosen==null) {
                        if(!entries.hasNext()) {phase=Phase.VIEWS;break;}
                        var entry=entries.next();key=entry.getKey();chosen=entry.getValue();
                        var old=sections.get(key);copied=old==null || old.chunk.get()!=chosen.chunk;
                        sectionPages=copied?0:old.pageCount;sectionUniform=copied?0:old.uniformCount;
                        pages=copied?new Page[64]:old.pages;pageIndex=0;
                    }
                    while(pageIndex<64 && chosen.masks[pageIndex]==0)pageIndex++;
                    if(pageIndex==64) {
                        if(copied)changed.put(key,new Section<>(new java.lang.ref.WeakReference<>(chosen.chunk),pages,sectionPages,sectionUniform));
                        chosen=null;pages=null;continue;
                    }
                    previous=pages[pageIndex];valid=previous==null?0:previous.valid;
                    mask=chosen.masks[pageIndex];remaining=mask;lows=highs=null;pageOpen=true;break;
                }
            }
            if(phase==Phase.VIEWS && viewIndex==requests.size()) {
                // All readers and all view-coverage/identity checks have succeeded.
                var completed=List.copyOf(views);
                for(var entry:changed.entrySet()) {
                    var next=entry.getValue();var old=sections.put(entry.getKey(),next);
                    if(old==null)structureVersion++;
                    else {pageCount-=old.pageCount;uniformCount-=old.uniformCount;}
                    pageCount+=next.pageCount;uniformCount+=next.uniformCount;
                }
                result=completed;phase=Phase.DONE;
                clearStaging();releaseCapture(this);
            }
        }
        private void selectOne() {
            var b=cursor.bounds;int cell=cursor.request.cellAt(cursor.cellIndex++);
            if(cell<0 || cell>=b.cells())throw new IndexOutOfBoundsException("Invalid optical capture cell");
            int x=b.x()-15+cell%b.inputX(),z=b.z()-15+cell/b.inputX()%b.inputZ(),y=b.y()-15+cell/(b.inputX()*b.inputZ());
            int index=((x>>4)-cursor.sx)+cursor.nx*(((z>>4)-cursor.sz)+cursor.nz*((y>>4)-cursor.sy));
            var selected=cursor.lookup[index];
            if(selected==null) {
                C chunk=cursor.request.chunks.get(RegionPublication.key(x>>4,z>>4));
                if(chunk==null)throw new IllegalStateException("Missing optical dependency chunk");
                selected=selection.computeIfAbsent(new Key(x>>4,y>>4,z>>4),section->new Selection<>(chunk));
                if(selected.chunk!=chunk)throw new IllegalStateException("Chunk identity changed between optical requests");
                cursor.lookup[index]=selected;
            }
            int local=(x&15)|((z&15)<<4)|((y&15)<<8);
            selected.masks[local>>>6]|=1L<<(local&63);selectedCells++;
        }
        private void readOne() {
            int offset=Long.numberOfTrailingZeros(remaining),local=(pageIndex<<6)|offset;
            reader.read(chosen.chunk,(key.x<<4)+(local&15),(key.y<<4)+(local>>>8),(key.z<<4)+(local>>>4&15),value);
            worldReads++;readCells++;
            long low=packLow(value),high=packHigh(value);
            if(previous==null || (valid&(1L<<offset))==0 || previous.low(offset)!=low || previous.high(offset)!=high) {
                if(lows==null) {
                    lows=new long[64];highs=new long[64];
                    if(previous!=null)for(int i=0;i<64;i++){lows[i]=previous.low(i);highs[i]=previous.high(i);}
                }
                lows[offset]=low;highs[offset]=high;
            }
            remaining&=remaining-1;
        }
        private void finishPage() {
            if(lows==null)return;
            valid|=mask;int first=Long.numberOfTrailingZeros(valid);boolean uniform=true;
            for(long bits=valid;bits!=0;bits&=bits-1) {
                int i=Long.numberOfTrailingZeros(bits);
                if(lows[i]!=lows[first] || highs[i]!=highs[first]){uniform=false;break;}
            }
            if(!copied){pages=pages.clone();copied=true;}
            pages[pageIndex]=new Page(valid,lows[first],highs[first],uniform?null:lows,uniform?null:highs);
            if(previous==null)sectionPages++;
            sectionUniform+=(uniform?1:0)-(previous!=null && previous.lows==null?1:0);
        }
        private void clearStaging() {
            requests=List.of();reader=null;cursor=null;entries=null;key=null;chosen=null;pages=null;previous=null;lows=highs=null;viewBuilder=null;
            selection.clear();changed.clear();views.clear();
        }
    }
    /** Private construction cursor; no partly initialized index is placed in the shared cache. */
    private final class ViewBuilder {
        final Request<C> request;final CapturedRgbRegion.Bounds bounds;
        final Map<Key,Section<C>> changed,previous;
        final int minX,minY,minZ,nx,nz;
        final View.Shape shape;
        final Page[][] viewSections;
        int[] indices;int indexCursor,indexX=1,indexY=1,indexZ=1;
        boolean indexReady;
        int sectionIndex,rowY,rowZ,startZ,endY,endZ;
        long rowMask;
        Page[] currentPages,oldPages;
        View result;
        ViewBuilder(Request<C> request,Map<Key,Section<C>> changed,Map<Key,Section<C>> previous) {
            this.request=request;bounds=request.bounds;this.changed=changed;this.previous=previous;
            minX=(bounds.x()-15)>>4;minY=(bounds.y()-15)>>4;minZ=(bounds.z()-15)>>4;
            nx=((bounds.x()+bounds.sizeX()+14)>>4)-minX+1;nz=((bounds.z()+bounds.sizeZ()+14)>>4)-minZ+1;
            int ny=((bounds.y()+bounds.sizeY()+14)>>4)-minY+1;viewSections=new Page[nx*ny*nz][];
            shape=new View.Shape(bounds.inputX(),bounds.inputY(),bounds.inputZ());
            indices=View.INDICES.get(shape);
            if(indices!=null){indexReady=true;indexCursor=indices.length;}else indices=new int[bounds.cells()];
        }
        boolean complete(){return result!=null;}
        void step() {
            if(complete())return;
            if(!indexReady) {
                indices[indexCursor++]=((indexX>>4)+nx*((indexZ>>4)+nz*(indexY>>4)))<<12|(indexX&15)|(indexZ&15)<<4|(indexY&15)<<8;
                if(++indexX>shape.x()){indexX=1;if(++indexZ>shape.z()){indexZ=1;indexY++;}}
                if(indexCursor==indices.length) {
                    // Another world may have completed this shape while this builder yielded.
                    int[] canonical=View.INDICES.putIfAbsent(shape,indices);
                    if(canonical!=null)indices=canonical;
                    indexReady=true;
                }
                return;
            }
            if(currentPages==null) {
                if(sectionIndex==viewSections.length){finish();return;}
                var key=new Key(minX+sectionIndex%nx,minY+sectionIndex/(nx*nz),minZ+sectionIndex/nx%nz);
                var section=changed.getOrDefault(key,previous.get(key));
                if(section==null || section.chunk.get()!=request.chunks.get(RegionPublication.key(key.x,key.z)))
                    throw new IllegalStateException("Unavailable optical section");
                currentPages=section.pages;viewSections[sectionIndex]=currentPages;
                oldPages=request.previous==null?null:request.previous.sections[sectionIndex];
                if(oldPages==currentPages){currentPages=null;sectionIndex++;finishIfReady();return;}
                int x0=Math.max(key.x<<4,bounds.x()-15),x1=(int)Math.min((long)(key.x<<4)+16,(long)bounds.x()+bounds.sizeX()+15);
                rowMask=((1L<<(x1-x0))-1)<<(x0&15);
                rowY=Math.max(key.y<<4,bounds.y()-15);endY=(int)Math.min((long)(key.y<<4)+16,(long)bounds.y()+bounds.sizeY()+15);
                rowZ=startZ=Math.max(key.z<<4,bounds.z()-15);endZ=(int)Math.min((long)(key.z<<4)+16,(long)bounds.z()+bounds.sizeZ()+15);
                return;
            }
            int local=(rowZ&15)<<4|(rowY&15)<<8;var page=currentPages[local>>>6];
            if(oldPages==null || page==null || page!=oldPages[local>>>6]) {
                long mask=rowMask<<(local&63);
                if(page==null || (page.valid&mask)!=mask)throw new IllegalStateException("Uncaptured optical input");
            }
            if(++rowZ==endZ){rowZ=startZ;rowY++;}
            if(rowY==endY){currentPages=oldPages=null;sectionIndex++;finishIfReady();}
        }
        private void finishIfReady(){if(sectionIndex==viewSections.length)finish();}
        private void finish(){result=new View(bounds,indices,viewSections);}
    }

    private static long packLow(CapturedRgbRegion.Input value) {
        PackedRgb.validate(value.source);
        if((value.blockedFaces&~63)!=0) throw new IllegalArgumentException("Invalid optical face bits");
        long low=value.source|((long)value.blockedFaces<<12);
        for(int d=0;d<3;d++) { PackedRgb.validate(value.incoming[d]); low|=(long)value.incoming[d]<<(18+12*d); }
        return low;
    }
    private static long packHigh(CapturedRgbRegion.Input value) {
        long high=value.contextual?CONTEXTUAL:0;
        for(int d=3;d<6;d++) { PackedRgb.validate(value.incoming[d]); high|=(long)value.incoming[d]<<(12*(d-3)); }
        return high;
    }
    public void retain(Collection<CapturedRgbRegion.Bounds> bounds) {
        if(activeCapture!=null){deferredRetention=List.copyOf(bounds);return;}
        prune(bounds);
    }
    private void prune(Collection<CapturedRgbRegion.Bounds> bounds) {
        if(retainedStructureVersion==structureVersion && retainedBounds!=null && retainedBounds.equals(bounds))return;
        for(var iterator=sections.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next();var key=entry.getKey();boolean keep=false;
            for(var b:bounds)if(key.x>=(b.x()-15)>>4 && key.x<=(b.x()+b.sizeX()+14)>>4 && key.y>=(b.y()-15)>>4
                    && key.y<=(b.y()+b.sizeY()+14)>>4 && key.z>=(b.z()-15)>>4 && key.z<=(b.z()+b.sizeZ()+14)>>4) {
                keep=true;break;
            }
            if(!keep) {
                var section=entry.getValue();pageCount-=section.pageCount;uniformCount-=section.uniformCount;
                iterator.remove();structureVersion++;
            }
        }
        retainedBounds=List.copyOf(bounds);retainedStructureVersion=structureVersion;
    }
    public Stats stats() {
        return new Stats(worldReads,sections.size(),pageCount,uniformCount,1024L*(pageCount-uniformCount));
    }

    /** Immutable region-local section table. Chunk objects are not retained by a view. */
    public static final class View {
        private record Shape(int x,int y,int z) {}
        // Bounds are section-aligned, so all equal-sized views share this coordinate lookup.
        private static final Map<Shape,int[]> INDICES=new java.util.concurrent.ConcurrentHashMap<>();
        private final CapturedRgbRegion.Bounds bounds;
        private final int minX,minY,minZ,nx,nz;
        private final int[] indices;
        private final Page[][] sections;
        private View(CapturedRgbRegion.Bounds b,int[] indices,Page[][] sections) {
            bounds=b;minX=(b.x()-15)>>4;minY=(b.y()-15)>>4;minZ=(b.z()-15)>>4;
            nx=((b.x()+b.sizeX()+14)>>4)-minX+1;nz=((b.z()+b.sizeZ()+14)>>4)-minZ+1;
            this.indices=indices;this.sections=sections;
        }
        private long data(int cell,boolean high) {
            int index=indices[cell]; var page=sections[index>>>12][index>>>6&63];
            return high?page.high(index&63):page.low(index&63);
        }
        public int emission(int cell) { return (int)data(cell,false)&4095; }
        public int faces(int cell) { return (int)(data(cell,false)>>>12)&63; }
        public int incoming(int cell,int direction) {
            if(direction<0 || direction>=6) throw new IllegalArgumentException("Invalid optical direction");
            return (int)(data(cell,direction>=3)>>>(direction<3?18+direction*12:(direction-3)*12))&4095;
        }
        public CapturedRgbRegion.Bounds bounds() { return bounds; }
        /** Includes changes sampled by another overlapping region in this same batch. */
        public void differences(View old,ChangeConsumer consumer) {
            var task=beginDifferences(old,consumer);while(!task.advance(4096)){}
        }
        public DifferenceTask beginDifferences(View old,ChangeConsumer consumer) {return new DifferenceTask(old,consumer);}
        /** Immutable inputs; callback output is the caller's private staging responsibility. */
        public final class DifferenceTask {
            private View old;private ChangeConsumer consumer;
            private int section,pageIndex;
            private Page[] currentPages,previousPages;
            private Page next,before;
            private long remaining;
            private int sx,sy,sz;
            private boolean done,cancelled;
            private DifferenceTask(View old,ChangeConsumer consumer) {
                if(old!=null && !bounds.equals(old.bounds))throw new IllegalArgumentException("Different optical view bounds");
                this.old=old;this.consumer=Objects.requireNonNull(consumer);
            }
            public boolean advance(int maximumCells) {
                if(maximumCells<1)throw new IllegalArgumentException("Positive difference budget required");
                if(cancelled)throw new IllegalStateException("Difference scan cancelled");
                try {
                    for(int operation=0;operation<maximumCells;operation++) {
                        settle();if(done)return true;
                        int i=Long.numberOfTrailingZeros(remaining);remaining&=remaining-1;
                        long low=next.low(i),high=next.high(i),oldLow=before==null?0:before.low(i),oldHigh=before==null?0:before.high(i);
                        if(low==oldLow && high==oldHigh)continue;
                        int local=(pageIndex-1)*64+i;
                        int x=(sx<<4)+(local&15),y=(sy<<4)+(local>>>8),z=(sz<<4)+(local>>>4&15);
                        if(!bounds.containsInput(x,y,z))continue;
                        boolean opacity=(low&~4095L)!=(oldLow&~4095L) || (high&OPACITY_HIGH)!=(oldHigh&OPACITY_HIGH);
                        consumer.changed(bounds.inputCell(x,y,z),opacity || (low&4095)!=(oldLow&4095),opacity,(high&CONTEXTUAL)!=0);
                    }
                    settle();return done;
                } catch(RuntimeException|Error failure){cancel();throw failure;}
            }
            public void cancel() {if(done)return;cancelled=true;release();}
            private void settle() {
                while(!done && remaining==0) {
                    if(currentPages==null) {
                        while(section<sections.length && old!=null && sections[section]==old.sections[section])section++;
                        if(section==sections.length){done=true;release();return;}
                        currentPages=sections[section];previousPages=old==null?null:old.sections[section];
                        sx=minX+section%nx;sz=minZ+section/nx%nz;sy=minY+section/(nx*nz);pageIndex=0;
                    }
                    while(pageIndex<64) {
                        next=currentPages[pageIndex];before=previousPages==null?null:previousPages[pageIndex];pageIndex++;
                        if(next!=null && next!=before){remaining=next.valid;if(remaining!=0)return;}
                    }
                    currentPages=previousPages=null;section++;
                }
            }
            private void release(){old=null;consumer=null;currentPages=previousPages=null;next=before=null;}
        }
        public boolean contextual(int cell) { return (data(cell,true)&CONTEXTUAL)!=0; }
    }
}
