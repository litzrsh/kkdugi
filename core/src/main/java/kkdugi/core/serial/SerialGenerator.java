package kkdugi.core.serial;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public interface SerialGenerator {

    String getId();

    default Long max() {
        return 9999L;
    }

    default DateFormat getDateFormat() {
        return new SimpleDateFormat("yyyyMMddHHmm");
    }

    default String getFormat() {
        return "%s04d";
    }

    default String format(Date date, Long value) {
        return String.format(getFormat(), getDateFormat().format(date), value);
    }

    default String format(String text, Long value) {
        return String.format(getFormat(), text, value);
    }
}
