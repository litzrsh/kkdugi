package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 서버가 알리는 실제 운영 한도(byte). */
@Getter
@AllArgsConstructor
public class BatchLimits {
    private final int jsonBytes;
    private final int inputBytes;
    private final int resultBytes;
    private final int logChunkBytes;
}
