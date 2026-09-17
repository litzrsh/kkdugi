package kkdugi.core.serial;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;

public interface SerialConfig {

    String getId();

    default Duration getDuration() {
        return Duration.ofMinutes(1);
    }

    default DateFormat getDateFormat() {
        return new SimpleDateFormat("yyyyMMddHHmm");
    }

    default long getLimit() {
        return 9999L;
    }

    default String getValueFormatter() {
        return "%s%04d";
    }

    default String format(Date date, long value) {
        return String.format(getValueFormatter(), getDateFormat().format(date), value);
    }
}
