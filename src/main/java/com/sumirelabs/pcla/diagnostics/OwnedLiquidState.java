package com.sumirelabs.pcla.diagnostics;

import java.util.regex.Pattern;

/** A native still/flowing transition at the same level does not relinquish fixture ownership. */
public final class OwnedLiquidState {
    private static final Pattern STATE=Pattern.compile("minecraft:(?:flowing_)?(water|lava)\\[level=(\\d+)\\]");
    public static boolean matches(String expected,String actual) {
        var a=STATE.matcher(expected);var b=STATE.matcher(actual);
        if(!a.matches() || !b.matches() || !a.group(1).equals(b.group(1)))return false;
        int level=Integer.parseInt(a.group(2));return level<=15 && level==Integer.parseInt(b.group(2));
    }
    private OwnedLiquidState() {}
}
