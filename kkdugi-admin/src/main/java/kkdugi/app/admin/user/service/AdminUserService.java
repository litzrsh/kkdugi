package kkdugi.app.admin.user.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.admin.user.exceptions.AdminUserConflictException;
import kkdugi.app.admin.user.exceptions.AdminUserNotFoundException;
import kkdugi.app.admin.user.exceptions.AdminUserValidationException;
import kkdugi.app.admin.user.mapper.AdminUserMapper;
import kkdugi.app.admin.user.models.AdminUser;
import kkdugi.app.admin.user.models.AdminUserAuthoritiesRequest;
import kkdugi.app.admin.user.models.AdminUserAuthority;
import kkdugi.app.admin.user.models.AdminUserChangeStatusRequest;
import kkdugi.app.admin.user.models.AdminUserIds;
import kkdugi.app.admin.user.models.AdminUserParams;
import kkdugi.app.admin.user.models.AdminUserPersistRequest;
import kkdugi.app.admin.user.models.UserAuthority;
import kkdugi.app.admin.user.models.UserBase;
import kkdugi.core.enums.PasswordStatus;
import kkdugi.core.enums.UserStatus;
import kkdugi.core.models.Page;
import kkdugi.core.serial.SerialConfig;
import kkdugi.core.util.SerialUtils;
import kkdugi.core.util.SessionUtils;

