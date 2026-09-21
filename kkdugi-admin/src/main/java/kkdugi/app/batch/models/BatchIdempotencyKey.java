package kkdugi.app.batch.models;

import java.time.Instant;

import kkdugi.app.batch.enums.IdempotencySubjectType;
import lombok.Getter;
import lombok.Setter;

/** 멱등 범위 {@code (subjectType, subjectId, operationHash, requestKey)}와 요청 동일성 hash·key 생성 시각. */
@Getter
@Setter
public class BatchIdempotencyKey {
    private IdempotencySubjectType subjectType;
    private String subjectId;
    private String operationHash;
    private String requestKey;
    private String requestHash;
    private Instant createdAt;
}
