package kkdugi.app.batch.service;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.batch.enums.ActorType;
import kkdugi.app.batch.enums.EventTargetType;
import kkdugi.app.batch.enums.EventType;
import kkdugi.app.batch.mapper.BatchEventMapper;
import kkdugi.app.batch.models.BatchEvent;
import tools.jackson.databind.ObjectMapper;

/**
 * 상태 전이·운영 감사 이력 기록. 호출자의 트랜잭션에 참여하므로 상태 변경과 event가 함께 커밋/롤백된다.
 * {@code detail}에는 토큰·비밀 값을 넣지 않는다(credential ID까지만).
 */
@Service
public class BatchEventService {

    private final BatchEventMapper mapper;
    private final ObjectMapper objectMapper;

    public BatchEventService(BatchEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(EventTargetType targetType, String targetId, EventType eventType, String fromStat,
            String toStat, ActorType actorType, String actorId, Map<String, Object> detail) {
        BatchEvent row = new BatchEvent();
        row.setId(BatchIds.next(BatchIds.EVENT));
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setEventType(eventType);
        row.setFromStat(fromStat);
        row.setToStat(toStat);
        row.setActorType(actorType);
        row.setActorId(actorId);
        row.setDetailData(detail == null || detail.isEmpty() ? null : objectMapper.writeValueAsString(detail));
        mapper.insert(row);
    }
}
