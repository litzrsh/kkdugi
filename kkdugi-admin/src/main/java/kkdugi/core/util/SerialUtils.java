package kkdugi.core.util;

import java.util.ArrayDeque;
import java.util.Queue;

import kkdugi.core.serial.SerialConfig;
import kkdugi.core.serial.service.SerialService;

public abstract class SerialUtils {

    private static SerialService serialService;

    public static void setSerialService(SerialService service) throws Exception {
        if (service == null) {
            throw new IllegalArgumentException("Failed to initialize SerialUtils : SerialService is null");
        }
        if (serialService != null) {
            throw new IllegalAccessException(
                    "Failed to initialize SerialUtils : SerialService already been initialized");
        }
        serialService = service;
    }

    public static Queue<String> nextSerials(SerialConfig config, long size) {
        if (serialService == null) {
            return new ArrayDeque<>();
        }
        return serialService.nextSerials(config, size);
    }

    public static String next(SerialConfig config) {
        if (serialService == null) {
            return null;
        }
        return serialService.nextSerials(config, 1).poll();
    }
}
