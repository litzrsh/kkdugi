package kkdugi.app.admin.authority.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.admin.authority.exceptions.AdminAuthorityConflictException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityNotFoundException;
import kkdugi.app.admin.authority.exceptions.AdminAuthorityValidationException;
import kkdugi.app.admin.authority.mapper.AdminAuthorityMapper;
import kkdugi.app.admin.authority.models.AdminAuthority;
import kkdugi.app.admin.authority.models.AdminAuthorityCandidate;
import kkdugi.app.admin.authority.models.AdminAuthorityMenu;
import kkdugi.app.admin.authority.models.AdminAuthorityMenuLocale;
import kkdugi.app.admin.authority.models.AdminAuthorityMenuNode;
import kkdugi.app.admin.authority.models.AdminAuthorityParams;
import kkdugi.app.admin.authority.models.AdminAuthorityPersistRequest;
import kkdugi.app.admin.authority.models.AdminAuthorityUser;
import kkdugi.app.admin.authority.models.AuthorityBase;
import kkdugi.app.admin.authority.models.AuthorityMenu;
import kkdugi.app.admin.authority.models.AuthorityMenuLang;
import kkdugi.app.admin.authority.models.AuthorityMenuRow;
import kkdugi.app.admin.authority.models.AuthorityUser;
import kkdugi.core.Constants;
import kkdugi.core.enums.AuthorityType;
import kkdugi.core.enums.Rbac;
import kkdugi.core.enums.UserStatus;
import kkdugi.core.models.Page;
import kkdugi.core.serial.SerialConfig;
import kkdugi.core.util.SerialUtils;
import kkdugi.core.util.TreeUtils;

