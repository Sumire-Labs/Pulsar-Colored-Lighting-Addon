package com.sumirelabs.pcla.light;

/** Immutable color decisions. Contextual emission/opacity are supplied at each lookup. */
public final class CompiledColor {
    private final short[] emissions = new short[16];
    private final short[] absorptions = new short[16];

    CompiledColor(Rgb ratio, Rgb filterAbsorption) {
        for (int level = 0; level < 16; level++) {
            emissions[level] = (short) ratio.atIntensity(level).packed();
            absorptions[level] = (short) new Rgb(Math.max(level, filterAbsorption.red()),
                    Math.max(level, filterAbsorption.green()), Math.max(level, filterAbsorption.blue())).packed();
        }
    }

    /** Returns 0xRGB without allocating. Does not query or cache a block's emission. */
    public int emission(int contextualEmission) {
        if (contextualEmission < 0 || contextualEmission > 15)
            throw new IllegalArgumentException("Emission must be in 0..15");
        return emissions[contextualEmission];
    }

    /**
     * Returns absorption, not transmitted light. The engine must supply the ordinary
     * directional opacity and apply its lane's minimum-step/downward-skylight rules.
     */
    public int absorption(int contextualOpacity) {
        if (contextualOpacity < 0) throw new IllegalArgumentException("Negative opacity");
        return absorptions[Math.min(15, contextualOpacity)];
    }
}
