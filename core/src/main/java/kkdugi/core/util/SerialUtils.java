package kkdugi.core.util;

import java.util.ArrayDeque;
import java.util.Queue;

import kkdugi.core.serial.SerialGenerator;
import kkdugi.core.serial.service.SerialService;

public abstract class SerialUtils {

    private static SerialService serialService;

    public static void setSerialService(SerialService serialService) throws Exception {
        if (serialService == null)
            throw new IllegalArgumentException("SerialService cannot be null");
        if (SerialUtils.serialService != null)
            throw new IllegalAccessException("SerialSerive already been initialized");
        SerialUtils.serialService = serialService;
    }

    public static Queue<String> nexts(SerialGenerator gen, long size) {
        if (serialService == null) return new ArrayDeque<>();
        return serialService.getSerials(gen, size);
    }

    public static String next(SerialGenerator gen) {
        if (serialService == null) return null;
        Queue<String> serials = serialService.getSerials(gen, 1L);
        return CommonUtils.isEmpty(serials) ? null : serials.poll();
    }
}
