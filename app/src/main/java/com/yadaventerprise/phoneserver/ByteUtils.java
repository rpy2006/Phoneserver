package com.yadaventerprise.phoneserver;

/** Small helpers for searching inside raw byte arrays (java.util has nothing built-in for this). */
public class ByteUtils {

    /** Finds the first index of `pattern` inside `data`, starting at `from`. Returns -1 if not found. */
    public static int indexOf(byte[] data, byte[] pattern, int from) {
        if (pattern.length == 0) return from;
        outer:
        for (int i = Math.max(from, 0); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
