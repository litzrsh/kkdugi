package kkdugi.app.batch.models;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Runner 등록 요청. 문자열 필드는 JSON 문자열만 받는다. */
@Getter
@Setter
public class BatchRegistrationRequest extends BatchStrictRequest {
    @JsonDeserialize(using = BatchStrictString.class)
    private String runnerCode;

    @JsonDeserialize(using = BatchStrictString.class)
    private String agentVersion;

    @JsonDeserialize(using = BatchStrictString.class)
    private String hostname;

    @JsonDeserialize(using = BatchStrictString.class)
    private String os;

    @JsonDeserialize(using = BatchStrictString.class)
    private String architecture;
}
