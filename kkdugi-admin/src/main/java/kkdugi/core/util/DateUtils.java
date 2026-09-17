package kkdugi.core.util;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.Date;

public abstract class DateUtils {
    
    public static Date plus(Date date, Period period) {
        Instant instant = date.toInstant();
        return Date.from(instant.plus(period));
    }
    
    public static Date plus(Date date, Duration duration) {
        Instant instant = date.toInstant();
        return Date.from(instant.plus(duration));
    }
    
    public static Date minus(Date date, Period period) {
        Instant instant = date.toInstant();
        return Date.from(instant.minus(period));
    }
    
    public static Date minus(Date date, Duration duration) {
        Instant instant = date.toInstant();
        return Date.from(instant.minus(duration));
    }

    public static long diff(Date from, Date to) {
        return Duration.between(from.toInstant(), to.toInstant()).toMillis();
    }
}