@Service
public class AdminAuthorityService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthorityService.class);

    public static final String ERR_MALFORMED_REQUEST = "authority.err.malformed_request";
    public static final String ERR_USER_NOT_FOUND = "authority.err.user_not_found";
    public static final String ERR_MENU_NOT_FOUND = "authority.err.menu_not_found";
    public static final String ERR_NOT_FOUND = "authority.err.not_found";
    public static final String ERR_DUPLICATE = "authority.err.duplicate";
    public static final String ERR_IMMUTABLE = "authority.err.immutable";

    private static final String SYSTEM_USER_ID = "SYSTEM";
    private static final String DEFAULT_USE = "Y";
    private static final Set<String> USE_VALUES = Set.of("Y", "N");
    private static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);
    private static final int MAX_ROLE_LENGTH = 60;
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_REMARKS_LENGTH = 1000;
    private static final int CANDIDATE_LIMIT = 200;

    private static final SerialConfig SERIAL_CONFIG = new SerialConfig() {

        @Override
        public String getId() {
            return "KKDUGI_AUTH";
        }

        @Override
        public String getValueFormatter() {
            return "A%s%04d";
        }
    };

    private final AdminAuthorityMapper adminAuthorityMapper;

    public AdminAuthorityService(AdminAuthorityMapper adminAuthorityMapper) {
        this.adminAuthorityMapper = adminAuthorityMapper;
    }

    // ---- 조회 ----------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<AdminAuthority> search(AdminAuthorityParams params) {
        params.setPage(params.resolvedPage());
        params.setPageSize(params.resolvedPageSize());

        List<AuthorityBase> rows = adminAuthorityMapper.search(
                params.getRole(), params.getType(), params.getName(), params.getOffset(), params.getLimit());
        List<AdminAuthority> contents = rows.stream().map(row -> {
            AdminAuthority content = toContent(row, null);
            content.setTotalSize(row.getTotalSize());
            return content;
        }).toList();

        return Page.of(contents, params);
    }

    @Transactional(readOnly = true)
    public AdminAuthority get(String id) {
        AuthorityBase row = findExisting(id);
        List<AdminAuthorityUser> users = adminAuthorityMapper.findUsersByAuthorityId(id).stream()
                .map(user -> new AdminAuthorityUser(user.getUserId(), user.getApplyStartDate(), user.getApplyEndDate()))
                .toList();
        return toContent(row, users);
    }

    @Transactional(readOnly = true)
    public List<AdminAuthorityCandidate> searchCandidates(String id, String query) {
        findExisting(id);
        String trimmed = query == null || query.isBlank() ? null : query.trim();
        return adminAuthorityMapper.searchCandidates(id, UserStatus.NORM, trimmed, CANDIDATE_LIMIT).stream()
                .map(user -> new AdminAuthorityCandidate(user.getId(), user.getUsername(), user.getName(), user.getImage()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminAuthorityMenuNode> menus(String id) {
        findExisting(id);
        List<AuthorityMenuRow> rows = adminAuthorityMapper.findMenusWithGrant(id);
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, Map<String, AdminAuthorityMenuLocale>> locales = new LinkedHashMap<>();
        for (AuthorityMenuLang lang : adminAuthorityMapper.findAllMenuLangs()) {
            locales.computeIfAbsent(lang.getMenuId(), key -> new LinkedHashMap<>())
                    .put(lang.getLangCode(), new AdminAuthorityMenuLocale(lang.getLabel(), lang.getRemarks()));
        }

        List<AdminAuthorityMenuNode> nodes = rows.stream()
                .map(row -> new AdminAuthorityMenuNode(row.getId(), row.getParentId(),
                        locales.getOrDefault(row.getId(), Map.of()), row.getIcon(), row.getProgram(), row.getUse(),
                        row.getPath(), row.getLevel(), row.getSort(), Rbac.toMap(row.getRbac())))
                .toList();
        return TreeUtils.convert(nodes);
    }

    // ---- 쓰기 ----------------------------------------------------------

    @Transactional
    public AdminAuthority regist(AdminAuthorityPersistRequest request) {
        validate(request);
        AuthorityType type = AuthorityType.fromCode(request.getType());
        rejectReservedSysAdminOfOtherType(request, type);
        checkDuplicate(type, request.getRole(), null);

        LocalDateTime now = LocalDateTime.now();
        String id = SerialUtils.next(SERIAL_CONFIG);
        String use = request.getUse() != null ? request.getUse() : DEFAULT_USE;

        AuthorityBase row = new AuthorityBase(id, request.getRole(), type, request.getName(), request.getRemarks(), use);
        row.setCreatedAt(now);
        row.setCreatorId(SYSTEM_USER_ID);
        try {
            adminAuthorityMapper.insert(row);
        } catch (DuplicateKeyException e) {
            log.warn("권한 등록 실패 - 같은 유형/role이 동시에 등록됨: type={}, role={}", type, request.getRole());
            throw new AdminAuthorityConflictException(ERR_DUPLICATE);
        }

        replaceUsers(id, request.getUsers(), now);
        replaceMenus(id, request.getMenus(), now);
        return get(id);
    }

    @Transactional
    public AdminAuthority save(String id, AdminAuthorityPersistRequest request) {
        validate(request);
        AuthorityBase existing = findExisting(id);
        AuthorityType type = AuthorityType.fromCode(request.getType());
        rejectReservedSysAdminOfOtherType(request, type);
        String use = request.getUse() != null ? request.getUse() : existing.getUse();
        boolean identityChanged = !existing.getRole().equals(request.getRole()) || existing.getType() != type;

        // SYS_ADMIN은 KkdugiUserDetailsService가 role 코드로 메뉴 우회를 판단한다 —
        // 바꾸거나 끄면 모든 사용자가 잠길 수 있어 이름/설명만 수정할 수 있다.
        if (isSysAdmin(existing) && (identityChanged || !DEFAULT_USE.equals(use))) {
            log.warn("권한 저장 실패 - SYS_ADMIN 변경 시도: id={}", id);
            throw new AdminAuthorityConflictException(ERR_IMMUTABLE);
        }
        if (identityChanged) {
            checkDuplicate(type, request.getRole(), id);
        }

        LocalDateTime now = LocalDateTime.now();
        AuthorityBase row = new AuthorityBase(id, request.getRole(), type, request.getName(), request.getRemarks(), use);
        row.setUpdatedAt(now);
        row.setUpdaterId(SYSTEM_USER_ID);
        try {
            adminAuthorityMapper.update(row);
        } catch (DuplicateKeyException e) {
            log.warn("권한 저장 실패 - 같은 유형/role이 동시에 등록됨: type={}, role={}", type, request.getRole());
            throw new AdminAuthorityConflictException(ERR_DUPLICATE);
        }

        replaceUsers(id, request.getUsers(), now);
        replaceMenus(id, request.getMenus(), now);
        return get(id);
    }

    @Transactional
    public void delete(String id) {
        AuthorityBase existing = adminAuthorityMapper.findById(id).orElse(null);
        if (existing == null) {
            log.info("권한 삭제 - 이미 없는 권한이라 아무것도 하지 않음: id={}", id);
            return;
        }
        if (isSysAdmin(existing)) {
            log.warn("권한 삭제 실패 - SYS_ADMIN 삭제 시도: id={}", id);
            throw new AdminAuthorityConflictException(ERR_IMMUTABLE);
        }
        adminAuthorityMapper.deleteUsersByAuthorityId(id);
        adminAuthorityMapper.deleteMenusByAuthorityId(id);
        adminAuthorityMapper.deleteById(id);
    }

    // ---- 매핑 교체 -----------------------------------------------------

    /** {@code users}가 null이면 건드리지 않고, 빈 목록이면 비운다. 목록에 없는 기존 행은 삭제, 나머지는 upsert. */
    private void replaceUsers(String id, List<AdminAuthorityUser> users, LocalDateTime now) {
        if (users == null) {
            return;
        }
        List<String> userIds = users.stream().map(AdminAuthorityUser::getId).toList();
        if (userIds.isEmpty()) {
            adminAuthorityMapper.deleteUsersByAuthorityId(id);
            return;
        }
        adminAuthorityMapper.deleteUsersNotIn(id, userIds);
        for (AdminAuthorityUser user : users) {
            AuthorityUser row = new AuthorityUser(user.getId(), id, resolveStart(user), resolveEnd(user));
            row.setCreatedAt(now);
            row.setCreatorId(SYSTEM_USER_ID);
            adminAuthorityMapper.upsertUser(row);
        }
    }

    /** {@code menus}가 null이면 건드리지 않는다. RBAC 값이 0인 항목은 행을 저장하지 않는다(행이 없는 것이 접근권 없음). */
    private void replaceMenus(String id, List<AdminAuthorityMenu> menus, LocalDateTime now) {
        if (menus == null) {
            return;
        }
        Map<String, Integer> grants = new LinkedHashMap<>();
        for (AdminAuthorityMenu menu : menus) {
            int rbac = Rbac.fromMap(menu.getAuthorities());
            if (rbac != 0) {
                grants.put(menu.getId(), rbac);
            }
        }
        if (grants.isEmpty()) {
            adminAuthorityMapper.deleteMenusByAuthorityId(id);
            return;
        }
        adminAuthorityMapper.deleteMenusNotIn(id, List.copyOf(grants.keySet()));
        for (Map.Entry<String, Integer> grant : grants.entrySet()) {
            AuthorityMenu row = new AuthorityMenu(id, grant.getKey(), grant.getValue());
            row.setCreatedAt(now);
            row.setCreatorId(SYSTEM_USER_ID);
            adminAuthorityMapper.upsertMenu(row);
        }
    }

    // ---- 검증 ----------------------------------------------------------

    private void validate(AdminAuthorityPersistRequest request) {
        if (isBlank(request.getRole()) || isBlank(request.getName()) || isBlank(request.getType())
                || request.getRole().length() > MAX_ROLE_LENGTH
                || request.getName().length() > MAX_NAME_LENGTH
                || (request.getRemarks() != null && request.getRemarks().length() > MAX_REMARKS_LENGTH)
                || (request.getUse() != null && !USE_VALUES.contains(request.getUse()))) {
            log.warn("권한 저장 검증 실패 - 필수값 누락/길이/use 값 오류: role={}", request.getRole());
            throw new AdminAuthorityValidationException(ERR_MALFORMED_REQUEST);
        }
        try {
            AuthorityType.fromCode(request.getType());
        } catch (IllegalArgumentException e) {
            log.warn("권한 저장 검증 실패 - 알 수 없는 type: {}", request.getType());
            throw new AdminAuthorityValidationException(ERR_MALFORMED_REQUEST);
        }
        validateUsers(request.getUsers());
        validateMenus(request.getMenus());
    }

    private void validateUsers(List<AdminAuthorityUser> users) {
        if (users == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (AdminAuthorityUser user : users) {
            if (user == null || isBlank(user.getId()) || !seen.add(user.getId())
                    || resolveEnd(user).isBefore(resolveStart(user))) {
                log.warn("권한 저장 검증 실패 - users 항목 오류(빈 id/중복/적용기간 역전): {}",
                        user == null ? null : user.getId());
                throw new AdminAuthorityValidationException(ERR_MALFORMED_REQUEST);
            }
        }
        if (!seen.isEmpty() && adminAuthorityMapper.findExistingUserIds(List.copyOf(seen)).size() != seen.size()) {
            log.warn("권한 저장 검증 실패 - 존재하지 않는 사용자 id가 포함됨");
            throw new AdminAuthorityValidationException(ERR_USER_NOT_FOUND);
        }
    }

    private void validateMenus(List<AdminAuthorityMenu> menus) {
        if (menus == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (AdminAuthorityMenu menu : menus) {
            if (menu == null || isBlank(menu.getId()) || menu.getAuthorities() == null || !seen.add(menu.getId())) {
                log.warn("권한 저장 검증 실패 - menus 항목 오류(빈 id/중복/authorities 없음): {}",
                        menu == null ? null : menu.getId());
                throw new AdminAuthorityValidationException(ERR_MALFORMED_REQUEST);
            }
            for (Map.Entry<String, Boolean> entry : menu.getAuthorities().entrySet()) {
                // Rbac.fromMap은 알 수 없는 키에서 IllegalArgumentException을 던져 500이 되므로 먼저 걸러 400으로 바꾼다.
                if (entry.getValue() == null || !isRbacCode(entry.getKey())) {
                    log.warn("권한 저장 검증 실패 - 알 수 없는 RBAC 키: menuId={}, key={}", menu.getId(), entry.getKey());
                    throw new AdminAuthorityValidationException(ERR_MALFORMED_REQUEST);
                }
            }
        }
        if (!seen.isEmpty() && adminAuthorityMapper.findExistingMenuIds(List.copyOf(seen)).size() != seen.size()) {
            log.warn("권한 저장 검증 실패 - 존재하지 않는 메뉴 id가 포함됨");
            throw new AdminAuthorityValidationException(ERR_MENU_NOT_FOUND);
        }
    }

    private void checkDuplicate(AuthorityType type, String role, String excludeId) {
        adminAuthorityMapper.findByTypeAndRole(type, role).ifPresent(found -> {
            if (!found.getId().equals(excludeId)) {
                log.warn("권한 저장 실패 - 같은 유형/role이 이미 있음: type={}, role={}", type, role);
                throw new AdminAuthorityConflictException(ERR_DUPLICATE);
            }
        });
    }

    /**
     * SYS_ADMIN은 유형과 무관하게 예약된 role 코드다 — 메뉴 우회 판단(KkdugiUserDetailsService,
     * SessionUtils)이 role 문자열만 보기 때문에, ROLE 외 유형으로 만들거나 바꾸면 우회가 생긴다.
     * (ROLE/SYS_ADMIN으로 만들거나 바꾸려는 시도는 checkDuplicate가 ERR_DUPLICATE로 막는다.)
     */
    private void rejectReservedSysAdminOfOtherType(AdminAuthorityPersistRequest request, AuthorityType type) {
        if (Constants.SYS_ADMIN.equals(request.getRole()) && type != AuthorityType.ROLE) {
            log.warn("권한 저장 실패 - 예약된 SYS_ADMIN role을 다른 유형으로 사용하려 함: type={}", type);
            throw new AdminAuthorityConflictException(ERR_IMMUTABLE);
        }
    }

    // ---- 헬퍼 ----------------------------------------------------------

    private AuthorityBase findExisting(String id) {
        return adminAuthorityMapper.findById(id).orElseThrow(() -> {
            log.warn("권한을 찾을 수 없음: id={}", id);
            return new AdminAuthorityNotFoundException(ERR_NOT_FOUND);
        });
    }

    private static boolean isSysAdmin(AuthorityBase authority) {
        return Constants.SYS_ADMIN.equals(authority.getRole());
    }

    private static boolean isRbacCode(String key) {
        return Arrays.stream(Rbac.values()).anyMatch(rbac -> rbac.getCode().equals(key));
    }

    private static LocalDate resolveStart(AdminAuthorityUser user) {
        return user.getApplyStartDate() != null ? user.getApplyStartDate() : LocalDate.now();
    }

    private static LocalDate resolveEnd(AdminAuthorityUser user) {
        return user.getApplyEndDate() != null ? user.getApplyEndDate() : OPEN_ENDED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static AdminAuthority toContent(AuthorityBase row, List<AdminAuthorityUser> users) {
        return new AdminAuthority(row.getId(), row.getRole(), row.getType().getCode(), row.getName(),
                row.getRemarks(), row.getUse(), users);
    }
}
