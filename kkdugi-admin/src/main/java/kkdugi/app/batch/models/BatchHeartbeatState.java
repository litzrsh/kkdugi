package kkdugi.app.batch.models;

import kkdugi.app.batch.enums.RunnerStatus;
import lombok.Getter;
import lombok.Setter;

/** heartbeat 갱신 직후 관측한 runner 상태. */
@Getter
@Setter
public class BatchHeartbeatState {
    private RunnerStatus status;
    private int capacity;
}
