package kkdugi.core.security.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.core.security.models.Session;

@Mapper
public interface SessionMapper {

    Optional<Session> findById(@Param("id") String id);

    Optional<Session> findByUserId(@Param("userId") String userId);

    void save(@Param("vo") Session vo);

    void deleteById(@Param("id") String id);

    void deleteByUserId(@Param("userId") String userId);

    void deleteExpiredSessions();
}
