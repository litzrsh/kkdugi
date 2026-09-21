package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

/** heartbeat 응답. 서버 시각, 신규 배정 수신 여부, 배정 항목별 지시. */
@Getter
@AllArgsConstructor
public class BatchHeartbeatResponse {
    private final String serverTime;
    private final boolean acceptingAssignments;
    private final List<BatchHeartbeatAction> assignments;
}
