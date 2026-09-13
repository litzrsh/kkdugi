package kkdugi.core.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.Date;

public abstract class DateUtils {

    public static final String DATE_PATTERN = "yyyyMMdd";
    public static final String TIME_PATTERN = "yyyyMMddHHmmss";

    public static Date add(Date date, Period period) {
        Instant instant = date.toInstant();
        return Date.from(instant.plus(period));
    }

    public static Date add(Date date, Duration duration) {
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

    public static Duration diff(Date from, Date to) {
        return Duration.between(from.toInstant(), to.toInstant());
    }

    public static String format(Date date) {
        return format(date, DATE_PATTERN);
    }

    public static String format(Date date, String pattern) {
        return new SimpleDateFormat(pattern).format(date);
    }

    public static Date parse(String source) {
        return parse(source, DATE_PATTERN);
    }

    public static Date parse(String source, String pattern) {
        try {
            return new SimpleDateFormat(pattern).parse(source);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid date value: " + source, e);
        }
    }
}
