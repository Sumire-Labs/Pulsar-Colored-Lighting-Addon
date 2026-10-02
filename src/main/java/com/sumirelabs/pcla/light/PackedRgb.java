package com.sumirelabs.pcla.light;

/** Allocation-free operations on validated 0xRGB values. Components never borrow across nibbles. */
public final class PackedRgb {
    private PackedRgb() {}

    public static int union(int a, int b) {
        return Math.max(a & 0xf00, b & 0xf00)
                | Math.max(a & 0xf0, b & 0xf0) | Math.max(a & 15, b & 15);
    }

    /** Block light loses at least one level per edge, even through opacity-zero blocks. */
    public static int attenuateBlock(int light, int absorption) {
        int red = Math.max(0, (light >>> 8) - Math.max(1, absorption >>> 8));
        int green = Math.max(0, (light >>> 4 & 15) - Math.max(1, absorption >>> 4 & 15));
        int blue = Math.max(0, (light & 15) - Math.max(1, absorption & 15));
        return red << 8 | green << 4 | blue;
    }

    /**
     * Components that could have depended on a removed neighboring value. This
     * deliberately ignores NEW face opacity: old light may have crossed an open face.
     * Valid only for lanes with strictly positive attenuation on every edge.
     */
    public static int dependentComponents(int neighbor, int removed) {
        return ((neighbor & 0xf00) < (removed & 0xf00) ? neighbor & 0xf00 : 0)
                | ((neighbor & 0xf0) < (removed & 0xf0) ? neighbor & 0xf0 : 0)
                | ((neighbor & 15) < (removed & 15) ? neighbor & 15 : 0);
    }

    public static void validate(int color) {
        if ((color & ~0xfff) != 0) throw new IllegalArgumentException("Invalid packed RGB: " + color);
    }
}
