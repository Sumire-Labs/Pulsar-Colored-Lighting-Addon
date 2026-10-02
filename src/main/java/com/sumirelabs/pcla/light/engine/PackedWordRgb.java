package com.sumirelabs.pcla.light.engine;

/** Allocation-free arithmetic on validated sparse 0x0R0G0B working words. */
public final class PackedWordRgb {
    public static final int MASK = 0x0f0f0f;
    public static final int GUARD = 0x101010;

    private PackedWordRgb() {}

    public static int fromRgb12(int rgb) {
        return (rgb & 15) | ((rgb & 0xf0) << 4) | ((rgb & 0xf00) << 8);
    }

    public static int toRgb12(int word) {
        return (word & 15) | ((word >>> 4) & 0xf0) | ((word >>> 8) & 0xf00);
    }

    public static void validate(int word) {
        if ((word & ~MASK) != 0) throw new IllegalArgumentException("Invalid word RGB " + word);
    }

    /** Each guard survives subtraction iff its component is greater than or equal. */
    public static int union(int a, int b) {
        int ge = ((a | GUARD) - b) & GUARD;
        int mask = (ge >>> 4) * 15;
        return ((a & mask) | (b & ~mask)) & MASK;
    }

    public static boolean anyGreater(int candidate, int current) {
        return (((current | GUARD) - candidate) & GUARD) != GUARD;
    }

    /**
     * Guards isolate each byte: 16 + component - loss lies in [1, 31], so
     * subtraction cannot borrow into another byte. A cleared guard selects zero.
     * Zero absorption becomes one, preserving strict BLOCK loss on every edge.
     */
    public static int attenuateBlock(int light, int absorption) {
        int zeros = (~((absorption | GUARD) - 0x010101)) & GUARD;
        int loss = absorption | (zeros >>> 4);
        int diff = (light | GUARD) - loss;
        int under = (~diff) & GUARD;
        int clear = under - (under >>> 4);
        return diff & ~clear & MASK;
    }

    /** Dependent components use old light only; newly changed opacity is irrelevant. */
    public static int dependentComponents(int neighbor, int removed) {
        int less = (~((neighbor | GUARD) - removed)) & GUARD;
        return neighbor & (less - (less >>> 4)) & MASK;
    }
}
