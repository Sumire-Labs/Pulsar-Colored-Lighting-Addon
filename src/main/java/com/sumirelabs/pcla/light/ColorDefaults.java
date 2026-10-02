package com.sumirelabs.pcla.light;

import java.util.*;

/** Initial 1.12.2 palette. Values are ratios, scaled to the block's contextual emission. */
public final class ColorDefaults {
    private static final Map<String, Rgb> BASE_EMISSION = Map.ofEntries(
            Map.entry("minecraft:torch", new Rgb(15, 11, 6)),
            Map.entry("minecraft:glowstone", new Rgb(15, 13, 7)),
            Map.entry("minecraft:lava", new Rgb(15, 7, 2)),
            Map.entry("minecraft:flowing_lava", new Rgb(15, 7, 2)),
            Map.entry("minecraft:fire", new Rgb(15, 10, 3)),
            Map.entry("minecraft:redstone_torch", new Rgb(15, 2, 1)),
            Map.entry("minecraft:lit_redstone_lamp", new Rgb(15, 11, 6)),
            Map.entry("minecraft:lit_furnace", new Rgb(15, 11, 7)),
            Map.entry("minecraft:portal", new Rgb(10, 3, 15)),
            Map.entry("thaumcraft:crystal_aer", new Rgb(15, 15, 3)),
            Map.entry("thaumcraft:crystal_ignis", new Rgb(15, 3, 1)),
            Map.entry("thaumcraft:crystal_aqua", new Rgb(2, 6, 15)),
            Map.entry("thaumcraft:crystal_terra", new Rgb(2, 15, 3)),
            Map.entry("thaumcraft:crystal_ordo", Rgb.WHITE),
            Map.entry("thaumcraft:crystal_perditio", new Rgb(7, 5, 9)),
            Map.entry("thaumcraft:crystal_vitium", new Rgb(12, 3, 15)),
            Map.entry("minecraft:lit_redstone_ore", new Rgb(15, 3, 3)),
            Map.entry("minecraft:powered_repeater", new Rgb(15, 2, 2)),
            Map.entry("minecraft:powered_comparator", new Rgb(15, 2, 2)),
            Map.entry("minecraft:lit_pumpkin", new Rgb(15, 13, 11)),
            Map.entry("minecraft:brewing_stand", new Rgb(15, 15, 0)),
            Map.entry("minecraft:brown_mushroom", new Rgb(15, 15, 0)),
            Map.entry("minecraft:ender_chest", new Rgb(3, 7, 6)),
            Map.entry("minecraft:end_portal", new Rgb(7, 15, 11)),
            Map.entry("minecraft:end_portal_frame", Rgb.WHITE),
            Map.entry("minecraft:dragon_egg", Rgb.WHITE),
            Map.entry("minecraft:beacon", Rgb.WHITE),
            Map.entry("minecraft:end_rod", Rgb.WHITE),
            Map.entry("minecraft:sea_lantern", new Rgb(10, 15, 15)));
    // Color names are TC6 registry suffixes, not TC4 metadata or vanilla's state spelling.
    private static final Map<String,Rgb> TC6_DYES = Map.ofEntries(
            Map.entry("white",new Rgb(14,14,14)),Map.entry("orange",new Rgb(14,8,4)),
            Map.entry("magenta",new Rgb(11,5,12)),Map.entry("lightblue",new Rgb(6,8,12)),
            Map.entry("yellow",new Rgb(13,12,2)),Map.entry("lime",new Rgb(4,12,3)),
            Map.entry("pink",new Rgb(13,8,9)),Map.entry("gray",new Rgb(4,4,4)),
            Map.entry("silver",new Rgb(9,9,9)),Map.entry("cyan",new Rgb(2,7,9)),
            Map.entry("purple",new Rgb(7,3,11)),Map.entry("blue",new Rgb(2,3,9)),
            Map.entry("brown",new Rgb(5,3,2)),Map.entry("green",new Rgb(3,5,2)),
            Map.entry("red",new Rgb(11,3,3)),Map.entry("black",new Rgb(2,2,2)));
    private static final Map<String,Rgb> EMISSION = emissionDefaults();
    private static Map<String,Rgb> emissionDefaults() {
        var result=new TreeMap<>(BASE_EMISSION);
        TC6_DYES.forEach((color,rgb)->{result.put("thaumcraft:candle_"+color,rgb);result.put("thaumcraft:nitor_"+color,rgb);});
        return Collections.unmodifiableMap(result);
    }
    private static final Map<String, Rgb> GLASS = Map.ofEntries(
            Map.entry("white", new Rgb(14, 14, 14)), Map.entry("orange", new Rgb(14, 8, 2)),
            Map.entry("magenta", new Rgb(14, 4, 12)), Map.entry("light_blue", new Rgb(6, 10, 14)),
            Map.entry("yellow", new Rgb(14, 14, 2)), Map.entry("lime", new Rgb(8, 14, 2)),
            Map.entry("pink", new Rgb(14, 8, 10)), Map.entry("gray", new Rgb(5, 5, 5)),
            Map.entry("silver", new Rgb(10, 10, 10)), Map.entry("cyan", new Rgb(2, 12, 12)),
            Map.entry("purple", new Rgb(10, 3, 14)), Map.entry("blue", new Rgb(2, 3, 14)),
            Map.entry("brown", new Rgb(8, 5, 2)), Map.entry("green", new Rgb(3, 9, 2)),
            Map.entry("red", new Rgb(14, 2, 2)), Map.entry("black", new Rgb(2, 2, 2)));
    private ColorDefaults() {}
    public static Rgb emission(String id) { return EMISSION.getOrDefault(id, Rgb.WHITE); }
    /** Read-only catalog for configuration tools; colors are ratios, never forced emission levels. */
    public static Map<String,Rgb> sources(){return EMISSION;}
    /** Filter values are per-channel transmittance, with 15 meaning no additional absorption. */
    public static Rgb filter(String id, Map<String, String> state) {
        if (!id.equals("minecraft:stained_glass") && !id.equals("minecraft:stained_glass_pane")) return Rgb.WHITE;
        String color = state.get("color");
        return color == null ? Rgb.WHITE : GLASS.getOrDefault(color, Rgb.WHITE);
    }
    public static String signature() { return new TreeMap<>(EMISSION) + ";" + new TreeMap<>(GLASS); }
}
