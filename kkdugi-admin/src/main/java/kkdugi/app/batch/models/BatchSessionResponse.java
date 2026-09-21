package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 세션 개설 응답. 서버가 실제 heartbeat/polling/lease 주기와 한도를 알려준다. */
@Getter
@AllArgsConstructor
public class BatchSessionResponse {
    private final String runnerId;
    private final String session;
    private final String serverTime;
    private final int heartbeatSeconds;
    private final int pollSeconds;
    private final int leaseSeconds;
    private final int capacity;
    private final BatchLimits limits;
}
