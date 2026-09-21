package kkdugi.app.batch.models;

import java.time.Instant;

import kkdugi.app.batch.enums.IdempotencySubjectType;
import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_batch_api_request} 한 행. {@code responseText}는 jsonb를 text로 읽은 값이다. */
@Getter
@Setter
public class BatchApiRequest {
    private String id;
    private IdempotencySubjectType subjectType;
    private String subjectId;
    private String operationHash;
    private String requestKey;
    private String requestHash;
    private Instant requestedAt;
    private int httpStatus;
    private String responseText;
    private String location;
}
