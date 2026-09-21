package kkdugi.app.batch.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 명령의 성공 응답(2xx). 재전송 시 그대로 재현된다. {@code body}는 JSON 문자열이고 본문이 없으면 null. */
@Getter
@AllArgsConstructor
public class BatchIdempotentResponse {
    private final int status;
    private final String body;
    private final String location;
}
