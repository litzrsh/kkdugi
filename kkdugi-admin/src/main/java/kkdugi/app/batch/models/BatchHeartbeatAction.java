package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 배정 항목별 지시. {@code null} 필드도 JSON에 그대로 나간다(계약: leaseUntil=null이면 갱신하지 않음). */
@Getter
@AllArgsConstructor
public class BatchHeartbeatAction {
    private final String id;
    private final String action;
    private final String leaseUntil;
    private final String stopReason;
    private final Integer graceSeconds;
}
