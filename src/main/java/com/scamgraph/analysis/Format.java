package com.scamgraph.analysis;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Small helpers to show money, time and durations the Indian way. */
public final class Format {

    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd MMM, HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private Format() {
    }

    /** 4250000 -> "Rs 42.5 L", 45000 -> "Rs 45,000", 12500000 -> "Rs 1.25 Cr". */
    public static String rupees(long amount) {
        if (amount >= 1_00_00_000L) return "₹" + trim(amount / 1_00_00_000.0) + " Cr";
        if (amount >= 1_00_000L) return "₹" + trim(amount / 1_00_000.0) + " L";
        return "₹" + indianGrouping(amount);
    }

    /** 1234567 -> "12,34,567" (Indian digit grouping). */
    public static String indianGrouping(long amount) {
        String s = Long.toString(Math.abs(amount));
        if (s.length() <= 3) return (amount < 0 ? "-" : "") + s;
        String last3 = s.substring(s.length() - 3);
        String rest = s.substring(0, s.length() - 3);
        StringBuilder sb = new StringBuilder();
        while (rest.length() > 2) {
            sb.insert(0, "," + rest.substring(rest.length() - 2));
            rest = rest.substring(0, rest.length() - 2);
        }
        sb.insert(0, rest);
        return (amount < 0 ? "-" : "") + sb + "," + last3;
    }

    private static String trim(double v) {
        String s = String.format("%.2f", v);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }

    /** Seconds -> "25 min", "3.5 hours", "2.1 days". */
    public static String duration(double seconds) {
        if (Double.isNaN(seconds)) return "-";
        if (seconds < 60) return Math.round(seconds) + " sec";
        double minutes = seconds / 60;
        if (minutes < 90) return Math.round(minutes) + " min";
        double hours = minutes / 60;
        if (hours < 48) return String.format("%.1f hours", hours);
        return String.format("%.1f days", hours / 24);
    }

    public static String time(LocalDateTime t) {
        return t == null ? "" : TIME.format(t);
    }

    public static String shortTime(LocalDateTime t) {
        return t == null ? "" : SHORT.format(t);
    }

    public static String date(LocalDateTime t) {
        return t == null ? "" : DATE.format(t);
    }

    public static double clamp01(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0, Math.min(1, v));
    }
}
