package kkdugi.app.admin.menu.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.admin.menu.exceptions.AdminMenuConflictException;
import kkdugi.app.admin.menu.exceptions.AdminMenuValidationException;
import kkdugi.app.admin.menu.mapper.AdminMenuMapper;
import kkdugi.app.admin.menu.models.AdminMenu;
import kkdugi.app.admin.menu.models.AdminMenuLocale;
import kkdugi.app.admin.menu.models.AdminMenuPersistRequest;
import kkdugi.app.admin.menu.models.MenuBase;
import kkdugi.app.admin.menu.models.MenuLang;
import kkdugi.core.serial.SerialConfig;
import kkdugi.core.util.SerialUtils;
import kkdugi.core.util.TreeUtils;

@Service
public class AdminMenuService {

    private static final Logger log = LoggerFactory.getLogger(AdminMenuService.class);

    public static final String ERR_MALFORMED_REQUEST = "menu.err.malformed_request";
    public static final String ERR_LOCALE_REQUIRED = "menu.err.locale_required";
    public static final String ERR_NOT_FOUND = "menu.err.not_found";
    public static final String ERR_IMMUTABLE = "menu.err.immutable";

    private static final Set<String> SYSTEM_PROGRAMS = Set.of("admin/code", "admin/message", "admin/menu", "admin/authority", "admin/user");
    private static final java.util.regex.Pattern PROGRAM_PATTERN = java.util.regex.Pattern.compile("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*");

    private static final String SYSTEM_USER_ID = "SYSTEM";
    private static final String DEFAULT_USE = "Y";
    private static final String DEFAULT_CLOSE = "Y";

    private static final SerialConfig SERIAL_CONFIG = new SerialConfig() {

        @Override
        public String getId() {
            return "KKDUGI_MENU";
        }

        @Override
        public String getValueFormatter() {
            return "M%s%04d";
        }
    };

    private final AdminMenuMapper adminMenuMapper;

    public AdminMenuService(AdminMenuMapper adminMenuMapper) {
        this.adminMenuMapper = adminMenuMapper;
    }

    @Transactional(readOnly = true)
    public List<AdminMenu> search() {
        List<MenuBase> rows = adminMenuMapper.findAll();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> ids = rows.stream().map(MenuBase::getId).toList();
        Map<String, Map<String, AdminMenuLocale>> grouped = groupLocale(adminMenuMapper.findLangsByMenuIds(ids));
        List<AdminMenu> contents = rows.stream()
                .map(row -> toContent(row, grouped.getOrDefault(row.getId(), Map.of())))
                .toList();
        return TreeUtils.convert(contents);
    }

    @Transactional
    public void persist(AdminMenuPersistRequest request) {
        validate(request);

        LocalDateTime now = LocalDateTime.now();

        for (AdminMenu content : request.insertOrEmpty()) {
            insertOne(content, now);
        }
        for (AdminMenu content : request.updateOrEmpty()) {
            updateOne(content, now);
        }
        for (AdminMenu content : request.deleteOrEmpty()) {
            deleteOne(content);
        }
    }

    private void insertOne(AdminMenu content, LocalDateTime now) {
        String parentId = content.getParentId();
        MenuBase parent = parentId == null ? null : adminMenuMapper.findById(parentId)
                .orElseThrow(() -> {
                    log.warn("메뉴 등록 실패 - 상위 메뉴를 찾을 수 없음: parentId={}", parentId);
                    return new AdminMenuConflictException(ERR_NOT_FOUND);
                });

        String id = SerialUtils.next(SERIAL_CONFIG);
        int level = parent == null ? 0 : parent.getLevel() + 1;
        String path = (parent == null ? "" : parent.getPath()) + "/" + id;
        String use = content.getUse() != null ? content.getUse() : DEFAULT_USE;
        String close = content.getClose() != null ? content.getClose() : DEFAULT_CLOSE;

        MenuBase row = new MenuBase(id, parentId, content.getIcon(), content.getProgram(),
                level, path, content.getSort(), use, close);
        row.setCreatedAt(now);
        row.setCreatorId(SYSTEM_USER_ID);
        adminMenuMapper.insert(row);

        for (Map.Entry<String, AdminMenuLocale> entry : content.getLocale().entrySet()) {
            MenuLang lang = new MenuLang(id, entry.getKey(), entry.getValue().getLabel(), entry.getValue().getRemarks());
            lang.setCreatedAt(now);
            lang.setCreatorId(SYSTEM_USER_ID);
            adminMenuMapper.insertLang(lang);
        }
    }

