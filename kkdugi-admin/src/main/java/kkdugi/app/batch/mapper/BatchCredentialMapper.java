package kkdugi.app.batch.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.app.batch.models.BatchRunnerCredential;

@Mapper
public interface BatchCredentialMapper {

    /** expires_dtm = DB now() + ttlSeconds. */
    int insert(@Param("row") BatchRunnerCredential row, @Param("ttlSeconds") long ttlSeconds);

    Optional<BatchRunnerCredential> findForAuth(@Param("id") String id);

    /** 미사용·미폐기·미만료 등록 토큰을 원자적으로 소비한다. 1이면 성공. */
    int consumeEnrollment(@Param("id") String id);

    int revokeUnusedEnrollments(@Param("runnerId") String runnerId);

    int revokeAll(@Param("runnerId") String runnerId);

    /** last_used_dtm이 없거나 intervalSeconds보다 오래됐을 때만 갱신한다. */
    int touchLastUsed(@Param("id") String id, @Param("intervalSeconds") int intervalSeconds);

    int deleteByRunnerId(@Param("runnerId") String runnerId);
}
