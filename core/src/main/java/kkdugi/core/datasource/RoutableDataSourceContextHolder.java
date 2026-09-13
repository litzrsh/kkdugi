package kkdugi.core.datasource;

public abstract class RoutableDataSourceContextHolder {

    private static final ThreadLocal<String> KEY = new ThreadLocal<>();

    public static void set(String key) {
        KEY.set(key);
    }

    public static String get() {
        return KEY.get();
    }

    public static void remove() {
        KEY.remove();
    }
}
