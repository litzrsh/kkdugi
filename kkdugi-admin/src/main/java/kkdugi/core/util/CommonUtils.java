package kkdugi.core.util;

import java.util.Collection;

public abstract class CommonUtils {
    
    public static String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static boolean isEmpty(String value) {
        return trim(value).isEmpty();
    }

    public static boolean isNotEmpty(String value) {
        return !isEmpty(value);
    }

    public static <T> boolean isEmpty(T[] array) {
        return array == null || array.length == 0;
    }

    public static <T> boolean isNotEmpty(T[] array) {
        return !isEmpty(array);
    }

    public static <T> boolean isEmpty(Collection<T> collection) {
        return collection == null || collection.isEmpty();
    }

    public static <T> boolean isNotEmpty(Collection<T> collection) {
        return !isEmpty(collection);
    }
}
