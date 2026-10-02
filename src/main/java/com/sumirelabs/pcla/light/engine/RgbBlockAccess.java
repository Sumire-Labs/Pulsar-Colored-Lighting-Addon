package com.sumirelabs.pcla.light.engine;

/**
 * Stable indexed view for one block-light task. The caller owns world locking,
 * chunk availability, contextual block/face queries and snapshot publication.
 * All colors are validated 0xRGB. Cell IDs are in [0, cellCount).
 * Topological neighbors must be reciprocal, even when their light faces are
 * blocked. A missing neighbor is -1 and contributes no light; chunk boundaries
 * need explicit seeding/reconciliation by the world adapter.
 */
public interface RgbBlockAccess {
    int cellCount();
    /** Directions are +X, -X, +Y, -Y, +Z, -Z, with opposite = direction ^ 1. */
    int neighbor(int cell, int direction);
    int emission(int cell);
    /** New directional absorption for the edge leaving cell, including face occlusion. */
    int absorption(int cell, int direction);
    int light(int cell);
    void light(int cell, int packedRgb);
}
