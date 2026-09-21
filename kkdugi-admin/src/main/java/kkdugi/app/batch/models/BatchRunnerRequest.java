package kkdugi.app.batch.models;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Runner 생성/수정 본문. 생성은 code/name/capacity만 받고(status는 무시하지 않고 거절), 수정은 status를 선택으로 받는다.
 * 문자열·정수 필드는 JSON 토큰 타입을 엄격히 검사한다(1.9, "1" 같은 값을 capacity=1로 바꾸지 않는다).
 */
@Getter
@Setter
public class BatchRunnerRequest extends BatchStrictRequest {
    @JsonDeserialize(using = BatchStrictString.class)
    private String code;

    @JsonDeserialize(using = BatchStrictString.class)
    private String name;

    @JsonDeserialize(using = BatchStrictInteger.class)
    private Integer capacity;

    @JsonDeserialize(using = BatchStrictString.class)
    private String status;
}
