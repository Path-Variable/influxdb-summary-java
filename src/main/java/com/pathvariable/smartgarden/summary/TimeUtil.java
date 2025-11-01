package com.pathvariable.smartgarden.summary;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Time utilities for scheduling.
 */
public final class TimeUtil {
    private TimeUtil() {}

    public static long computeInitialDelayToNextInterval(int interval, String timezone) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));
        int minute = now.getMinute();
        int nextSlot = ((minute / interval) + 1) * interval;
        if (nextSlot >= 60) {
            ZonedDateTime nextHour = now.plusHours(1).withMinute(0).withSecond(0).withNano(0);
            return Duration.between(now, nextHour).toMillis();
        } else {
            ZonedDateTime next = now.withMinute(nextSlot).withSecond(0).withNano(0);
            return Duration.between(now, next).toMillis();
        }
    }
}
