package kkdugi.app.batch.models;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/** heartbeat 요청. {@code freeSlots}는 JSON 정수, 나머지 텍스트는 JSON 문자열만 받는다. */
@Getter
@Setter
public class BatchHeartbeatRequest extends BatchStrictRequest {
    @JsonDeserialize(using = BatchStrictString.class)
    private String observedAt;

    @JsonDeserialize(using = BatchStrictString.class)
    private String mode;

    @JsonDeserialize(using = BatchStrictInteger.class)
    private Integer freeSlots;

    private List<BatchHeartbeatItem> assignments;
}
