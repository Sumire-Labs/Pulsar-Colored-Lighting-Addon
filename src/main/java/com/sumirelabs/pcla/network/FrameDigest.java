package com.sumirelabs.pcla.network;

import java.security.*;
import java.util.HexFormat;

/** Diagnostics only; excludes headers so actual installed values can be compared with sent values. */
public final class FrameDigest {
    public static String sha256(RegionFrame frame) {
        return lane(frame,false);
    }
    public static String skySha256(RegionFrame frame) { return lane(frame,true); }
    private static String lane(RegionFrame frame,boolean sky) {
        if(sky ? !frame.hasSky() : !frame.hasBlock()) return "absent";
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(int cell=0;cell<frame.cells();cell++) { int rgb=sky ? frame.skyColor(cell) : frame.color(cell); digest.update((byte)(rgb>>8)); digest.update((byte)rgb); }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
