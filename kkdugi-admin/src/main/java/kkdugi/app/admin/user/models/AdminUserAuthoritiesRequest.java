package kkdugi.app.admin.user.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import kkdugi.core.security.models.AuthorityBatch;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 사용자별 권한 저장 요청. 각 목록은 생략(null)하면 빈 목록과 같다. {@code insert}/{@code update}는
 * 둘 다 upsert(없으면 추가, 있으면 적용기간 갱신)이고 {@code delete}는 매핑을 지운다(없는 매핑은 무시).
 * {@link AuthorityBatch}라서 RBAC는 요청 내용에서 정해진다 — insert/update는 WRTE, 비어 있지 않은 delete는 DELT.
 */
@Getter
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminUserAuthoritiesRequest implements AuthorityBatch {

    private final List<AdminUserAuthority> insert;
    private final List<AdminUserAuthority> update;
    private final List<AdminUserAuthority> delete;
}
