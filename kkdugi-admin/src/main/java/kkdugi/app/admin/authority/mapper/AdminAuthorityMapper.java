package kkdugi.app.admin.authority.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kkdugi.app.admin.authority.models.AuthorityBase;
import kkdugi.app.admin.authority.models.AuthorityMenu;
import kkdugi.app.admin.authority.models.AuthorityMenuLang;
import kkdugi.app.admin.authority.models.AuthorityMenuRow;
import kkdugi.app.admin.authority.models.AuthorityUser;
import kkdugi.app.admin.authority.models.CandidateUser;
import kkdugi.core.enums.AuthorityType;
import kkdugi.core.enums.UserStatus;

@Mapper
public interface AdminAuthorityMapper {

    // ---- 권한 본체 -----------------------------------------------------

    List<AuthorityBase> search(@Param("role") String role, @Param("type") String type, @Param("name") String name,
            @Param("offset") int offset, @Param("pageSize") int pageSize);

    Optional<AuthorityBase> findById(@Param("id") String id);

    Optional<AuthorityBase> findByTypeAndRole(@Param("type") AuthorityType type, @Param("role") String role);

    int insert(AuthorityBase row);

    int update(AuthorityBase row);

    int deleteById(@Param("id") String id);

    // ---- 사용자 매핑 ---------------------------------------------------

    List<AuthorityUser> findUsersByAuthorityId(@Param("authorityId") String authorityId);

    /** {@code userIds}는 비어 있으면 안 된다(빈 목록은 {@code IN ()} SQL 구문 오류). */
    List<String> findExistingUserIds(@Param("userIds") List<String> userIds);

    int upsertUser(AuthorityUser row);

    /**
     * {@code userIds}는 비어 있으면 안 된다(빈 목록은 {@code IN ()} SQL 구문 오류). 빈 경우에는
     * {@link #deleteUsersByAuthorityId}를 쓴다.
     */
    int deleteUsersNotIn(@Param("authorityId") String authorityId, @Param("userIds") List<String> userIds);

    int deleteUsersByAuthorityId(@Param("authorityId") String authorityId);

    List<CandidateUser> searchCandidates(@Param("authorityId") String authorityId,
            @Param("status") UserStatus status, @Param("query") String query, @Param("limit") int limit);

    // ---- 메뉴 RBAC 매핑 ------------------------------------------------

    List<AuthorityMenuRow> findMenusWithGrant(@Param("authorityId") String authorityId);

    List<AuthorityMenuLang> findAllMenuLangs();

    /** {@code menuIds}는 비어 있으면 안 된다(빈 목록은 {@code IN ()} SQL 구문 오류). */
    List<String> findExistingMenuIds(@Param("menuIds") List<String> menuIds);

    int upsertMenu(AuthorityMenu row);

    /**
     * {@code menuIds}는 비어 있으면 안 된다(빈 목록은 {@code IN ()} SQL 구문 오류). 빈 경우에는
     * {@link #deleteMenusByAuthorityId}를 쓴다.
     */
    int deleteMenusNotIn(@Param("authorityId") String authorityId, @Param("menuIds") List<String> menuIds);

    int deleteMenusByAuthorityId(@Param("authorityId") String authorityId);
}
