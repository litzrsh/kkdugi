package kkdugi.app.batch.mapper;

import java.time.Instant;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.app.batch.enums.IdempotencySubjectType;
import kkdugi.app.batch.models.BatchApiRequest;

@Mapper
public interface BatchApiRequestMapper {

    /** 트랜잭션 범위 advisory lock. 같은 lockKey의 요청을 직렬화한다. 항상 1을 돌려준다. */
    int lock(@Param("lockKey") String lockKey);

    /** 만료되지 않은 기록만 찾는다. */
    Optional<BatchApiRequest> findActive(@Param("subjectType") IdempotencySubjectType subjectType,
            @Param("subjectId") String subjectId, @Param("operationHash") String operationHash,
            @Param("requestKey") String requestKey);

    /** 같은 key의 만료된 기록을 지운다(재삽입 시 UQ 충돌 방지). */
    int deleteExpired(@Param("subjectType") IdempotencySubjectType subjectType, @Param("subjectId") String subjectId,
            @Param("operationHash") String operationHash, @Param("requestKey") String requestKey);

    int insert(@Param("row") BatchApiRequest row, @Param("retentionSeconds") long retentionSeconds);

    /** key 생성 시각이 DB 시각과 skewSeconds 이내인지. */
    boolean isFresh(@Param("createdAt") Instant createdAt, @Param("skewSeconds") long skewSeconds);
}