@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    public static final String ERR_MALFORMED_REQUEST = "user.err.malformed_request";
    public static final String ERR_NOT_FOUND = "user.err.not_found";

    public static final String ERR_DUPLICATE_USERNAME = "user.err.duplicate_username";
    public static final String ERR_DUPLICATE_EMAIL = "user.err.duplicate_email";
    public static final String ERR_IMMUTABLE = "user.err.immutable";
    public static final String ERR_SELF_DELETE = "user.err.self_delete";
    public static final String ERR_AUTHORITY_NOT_FOUND = "user.err.authority_not_found";

    private static final String SYSTEM_USER_ID = "SYSTEM";
    private static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);
    private static final UserStatus DEFAULT_STATUS = UserStatus.NORM;
    private static final int MAX_USERNAME_LENGTH = 100;
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_EMAIL_LENGTH = 200;
    private static final int MAX_REMARKS_LENGTH = 1000;
    private static final int MAX_IMAGE_LENGTH = 500;

    /** ID 형식 {@code U{yyyyMMddHHmm}{0000}} — V8 시드가 쓰는 KKDUGI_USER 카운터와 같다. */
    private static final SerialConfig SERIAL_CONFIG = new SerialConfig() {

        @Override
        public String getId() {
            return "KKDUGI_USER";
        }

        @Override
        public String getValueFormatter() {
            return "U%s%04d";
        }
    };

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;

    public AdminUserService(AdminUserMapper adminUserMapper, PasswordEncoder passwordEncoder,
            TemporaryPasswordGenerator temporaryPasswordGenerator) {
        this.adminUserMapper = adminUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.temporaryPasswordGenerator = temporaryPasswordGenerator;
    }

    // ---- 조회 ----------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<AdminUser> search(AdminUserParams params) {
        params.setPage(params.resolvedPage());
        params.setPageSize(params.resolvedPageSize());
        UserStatus status = isBlank(params.getStatus()) ? null : parseStatus(params.getStatus());

        List<UserBase> rows = adminUserMapper.search(trimToNull(params.getUsername()), trimToNull(params.getName()),
                status, params.getOffset(), params.getLimit());
        List<AdminUser> contents = rows.stream().map(row -> {
            AdminUser content = toContent(row);
            content.setTotalSize(row.getTotalSize());
            return content;
        }).toList();

        return Page.of(contents, params);
    }

    @Transactional(readOnly = true)
    public AdminUser get(String id) {
        return toContent(findExisting(id));
    }

    // ---- 쓰기 ----------------------------------------------------------

    @Transactional
    public AdminUser regist(AdminUserPersistRequest request) {
        validate(request, true);
        UserStatus status = isBlank(request.getStatus()) ? DEFAULT_STATUS : parseStatus(request.getStatus());
        checkDuplicateUsername(request.getUsername());
        checkDuplicateEmail(request.getEmail(), null);

        LocalDateTime now = LocalDateTime.now();
        // bcrypt는 느리므로 KKDUGI_USER 카운터 행 잠금을 잡기 전에 끝내 둔다.
        String temporaryPassword = temporaryPasswordGenerator.generate();
        String encodedPassword = passwordEncoder.encode(temporaryPassword);
        String id = SerialUtils.next(SERIAL_CONFIG);

        UserBase row = new UserBase(id, request.getUsername(), request.getName(), request.getRemarks(),
                request.getImage(), request.getEmail(), status);
        row.setPassword(encodedPassword);
        row.setPasswordStatus(PasswordStatus.NEWP);
        row.setCreatedAt(now);
        row.setCreatorId(SYSTEM_USER_ID);
        try {
            adminUserMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw duplicateOf(e);
        }
        logTemporaryPassword(id, temporaryPassword);
        return get(id);
    }

    @Transactional
    public AdminUser save(String id, AdminUserPersistRequest request) {
        validate(request, false);
        UserBase existing = findExisting(id);
        if (!isBlank(request.getUsername()) && !request.getUsername().equals(existing.getUsername())) {
            log.warn("사용자 저장 실패 - username 변경 시도: id={}", id);
            throw new AdminUserConflictException(ERR_IMMUTABLE);
        }
        UserStatus status = isBlank(request.getStatus()) ? existing.getStatus() : parseStatus(request.getStatus());
        checkDuplicateEmail(request.getEmail(), id);

        UserBase row = new UserBase(id, existing.getUsername(), request.getName(), request.getRemarks(),
                request.getImage(), request.getEmail(), status);
        row.setUpdatedAt(LocalDateTime.now());
        row.setUpdaterId(SYSTEM_USER_ID);
        try {
            adminUserMapper.update(row);
        } catch (DuplicateKeyException e) {
            throw duplicateOf(e);
        }
        return get(id);
    }

    /** 대상마다 새 임시 비밀번호를 만든다. 없는 id가 하나라도 있으면 아무것도 바꾸지 않는다(404). */
    @Transactional
    public void resetPassword(AdminUserIds request) {
        List<String> ids = distinctIds(request.getId());
        requireAllExist(ids);

        LocalDateTime now = LocalDateTime.now();
        for (String id : ids) {
            String temporaryPassword = temporaryPasswordGenerator.generate();
            adminUserMapper.updatePassword(id, passwordEncoder.encode(temporaryPassword), PasswordStatus.NEWP, now,
                    SYSTEM_USER_ID);
            logTemporaryPassword(id, temporaryPassword);
        }
    }

    /** 물리 삭제. 이미 없으면 아무것도 하지 않고(200), 현재 로그인한 자기 자신은 지울 수 없다(409). */
    @Transactional
    public void delete(String id) {
        if (id.equals(SessionUtils.getUser().getId())) {
            log.warn("사용자 삭제 실패 - 자기 자신 삭제 시도: id={}", id);
            throw new AdminUserConflictException(ERR_SELF_DELETE);
        }
        if (adminUserMapper.findById(id).isEmpty()) {
            log.info("사용자 삭제 - 이미 없는 사용자라 아무것도 하지 않음: id={}", id);
            return;
        }
        adminUserMapper.deleteSessionsByUserId(id);
        adminUserMapper.deleteUserAuthsByUserId(id);
        adminUserMapper.deleteById(id);
    }

    /** 전부 성공하거나 전부 실패한다. 없는 id가 있으면 404, 빈 목록/빈 id/알 수 없는 status는 400. */
    @Transactional
    public void changeStatus(AdminUserChangeStatusRequest request) {
        List<String> ids = distinctIds(request.getId());
        if (isBlank(request.getStatus())) {
            log.warn("사용자 상태 변경 검증 실패 - status 누락");
            throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
        }
        UserStatus status = parseStatus(request.getStatus());
        requireAllExist(ids);

        adminUserMapper.updateStatus(ids, status, LocalDateTime.now(), SYSTEM_USER_ID);
    }

    // ---- 사용자별 권한 ---------------------------------------------------

    @Transactional(readOnly = true)
    public List<AdminUserAuthority> authorities(String id) {
        findExisting(id);
        return adminUserMapper.findAuthoritiesByUserId(id).stream().map(AdminUserService::toAuthorityContent).toList();
    }

    /**
     * {@code delete}를 먼저 적용하고 {@code insert}/{@code update}를 upsert한다. 요청 항목에서는 id와 적용기간만
     * 읽는다(날짜 생략 시 시작=오늘, 종료=9999-12-31). 하나라도 잘못되면 아무것도 쓰지 않는다.
     */
    @Transactional
    public List<AdminUserAuthority> saveAuthorities(String id, AdminUserAuthoritiesRequest request) {
        findExisting(id);
        List<AdminUserAuthority> inserts = orEmpty(request.getInsert());
        List<AdminUserAuthority> updates = orEmpty(request.getUpdate());
        List<AdminUserAuthority> deletes = orEmpty(request.getDelete());
        List<AdminUserAuthority> writes = Stream.concat(inserts.stream(), updates.stream()).toList();
        validateAuthorityItems(writes, deletes);

        List<String> deleteIds = deletes.stream().map(AdminUserAuthority::getId).toList();
        if (!deleteIds.isEmpty()) {
            adminUserMapper.deleteAuthorities(id, deleteIds);
        }
        LocalDateTime now = LocalDateTime.now();
        for (AdminUserAuthority item : writes) {
            UserAuthority row = new UserAuthority(id, item.getId(), resolveStart(item), resolveEnd(item));
            row.setCreatedAt(now);
            row.setCreatorId(SYSTEM_USER_ID);
            adminUserMapper.upsertAuthority(row);
        }
        return authorities(id);
    }

    // ---- 검증 ----------------------------------------------------------

    private void validate(AdminUserPersistRequest request, boolean usernameRequired) {
        boolean invalid = isBlank(request.getName()) || isBlank(request.getEmail())
                || (usernameRequired && isBlank(request.getUsername()))
                || tooLong(request.getUsername(), MAX_USERNAME_LENGTH)
                || tooLong(request.getName(), MAX_NAME_LENGTH)
                || tooLong(request.getEmail(), MAX_EMAIL_LENGTH)
                || tooLong(request.getRemarks(), MAX_REMARKS_LENGTH)
                || tooLong(request.getImage(), MAX_IMAGE_LENGTH);
        if (invalid) {
            log.warn("사용자 저장 검증 실패 - 필수값 누락/길이 초과: username={}", request.getUsername());
            throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
        }
    }

    private void checkDuplicateUsername(String username) {
        adminUserMapper.findByUsername(username).ifPresent(found -> {
            log.warn("사용자 등록 실패 - username 중복: {}", username);
            throw new AdminUserConflictException(ERR_DUPLICATE_USERNAME);
        });
    }

    private void checkDuplicateEmail(String email, String excludeId) {
        adminUserMapper.findByEmail(email).ifPresent(found -> {
            if (!found.getId().equals(excludeId)) {
                log.warn("사용자 저장 실패 - email 중복: {}", email);
                throw new AdminUserConflictException(ERR_DUPLICATE_EMAIL);
            }
        });
    }

    /**
     * 사전 조회를 통과한 동시 요청은 유니크 제약 위반으로 잡힌다 — 어느 제약인지 이름으로 구분한다. 알려진
     * 제약(email/login_id)이 아니면(예: PK 충돌) 사용자 오류로 둔갑시키지 않고 원래 예외를 그대로 돌려준다.
     */
    static RuntimeException duplicateOf(DuplicateKeyException e) {
        String message = e.getMostSpecificCause().getMessage();
        log.warn("사용자 저장 실패 - 동시 등록으로 유니크 제약 위반: {}", message);
        if (message != null && message.contains("udx_email")) {
            return new AdminUserConflictException(ERR_DUPLICATE_EMAIL);
        }
        if (message != null && message.contains("udx_login_id")) {
            return new AdminUserConflictException(ERR_DUPLICATE_USERNAME);
        }
        return e;
    }

    private void validateAuthorityItems(List<AdminUserAuthority> writes, List<AdminUserAuthority> deletes) {
        Set<String> seen = new HashSet<>();
        for (AdminUserAuthority item : Stream.concat(writes.stream(), deletes.stream()).toList()) {
            if (item == null || isBlank(item.getId()) || !seen.add(item.getId())) {
                log.warn("사용자 권한 저장 검증 실패 - 항목 오류(null/빈 id/중복 id): {}", item == null ? null : item.getId());
                throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
            }
        }
        for (AdminUserAuthority item : writes) {
            if (resolveEnd(item).isBefore(resolveStart(item))) {
                log.warn("사용자 권한 저장 검증 실패 - 적용기간 역전: {}", item.getId());
                throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
            }
        }
        List<String> writeIds = writes.stream().map(AdminUserAuthority::getId).toList();
        if (!writeIds.isEmpty() && adminUserMapper.findExistingAuthorityIds(writeIds).size() != writeIds.size()) {
            log.warn("사용자 권한 저장 검증 실패 - 존재하지 않는 권한 id가 포함됨");
            throw new AdminUserValidationException(ERR_AUTHORITY_NOT_FOUND);
        }
    }

    // ---- 헬퍼 ----------------------------------------------------------

    private UserBase findExisting(String id) {
        return adminUserMapper.findById(id).orElseThrow(() -> {
            log.warn("사용자를 찾을 수 없음: id={}", id);
            return new AdminUserNotFoundException(ERR_NOT_FOUND);
        });
    }

    /** 빈 목록/빈 id는 400, 중복은 한 번만 처리한다. */
    private static List<String> distinctIds(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(AdminUserService::isBlank)) {
            log.warn("사용자 요청 검증 실패 - id 목록이 비었거나 빈 id가 있음");
            throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
        }
        return ids.stream().distinct().toList();
    }

    private void requireAllExist(List<String> ids) {
        if (adminUserMapper.findExistingIds(ids).size() != ids.size()) {
            log.warn("사용자 요청 실패 - 존재하지 않는 사용자 id가 포함됨");
            throw new AdminUserNotFoundException(ERR_NOT_FOUND);
        }
    }

    private static UserStatus parseStatus(String code) {
        try {
            return UserStatus.fromCode(code);
        } catch (IllegalArgumentException e) {
            log.warn("사용자 요청 검증 실패 - 알 수 없는 status: {}", code);
            throw new AdminUserValidationException(ERR_MALFORMED_REQUEST);
        }
    }

    /**
     * TODO(mail): 메일 발송이 구현되면 이 로그를 제거하고 임시 비밀번호를 메일로 전달한다. 그때까지는
     * 운영자가 로그에서 임시 비밀번호를 확인한다 — 평문이 로그에 남는 임시 조치다.
     */
    private void logTemporaryPassword(String userId, String temporaryPassword) {
        log.warn("TODO(mail) 임시 비밀번호 발급 - 메일 발송 구현 시 이 로그를 제거한다: userId={}, temporaryPassword={}",
                userId, temporaryPassword);
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static List<AdminUserAuthority> orEmpty(List<AdminUserAuthority> items) {
        return items == null ? List.of() : items;
    }

    private static LocalDate resolveStart(AdminUserAuthority item) {
        return item.getApplyStartDate() != null ? item.getApplyStartDate() : LocalDate.now();
    }

    private static LocalDate resolveEnd(AdminUserAuthority item) {
        return item.getApplyEndDate() != null ? item.getApplyEndDate() : OPEN_ENDED;
    }

    private static AdminUserAuthority toAuthorityContent(UserAuthority row) {
        return new AdminUserAuthority(row.getAuthorityId(), row.getRole(), row.getType().getCode(), row.getName(),
                row.getRemarks(), row.getUse(), row.getApplyStartDate(), row.getApplyEndDate());
    }

    private static AdminUser toContent(UserBase row) {
        return new AdminUser(row.getId(), row.getUsername(), row.getName(), row.getRemarks(), row.getImage(),
                row.getEmail(), row.getStatus().getCode(), row.getLastLoginAt(), row.getLastChangePasswordAt(),
                row.getPasswordStatus() == null ? null : row.getPasswordStatus().getCode());
    }
}
