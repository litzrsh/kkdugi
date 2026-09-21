package kkdugi.app.batch.models;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Runner 폐기 본문. {@code reason}은 JSON 문자열만 받는다. */
@Getter
@Setter
public class BatchRevokeRequest extends BatchStrictRequest {
    @JsonDeserialize(using = BatchStrictString.class)
    private String reason;
}
