package kkdugi.app.batch.service;

import kkdugi.core.serial.SerialConfig;
import kkdugi.core.util.SerialUtils;

/** 배치 ID 채번 설정. 형식은 접두사 2자 + yyyyMMddHHmm + %04d (18자, 20자 도메인 안). */
public final class BatchIds {

    public static final SerialConfig RUNNER = config("KKDUGI_BATCH_RUNNER", "BR");
    public static final SerialConfig CREDENTIAL = config("KKDUGI_BATCH_CREDENTIAL", "BC");
    public static final SerialConfig EVENT = config("KKDUGI_BATCH_EVENT", "BE");
    public static final SerialConfig API_REQUEST = config("KKDUGI_BATCH_API_REQUEST", "BQ");

    private BatchIds() {
    }

    public static String next(SerialConfig config) {
        String id = SerialUtils.next(config);
        if (id == null) {
            throw new IllegalStateException("SerialUtils is not initialized");
        }
        return id;
    }

    private static SerialConfig config(String id, String prefix) {
        return new SerialConfig() {

            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getValueFormatter() {
                return prefix + "%s%04d";
            }
        };
    }
}
