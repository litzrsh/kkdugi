package kkdugi.app.batch.models;

import kkdugi.app.batch.enums.ActorType;
import kkdugi.app.batch.enums.EventTargetType;
import kkdugi.app.batch.enums.EventType;
import kkdugi.core.models.BaseModel;
import lombok.Getter;
import lombok.Setter;

/** {@code kkdugi_batch_event} 한 행(수정 불가 이력). {@code detailData}는 JSON 문자열이다. */
@Getter
@Setter
public class BatchEvent extends BaseModel {

    private String id;
    private EventTargetType targetType;
    private String targetId;
    private EventType eventType;
    private String fromStat;
    private String toStat;
    private ActorType actorType;
    private String actorId;
    private String detailData;
}
