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

import kkdugi.app.admin.menu.exceptions.MenuConflictException;
import kkdugi.app.admin.menu.exceptions.MenuValidationException;
import kkdugi.app.admin.menu.models.MenuContent;
import kkdugi.app.admin.menu.models.MenuLocale;
import kkdugi.app.admin.menu.models.MenuPersistRequest;
import kkdugi.core.menu.mapper.MenuBaseMapper;
import kkdugi.core.menu.mapper.MenuLangMapper;
import kkdugi.core.menu.models.MenuBase;
import kkdugi.core.menu.models.MenuLang;
import kkdugi.core.serial.SerialConfig;
import kkdugi.core.util.SerialUtils;
import kkdugi.core.util.TreeUtils;

@Service
public class MenuAdminService {

    private static final Logger log = LoggerFactory.getLogger(MenuAdminService.class);

    public static final String ERR_MALFORMED_REQUEST = "menu.err.malformed_request";
    public static final String ERR_LOCALE_REQUIRED = "menu.err.locale_required";
    public static final String ERR_NOT_FOUND = "menu.err.not_found";
    public static final String ERR_IMMUTABLE = "menu.err.immutable";

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

    private final MenuBaseMapper menuBaseMapper;
    private final MenuLangMapper menuLangMapper;

    public MenuAdminService(MenuBaseMapper menuBaseMapper, MenuLangMapper menuLangMapper) {
        this.menuBaseMapper = menuBaseMapper;
        this.menuLangMapper = menuLangMapper;
    }

    @Transactional(readOnly = true)
    public List<MenuContent> search() {
        List<MenuBase> rows = menuBaseMapper.findAll();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> ids = rows.stream().map(MenuBase::getId).toList();
        Map<String, Map<String, MenuLocale>> grouped = groupLocale(menuLangMapper.findByMenuIds(ids));
        List<MenuContent> contents = rows.stream()
                .map(row -> toContent(row, grouped.getOrDefault(row.getId(), Map.of())))
                .toList();
        return TreeUtils.convert(contents);
    }

    @Transactional
    public void persist(MenuPersistRequest request) {
        validate(request);

        LocalDateTime now = LocalDateTime.now();

        for (MenuContent content : request.insertOrEmpty()) {
            insertOne(content, now);
        }
        for (MenuContent content : request.updateOrEmpty()) {
            updateOne(content, now);
        }
        for (MenuContent content : request.deleteOrEmpty()) {
            deleteOne(content);
        }
    }

