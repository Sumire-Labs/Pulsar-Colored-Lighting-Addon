package com.sumirelabs.pcla.client;

/** Axis-aligned unit-block geometry only. -1 means unsupported, 0 is inset, 1 is the outer face. */
public final class QuadLightGeometry {
    private static final float EPSILON=0.0001f;
    private QuadLightGeometry() {}
    /** Nonparallel geometry (crossed grass, flowers) uses its own cell, never a face neighbor. */
    public static int sampleLayer(int geometry,boolean fullCube) {
        if(geometry < -1 || geometry > 1)throw new IllegalArgumentException("Invalid geometry classification");
        // Like vanilla fillQuadBounds, a parallel inset face of a full cube uses
        // the exterior light: sampling inside an opaque full cube would be black.
        return geometry==1 || geometry==0 && fullCube?1:0;
    }
    public static int normalOffset(int[] vertices,int stride,int normalAxis,boolean positive) {
        if(stride<3 || normalAxis<0 || normalAxis>2 || vertices.length!=(long)stride*4) return -1;
        float plane=Float.intBitsToFloat(vertices[normalAxis]);
        for(int vertex=0;vertex<4;vertex++) for(int axis=0;axis<3;axis++) {
            float value=Float.intBitsToFloat(vertices[vertex*stride+axis]);
            if(!Float.isFinite(value) || value < -EPSILON || value > 1+EPSILON || axis==normalAxis && Math.abs(value-plane)>EPSILON) return -1;
        }
        return Math.abs(plane-(positive ? 1 : 0))<=EPSILON ? 1 : 0;
    }
    /** Minecraft face indexes are paired DOWN/UP, NORTH/SOUTH, WEST/EAST. */
    public static int occludingFace(int face,int normalOffset) {
        if(face<0 || face>5 || normalOffset<0 || normalOffset>1) throw new IllegalArgumentException("Invalid sample face");
        // Inset samples look toward the rendered face; outside samples look back toward it.
        return normalOffset==0 ? face : face^1;
    }
}
