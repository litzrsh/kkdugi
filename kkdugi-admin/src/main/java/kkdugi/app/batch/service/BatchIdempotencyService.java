package kkdugi.app.batch.service;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.batch.config.BatchProperties;
import kkdugi.app.batch.exceptions.BatchErrors;
import kkdugi.app.batch.exceptions.BatchException;
import kkdugi.app.batch.mapper.BatchApiRequestMapper;
import kkdugi.app.batch.models.BatchApiRequest;
import kkdugi.app.batch.models.BatchIdempotencyKey;
import kkdugi.app.batch.models.BatchIdempotentResponse;

/**
 * 관리자 명령의 멱등 실행. 명령·event·응답 기록이 한 트랜잭션이라 명령이 실패(롤백)하면 기록도 남지 않는다.
 * 호출 전에 인증·현재 권한 검사가 끝나 있어야 한다(재현 응답도 현재 권한으로 보호된다).
 */
@Service
public class BatchIdempotencyService {

    private final BatchApiRequestMapper mapper;
    private final BatchProperties properties;

    public BatchIdempotencyService(BatchApiRequestMapper mapper, BatchProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Transactional
    public BatchIdempotentResponse execute(BatchIdempotencyKey key, Supplier<BatchIdempotentResponse> command) {
        mapper.lock(String.join("|", key.getSubjectType().getCode(), key.getSubjectId(), key.getOperationHash(),
                key.getRequestKey()));

        Optional<BatchApiRequest> existing = mapper.findActive(key.getSubjectType(), key.getSubjectId(),
                key.getOperationHash(), key.getRequestKey());
        if (existing.isPresent()) {
            BatchApiRequest saved = existing.get();
            if (!saved.getRequestHash().equals(key.getRequestHash())) {
                throw BatchException.conflict(BatchErrors.IDEMPOTENCY_CONFLICT);
            }
            return new BatchIdempotentResponse(saved.getHttpStatus(), saved.getResponseText(), saved.getLocation());
        }
        // 기록이 정리된 오래된 key를 새 명령으로 받아들이지 않도록 신규 key의 생성 시각 창을 검사한다.
        if (!mapper.isFresh(key.getCreatedAt(), properties.getRequestSkew().toSeconds())) {
            throw BatchException.gone(BatchErrors.REQUEST_EXPIRED);
        }

        BatchIdempotentResponse response = command.get();

        mapper.deleteExpired(key.getSubjectType(), key.getSubjectId(), key.getOperationHash(), key.getRequestKey());
        BatchApiRequest row = new BatchApiRequest();
        row.setId(BatchIds.next(BatchIds.API_REQUEST));
        row.setSubjectType(key.getSubjectType());
        row.setSubjectId(key.getSubjectId());
        row.setOperationHash(key.getOperationHash());
        row.setRequestKey(key.getRequestKey());
        row.setRequestHash(key.getRequestHash());
        row.setRequestedAt(key.getCreatedAt());
        row.setHttpStatus(response.getStatus());
        row.setResponseText(response.getBody());
        row.setLocation(response.getLocation());
        mapper.insert(row, properties.getIdempotencyRetention().toSeconds());
        return response;
    }
}