    private void insertOne(MenuContent content, LocalDateTime now) {
        String parentId = content.getParentId();
        MenuBase parent = parentId == null ? null : menuBaseMapper.findById(parentId)
                .orElseThrow(() -> {
                    log.warn("메뉴 등록 실패 - 상위 메뉴를 찾을 수 없음: parentId={}", parentId);
                    return new MenuConflictException(ERR_NOT_FOUND);
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
        menuBaseMapper.insert(row);

        for (Map.Entry<String, MenuLocale> entry : content.getLocale().entrySet()) {
            MenuLang lang = new MenuLang(id, entry.getKey(), entry.getValue().getLabel(), entry.getValue().getRemarks());
            lang.setCreatedAt(now);
            lang.setCreatorId(SYSTEM_USER_ID);
            menuLangMapper.insert(lang);
        }
    }

    private void updateOne(MenuContent content, LocalDateTime now) {
        MenuBase existing = menuBaseMapper.findById(content.getId())
                .orElseThrow(() -> {
                    log.warn("메뉴 수정 실패 - 대상 메뉴를 찾을 수 없음: id={}", content.getId());
                    return new MenuConflictException(ERR_NOT_FOUND);
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
            throw new MenuConflictException(ERR_IMMUTABLE);
        }

        String use = content.getUse() != null ? content.getUse() : existing.getUse();
        String close = content.getClose() != null ? content.getClose() : existing.getClose();
        MenuBase row = new MenuBase(existing.getId(), existing.getParentId(), content.getIcon(), content.getProgram(),
                existing.getLevel(), existing.getPath(), content.getSort(), use, close);
        row.setUpdatedAt(now);
        row.setUpdaterId(SYSTEM_USER_ID);
        menuBaseMapper.update(row);

        if (content.getLocale() != null) {
            List<MenuLang> existingLangs = menuLangMapper.findByMenuId(existing.getId());
            Set<String> existingLangCodes = existingLangs.stream()
                    .map(MenuLang::getLangCode)
                    .collect(Collectors.toSet());
            for (Map.Entry<String, MenuLocale> entry : content.getLocale().entrySet()) {
                String lang = entry.getKey();
                MenuLocale value = entry.getValue();
                if (existingLangCodes.contains(lang)) {
                    MenuLang updated = new MenuLang(existing.getId(), lang, value.getLabel(), value.getRemarks());
                    updated.setUpdatedAt(now);
                    updated.setUpdaterId(SYSTEM_USER_ID);
                    menuLangMapper.update(updated);
                } else {
                    MenuLang inserted = new MenuLang(existing.getId(), lang, value.getLabel(), value.getRemarks());
                    inserted.setCreatedAt(now);
                    inserted.setCreatorId(SYSTEM_USER_ID);
                    menuLangMapper.insert(inserted);
                }
            }
        }
    }

    private void deleteOne(MenuContent content) {
        MenuBase existing = menuBaseMapper.findById(content.getId())
                .orElseThrow(() -> {
                    log.warn("메뉴 삭제 실패 - 대상 메뉴를 찾을 수 없음: id={}", content.getId());
                    return new MenuConflictException(ERR_NOT_FOUND);
                });
        // 메뉴가 삭제되면 하위 메뉴도 모두 삭제한다(archive/api-define-admin.md
        // 3.2절). menu_path 접두어로 자신+모든 하위를 찾는다.
        List<MenuBase> targets = menuBaseMapper.findSelfAndDescendants(existing.getPath());
        List<String> ids = targets.stream().map(MenuBase::getId).toList();
        menuLangMapper.deleteByMenuIds(ids);
        menuBaseMapper.deleteByIds(ids);
    }

    private void validate(MenuPersistRequest request) {
        for (MenuContent content : request.insertOrEmpty()) {
            if (!isBlank(content.getId())) {
                log.warn("메뉴 등록 검증 실패 - insert 항목에 id가 지정됨");
                throw new MenuValidationException(ERR_MALFORMED_REQUEST);
            }
            validateLocale(content);
        }
        for (MenuContent content : request.updateOrEmpty()) {
            if (isBlank(content.getId())) {
                log.warn("메뉴 수정 검증 실패 - update 항목에 id가 없음");
                throw new MenuValidationException(ERR_MALFORMED_REQUEST);
            }
            if (content.getLocale() != null) {
                validateLocale(content);
            }
        }
        for (MenuContent content : request.deleteOrEmpty()) {
            if (isBlank(content.getId())) {
                log.warn("메뉴 삭제 검증 실패 - delete 항목에 id가 없음");
                throw new MenuValidationException(ERR_MALFORMED_REQUEST);
            }
        }
    }

    private void validateLocale(MenuContent content) {
        if (content.getLocale() == null || content.getLocale().isEmpty()) {
            log.warn("메뉴 저장 검증 실패 - locale이 비어 있음: id={}", content.getId());
            throw new MenuValidationException(ERR_LOCALE_REQUIRED);
        }
        for (Map.Entry<String, MenuLocale> entry : content.getLocale().entrySet()) {
            if (entry.getValue() == null || isBlank(entry.getValue().getLabel())) {
                log.warn("메뉴 저장 검증 실패 - locale.{}.label이 비어 있음: id={}", entry.getKey(), content.getId());
                throw new MenuValidationException(ERR_LOCALE_REQUIRED);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, Map<String, MenuLocale>> groupLocale(List<MenuLang> rows) {
        Map<String, Map<String, MenuLocale>> grouped = new LinkedHashMap<>();
        for (MenuLang row : rows) {
            grouped.computeIfAbsent(row.getMenuId(), k -> new LinkedHashMap<>())
                    .put(row.getLangCode(), new MenuLocale(row.getLabel(), row.getRemarks()));
        }
        return grouped;
    }

    private static MenuContent toContent(MenuBase row, Map<String, MenuLocale> locale) {
        return new MenuContent(row.getId(), row.getParentId(), locale, row.getIcon(), row.getProgram(),
                row.getUse(), row.getClose(), row.getPath(), row.getLevel(), row.getSort());
    }
}
