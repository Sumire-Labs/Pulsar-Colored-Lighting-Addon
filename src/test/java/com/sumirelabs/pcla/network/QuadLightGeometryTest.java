package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.client.QuadLightGeometry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuadLightGeometryTest {
    @Test void modelRotationRoundoffDoesNotMoveCubeFacesIntoOpaqueInterior() {
        for(int axis=0;axis<3;axis++)for(boolean positive:new boolean[]{false,true}) {
            // Forge matrix rotations can put otherwise unit-cube positions a few
            // ULPs beyond [0,1]. The plane and the tangential bounds need the same tolerance.
            var data=quad(axis,positive?Math.nextUp(1.0f):-Math.ulp(1.0f),-Math.ulp(1.0f),Math.nextUp(1.0f));
            int geometry=QuadLightGeometry.normalOffset(data,7,axis,positive);
            assertEquals(1,geometry);
            assertEquals(1,QuadLightGeometry.sampleLayer(geometry,true));
        }
    }
    @Test void crossedPlantFacesDoNotReadDarkSolidNeighbors() {
        // A tall-grass diagonal has x=z, not a constant X or Z plane. A grass
        // block in the adjacent hillside may be opaque; the plant's own cell is lit.
        int[] cross=new int[28];
        float[][] positions={{0,0,0},{0,1,0},{1,1,1},{1,0,1}};
        for(int vertex=0;vertex<4;vertex++)for(int axis=0;axis<3;axis++)
            cross[vertex*7+axis]=Float.floatToIntBits(positions[vertex][axis]);
        for(int axis:new int[]{0,2})for(boolean positive:new boolean[]{false,true}) {
            int geometry=QuadLightGeometry.normalOffset(cross,7,axis,positive);
            assertEquals(-1,geometry);
            assertEquals(0,QuadLightGeometry.sampleLayer(geometry,false));
        }
    }
    @Test void parallelInsetFullCubeFacesUseExteriorButSlabsKeepInterior() {
        int inset=QuadLightGeometry.normalOffset(quad(2,0.99f,0,1),7,2,true);
        assertEquals(0,inset);
        assertEquals(1,QuadLightGeometry.sampleLayer(inset,true));
        assertEquals(0,QuadLightGeometry.sampleLayer(inset,false));
        assertEquals(1,QuadLightGeometry.sampleLayer(1,false));
        assertEquals(0,QuadLightGeometry.sampleLayer(-1,true));
    }
    private static int[] quad(int axis,float plane,float lo,float hi) {
        int[] data=new int[4*7];
        int u=(axis+1)%3,v=(axis+2)%3;
        for(int i=0;i<4;i++) {
            data[i*7+axis]=Float.floatToIntBits(plane);
            data[i*7+u]=Float.floatToIntBits((i&1)==0 ? lo : hi);
            data[i*7+v]=Float.floatToIntBits((i&2)==0 ? lo : hi);
        }
        return data;
    }
    @Test void slabSurfacesUseTheirOwnCellWhileFullBoundaryFacesUseTheNeighbor() {
        assertEquals(0,QuadLightGeometry.normalOffset(quad(1,0.5f,0,1),7,1,true)); // Bottom slab top.
        assertEquals(0,QuadLightGeometry.normalOffset(quad(1,0.5f,0,1),7,1,false)); // Top slab underside.
        assertEquals(1,QuadLightGeometry.normalOffset(quad(1,0,0,1),7,1,false));
        assertEquals(1,QuadLightGeometry.normalOffset(quad(1,1,0,1),7,1,true));
    }
    @Test void rotatedStairRisersAndPartialBoundaryQuadsKeepTheCorrectSamplingLayer() {
        for(int axis=0;axis<3;axis++) for(boolean positive:new boolean[]{false,true}) {
            assertEquals(0,QuadLightGeometry.normalOffset(quad(axis,0.5f,0.5f,1),7,axis,positive));
            assertEquals(1,QuadLightGeometry.normalOffset(quad(axis,positive?1:0,0.5f,1),7,axis,positive));
        }
        assertEquals(0,QuadLightGeometry.normalOffset(quad(1,0.0625f,0,1),7,1,true)); // Carpet.
        assertEquals(0,QuadLightGeometry.normalOffset(quad(2,0.9375f,0,1),7,2,true)); // Thin inset model face.
    }
    @Test void nonPlanarCrossedAndOutOfBlockGeometryFallsBackWithoutInventingAPlane() {
        var vertices=quad(1,0.5f,0,1); vertices[7+1]=Float.floatToIntBits(0.75f);
        assertEquals(-1,QuadLightGeometry.normalOffset(vertices,7,1,true));
        assertEquals(-1,QuadLightGeometry.normalOffset(quad(0,0.5f,-0.1f,1),7,0,true));
        assertEquals(-1,QuadLightGeometry.normalOffset(quad(2,Float.NaN,0,1),7,2,true));
        assertEquals(-1,QuadLightGeometry.normalOffset(new int[28],8,0,true));
    }
    @Test void slabOcclusionUsesOppositeProjectionOnTheOuterAndInsetLayers() {
        // A top slab closes UP: it occludes an inset UP stencil, but not an outer UP stencil looking DOWN.
        int topSlabFaces=1<<1,bottomSlabFaces=1<<0;
        assertNotEquals(0,topSlabFaces & 1<<QuadLightGeometry.occludingFace(1,0));
        assertEquals(0,topSlabFaces & 1<<QuadLightGeometry.occludingFace(1,1));
        assertEquals(0,bottomSlabFaces & 1<<QuadLightGeometry.occludingFace(1,0));
        assertNotEquals(0,bottomSlabFaces & 1<<QuadLightGeometry.occludingFace(1,1));
        for(int face=0;face<6;face++) {
            assertEquals(face,QuadLightGeometry.occludingFace(face,0));
            assertEquals(face,QuadLightGeometry.occludingFace(QuadLightGeometry.occludingFace(face,1),1));
        }
    }
}
