package kkdugi.app.batch.models;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.core.models.BaseModel;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code kkdugi_batch_runner} 한 행이자 관리자 API의 Runner 응답. bigint인 세션 세대와 설정 버전은
 * JSON에서 십진 문자열({@link #getSession()}, {@link #getVersion()})로 나가고, 부팅 식별자는 노출하지 않는다.
 */
@Getter
@Setter
@JsonIgnoreProperties({"rownum", "createdAt", "creatorId", "updatedAt", "updaterId"})
public class BatchRunner extends BaseModel {
    private String id;
    private String code;
    private String name;
    private RunnerStatus status;
    private String hostname;
    private String os;
    private String agentVersion;
    private int capacity;
    @JsonIgnore
    private long sessionVer;
    @JsonIgnore
    private String bootRef;
    private Instant lastSeenAt;
    private boolean online;
    @JsonIgnore
    private long configVer;

    public String getSession() {
        return Long.toString(sessionVer);
    }

    public String getVersion() {
        return Long.toString(configVer);
    }
}
