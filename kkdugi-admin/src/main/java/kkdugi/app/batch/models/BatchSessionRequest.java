package kkdugi.app.batch.models;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** 세션 개설 요청. bigint 세대는 JSON 십진 문자열로 받는다. */
@Getter
@Setter
public class BatchSessionRequest extends BatchStrictRequest {
    @JsonDeserialize(using = BatchStrictString.class)
    private String bootId;

    /**
     * bigint 생성은 JSON decimals string이며 number token은 거절된다.
     */
    @JsonDeserialize(using = BatchStrictString.class)
    private String expectedSession;

    @JsonDeserialize(using = BatchStrictString.class)
    private String agentVersion;
}