    private void updateOne(AdminMenu content, LocalDateTime now) {
        MenuBase existing = adminMenuMapper.findById(content.getId())
                .orElseThrow(() -> {
                    log.warn("메뉴 수정 실패 - 대상 메뉴를 찾을 수 없음: id={}", content.getId());
                    return new AdminMenuConflictException(ERR_NOT_FOUND);
                });
        // parentId는 이번 범위에서 수정 불가로 뒀다 — 바꾸려면 이 노드와
        // 모든 하위 노드의 menu_path/menu_lvl을 재계산해야 하는데, 공통코드와
        // 마찬가지로 그 캐스케이드 재계산은 아직 구현하지 않았다. 바꾸고
        // 싶으면 삭제 후 재등록한다(삭제는 하위까지 캐스케이드된다).
        boolean parentChanged = content.getParentId() != null
                ? !content.getParentId().equals(existing.getParentId())
                : existing.getParentId() != null;
        if (parentChanged) {
            log.warn("메뉴 수정 실패 - 상위 메뉴 변경 시도: id={}, 기존={}, 요청={}",
                    content.getId(), existing.getParentId(), content.getParentId());
            throw new AdminMenuConflictException(ERR_IMMUTABLE);
        }

        if (isProtected(existing) && (!java.util.Objects.equals(content.getProgram(), existing.getProgram())
                || content.getSort() != (existing.getSort() == null ? 0 : existing.getSort())
                || (content.getUse() != null && !content.getUse().equals(existing.getUse()))
                || (content.getClose() != null && !content.getClose().equals(existing.getClose())))) {
            throw new AdminMenuConflictException(ERR_IMMUTABLE);
        }
        String use = content.getUse() != null ? content.getUse() : existing.getUse();
        String close = content.getClose() != null ? content.getClose() : existing.getClose();
        MenuBase row = new MenuBase(existing.getId(), existing.getParentId(), content.getIcon(), content.getProgram(),
                existing.getLevel(), existing.getPath(), content.getSort(), use, close);
        row.setUpdatedAt(now);
        row.setUpdaterId(SYSTEM_USER_ID);
        adminMenuMapper.update(row);

        if (content.getLocale() != null) {
            List<MenuLang> existingLangs = adminMenuMapper.findLangsByMenuId(existing.getId());
            Set<String> existingLangCodes = existingLangs.stream()
                    .map(MenuLang::getLangCode)
                    .collect(Collectors.toSet());
            for (Map.Entry<String, AdminMenuLocale> entry : content.getLocale().entrySet()) {
                String lang = entry.getKey();
                AdminMenuLocale value = entry.getValue();
                if (existingLangCodes.contains(lang)) {
                    MenuLang updated = new MenuLang(existing.getId(), lang, value.getLabel(), value.getRemarks());
                    updated.setUpdatedAt(now);
                    updated.setUpdaterId(SYSTEM_USER_ID);
                    adminMenuMapper.updateLang(updated);
                } else {
                    MenuLang inserted = new MenuLang(existing.getId(), lang, value.getLabel(), value.getRemarks());
                    inserted.setCreatedAt(now);
                    inserted.setCreatorId(SYSTEM_USER_ID);
                    adminMenuMapper.insertLang(inserted);
                }
            }
        }
    }

