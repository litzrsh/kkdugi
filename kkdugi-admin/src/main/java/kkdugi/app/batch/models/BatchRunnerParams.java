package kkdugi.app.batch.models;

import kkdugi.core.models.BaseParams;

import lombok.Getter;
import lombok.Setter;

/**
 * Runner 목록 필터. GET 쿼리 파라미터로 바인딩된다. {@code status}는 문자열로 받아 서비스에서 검증한다
 * (잘못된 값이 바인딩 단계에서 실패하지 않고 계약의 400 {@code batch.request.invalid}가 되도록).
 */
@Getter
@Setter
public class BatchRunnerParams extends BaseParams {
    private String code;
    private String name;
    private String status;
}
