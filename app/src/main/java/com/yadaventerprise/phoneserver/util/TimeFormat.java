package com.yadaventerprise.phoneserver.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Shared date/time formatting helpers for list and detail screens. */
public final class TimeFormat {

    private TimeFormat() {
    }

    /** Formats a millisecond epoch timestamp as {@code yyyy-MM-dd HH:mm}, or "-" when unset. */
    public static String formatTime(long millis) {
        if (millis <= 0) return "-";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }
}
