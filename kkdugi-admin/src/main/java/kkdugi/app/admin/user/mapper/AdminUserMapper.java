package kkdugi.app.admin.user.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.app.admin.user.models.UserAuthority;
import kkdugi.app.admin.user.models.UserBase;
import kkdugi.core.enums.PasswordStatus;
import kkdugi.core.enums.UserStatus;

@Mapper
public interface AdminUserMapper {

    List<UserBase> search(@Param("username") String username, @Param("name") String name,
            @Param("status") UserStatus status, @Param("offset") int offset, @Param("pageSize") int pageSize);

    Optional<UserBase> findById(@Param("id") String id);

    Optional<UserBase> findByUsername(@Param("username") String username);

    Optional<UserBase> findByEmail(@Param("email") String email);

    int insert(UserBase row);

    int update(UserBase row);

    /** {@code ids}는 비어 있으면 안 된다(빈 목록은 {@code IN ()} SQL 구문 오류). */
    List<String> findExistingIds(@Param("ids") List<String> ids);

    int updatePassword(@Param("id") String id, @Param("password") String password,
            @Param("passwordStatus") PasswordStatus passwordStatus, @Param("changedAt") LocalDateTime changedAt,
            @Param("updaterId") String updaterId);

    /** {@code ids}는 비어 있으면 안 된다. */
    int updateStatus(@Param("ids") List<String> ids, @Param("status") UserStatus status,
            @Param("updatedAt") LocalDateTime updatedAt, @Param("updaterId") String updaterId);

    int deleteSessionsByUserId(@Param("userId") String userId);

    int deleteUserAuthsByUserId(@Param("userId") String userId);

    int deleteById(@Param("id") String id);

    List<UserAuthority> findAuthoritiesByUserId(@Param("userId") String userId);

    /** {@code authorityIds}는 비어 있으면 안 된다. */
    List<String> findExistingAuthorityIds(@Param("authorityIds") List<String> authorityIds);

    List<UserAuthority> findAuthorityCandidates(@Param("userId") String userId, @Param("query") String query);

    int upsertAuthority(UserAuthority row);

    /** {@code authorityIds}는 비어 있으면 안 된다. */
    int deleteAuthorities(@Param("userId") String userId, @Param("authorityIds") List<String> authorityIds);
}
