package com.sumirelabs.pcla.light;

/** Immutable 4-bit RGB components. Packed layout is 0xRGB; not a vanilla lightmap value. */
public record Rgb(int red, int green, int blue) {
    public static final Rgb BLACK = new Rgb(0, 0, 0);
    public static final Rgb WHITE = new Rgb(15, 15, 15);

    public Rgb {
        if ((red | green | blue) < 0 || red > 15 || green > 15 || blue > 15)
            throw new IllegalArgumentException("RGB components must be integers in 0..15");
    }

    public int packed() { return (red << 8) | (green << 4) | blue; }
    public int max() { return Math.max(red, Math.max(green, blue)); }
    public static Rgb unpack(int packed) {
        if (packed < 0 || packed > 0xfff) throw new IllegalArgumentException("Invalid packed RGB");
        return new Rgb(packed >>> 8, (packed >>> 4) & 15, packed & 15);
    }
    public static Rgb white(int level) { return new Rgb(level, level, level); }

    /** Component-wise light union; removing a source requires propagation, not subtraction. */
    public Rgb union(Rgb other) {
        return new Rgb(Math.max(red, other.red), Math.max(green, other.green), Math.max(blue, other.blue));
    }
    public Rgb attenuate(Rgb absorption) {
        return new Rgb(Math.max(0, red - absorption.red), Math.max(0, green - absorption.green),
                Math.max(0, blue - absorption.blue));
    }
    /** Treat a configured source color as a ratio, preserving its contextual emission maximum. */
    public Rgb atIntensity(int intensity) {
        if (intensity < 0 || intensity > 15) throw new IllegalArgumentException("Intensity must be in 0..15");
        int maximum = max();
        if (maximum == 0) return BLACK;
        return new Rgb(scale(red, intensity, maximum), scale(green, intensity, maximum), scale(blue, intensity, maximum));
    }
    private static int scale(int component, int level, int maximum) {
        return (component * level + maximum / 2) / maximum;
    }
}
