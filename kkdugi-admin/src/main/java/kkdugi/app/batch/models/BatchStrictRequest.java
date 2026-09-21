package kkdugi.app.batch.models;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * 배치 API 요청 본문의 공통 부모. 계약상 알 수 없는 요청 필드는 400이다. Spring Boot는 전역으로
 * FAIL_ON_UNKNOWN_PROPERTIES를 끄므로 any-setter에서 거절한다(역직렬화 실패 → HttpMessageNotReadableException).
 */
public abstract class BatchStrictRequest {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown field: " + name);
    }
}
