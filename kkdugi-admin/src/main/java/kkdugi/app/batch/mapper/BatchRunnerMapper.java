package kkdugi.app.batch.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.app.batch.enums.RunnerStatus;
import kkdugi.app.batch.models.BatchHeartbeatState;
import kkdugi.app.batch.models.BatchRunner;

@Mapper
public interface BatchRunnerMapper {

    List<BatchRunner> search(@Param("code") String code, @Param("name") String name, @Param("status") String status,
            @Param("offset") int offset, @Param("limit") int limit, @Param("onlineSeconds") int onlineSeconds);

    Optional<BatchRunner> findById(@Param("id") String id, @Param("onlineSeconds") int onlineSeconds);

    /** 상태를 바꾸는 모든 작업의 첫 문장. 행 잠금(FOR UPDATE)을 잡는다. */
    Optional<BatchRunner> lockById(@Param("id") String id);

    int countByCode(@Param("code") String code);

    int insert(BatchRunner row);

    /** 낙관적 잠금 갱신. 갱신 행 수가 0이면 버전 불일치다. config_ver를 1 올린다. */
    int update(@Param("id") String id, @Param("name") String name, @Param("capacity") int capacity,
            @Param("status") RunnerStatus status, @Param("expectedVersion") long expectedVersion,
            @Param("updaterId") String updaterId);

    /** 상태만 바꾸고 config_ver를 1 올린다. */
    int changeStatus(@Param("id") String id, @Param("status") RunnerStatus status, @Param("updaterId") String updaterId);

    /** 등록 완료: 호스트 정보를 채우고 ACTIVE로 전환하며 config_ver를 1 올린다. */
    int markRegistered(@Param("id") String id, @Param("hostname") String hostname, @Param("os") String os,
            @Param("agentVersion") String agentVersion, @Param("updaterId") String updaterId);

    /** 세션 세대를 1 올리고 boot_ref를 기록한다. config_ver와 감사 컬럼은 건드리지 않는다. */
    int openSession(@Param("id") String id, @Param("bootId") String bootId, @Param("agentVersion") String agentVersion);

    /** heartbeat: last_seen_dtm만 갱신한다. ACTIVE/PAUSED이고 세션 세대가 일치할 때만 갱신되고 결과를 돌려준다. */
    Optional<BatchHeartbeatState> touch(@Param("id") String id, @Param("session") long session);

    int deleteById(@Param("id") String id);

    /** DB 현재 시각(clock_timestamp). 서버 시각 응답의 기준이다. now()가 아니라 실제 시각이다. */
    Instant now();
}
