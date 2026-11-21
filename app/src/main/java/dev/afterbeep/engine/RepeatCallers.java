package dev.afterbeep.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * iPhone-style "Repeated calls": someone who calls again right after their last call is probably trying to reach
 * you urgently, so the second call rings through instead of going to voicemail.
 * Pure Java, no Android, so it can be unit-tested on a plain JVM. In memory only: an engine restart forgets.
 */
final class RepeatCallers {
    static final long WINDOW_MS = 3 * 60 * 1000;

    private final Map<String, Long> lastCall = new HashMap<>();

    /** True if [number] called within the window before [now]. Hidden numbers never count. */
    boolean isRecent(String number, long now) {
        Long last = lastCall.get(digitsTail(number));
        return last != null && now - last <= WINDOW_MS;
    }

    /** Remember that [number] just called (call at the end of each call, so the window starts after it). */
    void remember(String number, long now) {
        lastCall.values().removeIf(t -> now - t > WINDOW_MS);
        String n = digitsTail(number);
        if (!n.isEmpty()) lastCall.put(n, now);
    }

    /** Last 10 digits, so "+91 98765 43210" and "098765 43210" match. */
    static String digitsTail(String s) {
        String d = s == null ? "" : s.replaceAll("[^0-9]", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }
}
