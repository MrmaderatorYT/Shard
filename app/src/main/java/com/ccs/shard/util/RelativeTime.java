package com.ccs.shard.util;

import android.content.Context;
import android.text.format.DateUtils;

import java.util.Locale;

/**
 * Human-readable timestamps.
 *
 * <p>Delegates to {@link DateUtils}, which is already localised for every
 * language the platform ships — the previous hand-rolled version produced
 * English strings ("3 hours ago") even in the Ukrainian UI.
 */
public final class RelativeTime {

    private RelativeTime() {}

    /** e.g. "just now", "5 min ago", "yesterday", "12 Mar". */
    public static CharSequence format(Context context, long millis) {
        if (millis <= 0) return "";
        long now = System.currentTimeMillis();
        long delta = now - millis;
        if (delta < DateUtils.MINUTE_IN_MILLIS) {
            return context.getString(com.ccs.shard.R.string.just_now);
        }
        if (delta < DateUtils.WEEK_IN_MILLIS) {
            return DateUtils.getRelativeTimeSpanString(
                    millis, now, DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE);
        }
        int flags = DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR;
        if (!isSameYear(millis, now)) flags = DateUtils.FORMAT_ABBREV_MONTH;
        return DateUtils.formatDateTime(context, millis, flags);
    }

    /** Absolute date and time, for detail screens. */
    public static String absolute(long millis) {
        if (millis <= 0) return "";
        return new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                .format(new java.util.Date(millis));
    }

    private static boolean isSameYear(long a, long b) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(a);
        int yearA = calendar.get(java.util.Calendar.YEAR);
        calendar.setTimeInMillis(b);
        return yearA == calendar.get(java.util.Calendar.YEAR);
    }
}
