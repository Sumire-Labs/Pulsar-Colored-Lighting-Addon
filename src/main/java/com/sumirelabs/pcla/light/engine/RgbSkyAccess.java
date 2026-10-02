package com.sumirelabs.pcla.light.engine;

/**
 * Stable single-writer sky view, with validated 0xRGB values. Directions are
 * +X,-X,+Y,-Y,+Z,-Z; neighbors are reciprocal and -1 means unavailable/outside.
 * The caller must supply established incoming sky boundary values. A 15-cell
 * block-light halo cannot establish these: full sky can travel arbitrarily far
 * downward. Unknown incoming sky must remain unavailable, not assumed white.
 */
public interface RgbSkyAccess {
    int cellCount();
    int neighbor(int cell,int direction);
    /** Fixed incoming contribution already attenuated into this cell; not block emission. */
    int boundaryLight(int cell);
    /** Absorption on the directed edge leaving cell, including source/target face occlusion. */
    int absorption(int cell,int direction);
    int light(int cell);
    void light(int cell,int rgb);
}
