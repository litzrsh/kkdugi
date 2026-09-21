package kkdugi.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.models.BatchIdempotentResponse;
import kkdugi.core.exceptions.ExceptionMessage;

/**
 * 배치 컨트롤러의 공통 부모. 오류를 계약의 {@code {code, message}}로 바꾼다. 컨트롤러 안의
 * {@code @ExceptionHandler}는 전역 {@code RestfulExceptionAdvice}보다 우선하므로 core를 수정하지 않는다.
 */
public abstract class BatchApiSupport {

    @ExceptionHandler(BatchException.class)
    public ResponseEntity<ExceptionMessage> handleBatch(BatchException e) {
        return ResponseEntity.status(e.getStatus()).body(new ExceptionMessage(e.getCode()));
    }

    /** 잘못된 JSON, 알 수 없는 필드({@code BatchStrictRequest}), 타입 불일치, 쿼리 바인딩 실패. */
    @ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            BindException.class })
    public ResponseEntity<ExceptionMessage> handleMalformed(Exception e) {
        return ResponseEntity.badRequest().body(new ExceptionMessage(BatchErrors.REQUEST_INVALID));
    }

    /** 멱등 명령의 저장된/신규 응답을 그대로 내보낸다(본문은 이미 직렬화된 JSON). */
    protected static ResponseEntity<String> json(BatchIdempotentResponse response) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.getStatus());
        if (response.getLocation() != null) {
            builder.header(HttpHeaders.LOCATION, response.getLocation());
        }
        if (response.getBody() == null) {
            return builder.build();
        }
        return builder.contentType(MediaType.APPLICATION_JSON).body(response.getBody());
    }
}
