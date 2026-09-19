package kkdugi.core.security.mapper;

import java.util.Date;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.core.security.models.Session;

@Mapper
public interface SessionMapper {

    Optional<Session> findById(@Param("id") String id);

    Optional<Session> findByUserId(@Param("userId") String userId);

    void save(@Param("vo") Session vo);

    void extend(@Param("id") String id, @Param("expiresAt") Date expiresAt, @Param("updatedAt") Date updatedAt);

    void putAttribute(@Param("id") String id, @Param("key") String key, @Param("json") String json);

    void removeAttribute(@Param("id") String id, @Param("key") String key);

    void deleteById(@Param("id") String id);

    void deleteByUserId(@Param("userId") String userId);

    void deleteExpiredSessions();
}
