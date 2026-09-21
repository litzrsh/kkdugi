package kkdugi.app.batch.models;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** heartbeat가 보고하는 배정 항목(배정 ID와 진행 단계). */
@Getter
@Setter
public class BatchHeartbeatItem extends BatchStrictRequest {
    @JsonDeserialize(using = BatchStrictString.class)
    private String id;

    @JsonDeserialize(using = BatchStrictString.class)
    private String phase;
}