    private void deleteOne(AdminMenu content) {
        MenuBase existing = adminMenuMapper.findById(content.getId())
                .orElseThrow(() -> {
                    log.warn("메뉴 삭제 실패 - 대상 메뉴를 찾을 수 없음: id={}", content.getId());
                    return new AdminMenuConflictException(ERR_NOT_FOUND);
                });
        if (isProtected(existing)) {
            throw new AdminMenuConflictException(ERR_IMMUTABLE);
        }
        // 메뉴가 삭제되면 하위 메뉴도 모두 삭제한다(archive/api-define-admin.md
        // 3.2절). menu_path 접두어로 자신+모든 하위를 찾는다.
        List<MenuBase> targets = adminMenuMapper.findSelfAndDescendants(existing.getPath());
        List<String> ids = targets.stream().map(MenuBase::getId).toList();
        // kkdugi_auth_menu가 kkdugi_menu_base를 FK로 참조하므로(ON DELETE CASCADE 없음) 권한 부여 행을
        // 먼저 지운다. 권한(kkdugi_auth_base) 자체는 남는다. 순서: auth_menu -> lang -> base.
        adminMenuMapper.deleteAuthMenusByMenuIds(ids);
        adminMenuMapper.deleteLangByMenuIds(ids);
        adminMenuMapper.deleteByIds(ids);
    }

    private boolean isProtected(MenuBase menu) {
        return adminMenuMapper.findSelfAndDescendants(menu.getPath()).stream()
                .anyMatch(row -> row.getProgram() != null && SYSTEM_PROGRAMS.contains(row.getProgram()));
    }

    private void validateFields(AdminMenu content) {
        if ((content.getProgram() != null && !content.getProgram().isEmpty() && !PROGRAM_PATTERN.matcher(content.getProgram()).matches())
                || (content.getUse() != null && !Set.of("Y", "N").contains(content.getUse()))
                || (content.getClose() != null && !Set.of("Y", "N").contains(content.getClose())) || content.getSort() < 0) {
            throw new AdminMenuValidationException(ERR_MALFORMED_REQUEST);
        }
    }

    private void validate(AdminMenuPersistRequest request) {
        for (AdminMenu content : request.insertOrEmpty()) {
            validateFields(content);
            if (!isBlank(content.getId())) {
                log.warn("메뉴 등록 검증 실패 - insert 항목에 id가 지정됨");
                throw new AdminMenuValidationException(ERR_MALFORMED_REQUEST);
            }
            validateLocale(content);
        }
        for (AdminMenu content : request.updateOrEmpty()) {
            validateFields(content);
            if (isBlank(content.getId())) {
                log.warn("메뉴 수정 검증 실패 - update 항목에 id가 없음");
                throw new AdminMenuValidationException(ERR_MALFORMED_REQUEST);
            }
            if (content.getLocale() != null) {
                validateLocale(content);
            }
        }
        for (AdminMenu content : request.deleteOrEmpty()) {
            if (isBlank(content.getId())) {
                log.warn("메뉴 삭제 검증 실패 - delete 항목에 id가 없음");
                throw new AdminMenuValidationException(ERR_MALFORMED_REQUEST);
            }
        }
    }

    private void validateLocale(AdminMenu content) {
        if (content.getLocale() == null || content.getLocale().isEmpty()) {
            log.warn("메뉴 저장 검증 실패 - locale이 비어 있음: id={}", content.getId());
            throw new AdminMenuValidationException(ERR_LOCALE_REQUIRED);
        }
        for (Map.Entry<String, AdminMenuLocale> entry : content.getLocale().entrySet()) {
            if (entry.getValue() == null || isBlank(entry.getValue().getLabel())) {
                log.warn("메뉴 저장 검증 실패 - locale.{}.label이 비어 있음: id={}", entry.getKey(), content.getId());
                throw new AdminMenuValidationException(ERR_LOCALE_REQUIRED);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, Map<String, AdminMenuLocale>> groupLocale(List<MenuLang> rows) {
        Map<String, Map<String, AdminMenuLocale>> grouped = new LinkedHashMap<>();
        for (MenuLang row : rows) {
            grouped.computeIfAbsent(row.getMenuId(), k -> new LinkedHashMap<>())
                    .put(row.getLangCode(), new AdminMenuLocale(row.getLabel(), row.getRemarks()));
        }
        return grouped;
    }

    private static AdminMenu toContent(MenuBase row, Map<String, AdminMenuLocale> locale) {
        return new AdminMenu(row.getId(), row.getParentId(), locale, row.getIcon(), row.getProgram(),
                row.getUse(), row.getClose(), row.getPath(), row.getLevel(), row.getSort());
    }
}
