package kkdugi.app.admin.code.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.admin.code.exceptions.CodeConflictException;
import kkdugi.app.admin.code.exceptions.CodeValidationException;
import kkdugi.app.admin.code.models.CodeContent;
import kkdugi.app.admin.code.models.CodeLocale;
import kkdugi.app.admin.code.models.CodePersistRequest;
import kkdugi.app.admin.code.models.CodeSearchParams;
import kkdugi.core.code.mapper.CodeBaseMapper;
import kkdugi.core.code.mapper.CodeLangMapper;
import kkdugi.core.code.models.CodeBase;
import kkdugi.core.code.models.CodeLang;
import kkdugi.core.code.models.CodeValue;
import kkdugi.core.models.Page;
import kkdugi.core.serial.SerialConfig;
import kkdugi.core.util.SerialUtils;

@Service
public class CodeAdminService {

    private static final Logger log = LoggerFactory.getLogger(CodeAdminService.class);

    public static final String ERR_MALFORMED_REQUEST = "code.err.malformed_request";
    public static final String ERR_INVALID_FORMAT = "code.err.invalid_format";
    public static final String ERR_LOCALE_REQUIRED = "code.err.locale_required";
    public static final String ERR_DUPLICATE = "code.err.duplicate";
    public static final String ERR_NOT_FOUND = "code.err.not_found";
    public static final String ERR_IMMUTABLE = "code.err.immutable";

    private static final String SYSTEM_USER_ID = "SYSTEM";
    private static final String ID_PREFIX = "C";
    private static final String DEFAULT_USE = "Y";

    private static final SerialConfig SERIAL_CONFIG = new SerialConfig() {

        @Override
        public String getId() {
            return "KKDUGI_CODE";
        }

        @Override
        public String getValueFormatter() {
            return "C%s%04d";
        }
    };

    private final CodeBaseMapper codeBaseMapper;
    private final CodeLangMapper codeLangMapper;

    public CodeAdminService(CodeBaseMapper codeBaseMapper, CodeLangMapper codeLangMapper) {
        this.codeBaseMapper = codeBaseMapper;
        this.codeLangMapper = codeLangMapper;
    }

    @Transactional(readOnly = true)
    public Page<CodeContent> search(CodeSearchParams params) {
        params.setPage(params.resolvedPage());
        params.setPageSize(params.resolvedPageSize());

        List<CodeBase> rows = codeBaseMapper.findChildren(
                params.getParentId(), params.getCode(), params.getPath(), params.getName(), params.getUse(),
                params.getOffset(), params.getLimit());

        List<CodeContent> contents;
        if (rows.isEmpty()) {
            contents = List.of();
        } else {
            List<String> ids = rows.stream().map(CodeBase::getId).toList();
            Map<String, Map<String, CodeLocale>> grouped = groupLocale(codeLangMapper.findByCodeIds(ids));
            contents = rows.stream()
                    .map(row -> toContent(row, grouped.getOrDefault(row.getId(), Map.of())))
                    .toList();
        }

        return Page.of(contents, params);
    }

    @Transactional
    public void persist(CodePersistRequest request) {
        validate(request);

        LocalDateTime now = LocalDateTime.now();

        for (CodeContent content : request.insertOrEmpty()) {
            insertOne(content, now);
        }
        for (CodeContent content : request.updateOrEmpty()) {
            updateOne(content, now);
        }
        for (CodeContent content : request.deleteOrEmpty()) {
            deleteOne(content);
        }
    }

    private void insertOne(CodeContent content, LocalDateTime now) {
        String parentId = content.getParentId();
        CodeBase parent = parentId == null ? null : codeBaseMapper.findById(parentId)
                .orElseThrow(() -> {
                    log.warn("공통코드 등록 실패 - 상위 코드를 찾을 수 없음: parentId={}", parentId);
                    return new CodeConflictException(ERR_NOT_FOUND);
                });

        String id = SerialUtils.next(SERIAL_CONFIG);
        int level = parent == null ? 0 : parent.getLevel() + 1;
        String path = (parent == null ? "" : parent.getPath()) + "/" + content.getCode();
        String use = content.getUse() != null ? content.getUse() : DEFAULT_USE;

        CodeBase row = new CodeBase(id, parentId, content.getCode(),
                content.getExtra1(), content.getExtra2(), content.getExtra3(), content.getExtra4(), content.getExtra5(),
                level, path, content.getSort(), use);
        row.setCreatedAt(now);
        row.setCreatorId(SYSTEM_USER_ID);
        try {
            codeBaseMapper.insert(row);
        } catch (DuplicateKeyException e) {
            log.warn("공통코드 등록 실패 - 같은 경로에 이미 존재하는 코드: parentId={}, code={}", parentId, content.getCode());
            throw new CodeConflictException(ERR_DUPLICATE);
        }

        for (Map.Entry<String, CodeLocale> entry : content.getLocale().entrySet()) {
            CodeLang lang = new CodeLang(id, entry.getKey(), entry.getValue().getName(), entry.getValue().getRemarks());
            lang.setCreatedAt(now);
            lang.setCreatorId(SYSTEM_USER_ID);
            codeLangMapper.insert(lang);
        }
    }

    private void updateOne(CodeContent content, LocalDateTime now) {
        CodeBase existing = codeBaseMapper.findById(content.getId())
                .orElseThrow(() -> {
                    log.warn("공통코드 수정 실패 - 대상 코드를 찾을 수 없음: id={}", content.getId());
                    return new CodeConflictException(ERR_NOT_FOUND);
                });
        // code 값(경로 세그먼트)과 parentId는 이번 범위에서 수정 불가로 뒀다 —
        // 바꾸려면 이 노드와 모든 하위 노드의 code_path/code_lvl을 재계산해야
        // 하는데, 그 캐스케이드 재계산은 아직 구현하지 않았다. 바꾸고 싶으면
        // 삭제 후 재등록한다(삭제는 하위까지 캐스케이드된다).
        if (content.getCode() != null && !content.getCode().equals(existing.getCode())) {
            log.warn("공통코드 수정 실패 - code 값 변경 시도: id={}, 기존={}, 요청={}",
                    content.getId(), existing.getCode(), content.getCode());
            throw new CodeConflictException(ERR_IMMUTABLE);
        }
        boolean parentChanged = content.getParentId() != null
                ? !content.getParentId().equals(existing.getParentId())
                : existing.getParentId() != null;
        if (parentChanged) {
            log.warn("공통코드 수정 실패 - 상위 코드 변경 시도: id={}, 기존={}, 요청={}",
                    content.getId(), existing.getParentId(), content.getParentId());
            throw new CodeConflictException(ERR_IMMUTABLE);
        }

        String use = content.getUse() != null ? content.getUse() : existing.getUse();
        CodeBase row = new CodeBase(existing.getId(), existing.getParentId(), existing.getCode(),
                content.getExtra1(), content.getExtra2(), content.getExtra3(), content.getExtra4(), content.getExtra5(),
                existing.getLevel(), existing.getPath(), content.getSort(), use);
        row.setUpdatedAt(now);
        row.setUpdaterId(SYSTEM_USER_ID);
        codeBaseMapper.update(row);

        if (content.getLocale() != null) {
            List<CodeLang> existingLangs = codeLangMapper.findByCodeId(existing.getId());
            Set<String> existingLangCodes = existingLangs.stream()
                    .map(CodeLang::getLangCode)
                    .collect(Collectors.toSet());
            for (Map.Entry<String, CodeLocale> entry : content.getLocale().entrySet()) {
                String lang = entry.getKey();
                CodeLocale value = entry.getValue();
                if (existingLangCodes.contains(lang)) {
                    CodeLang updated = new CodeLang(existing.getId(), lang, value.getName(), value.getRemarks());
                    updated.setUpdatedAt(now);
                    updated.setUpdaterId(SYSTEM_USER_ID);
                    codeLangMapper.update(updated);
                } else {
                    CodeLang inserted = new CodeLang(existing.getId(), lang, value.getName(), value.getRemarks());
                    inserted.setCreatedAt(now);
                    inserted.setCreatorId(SYSTEM_USER_ID);
                    codeLangMapper.insert(inserted);
                }
            }
        }
    }

    private void deleteOne(CodeContent content) {
        CodeBase existing = codeBaseMapper.findById(content.getId())
                .orElseThrow(() -> {
                    log.warn("공통코드 삭제 실패 - 대상 코드를 찾을 수 없음: id={}", content.getId());
                    return new CodeConflictException(ERR_NOT_FOUND);
                });
        // 하위 코드도 모두 삭제 (docs/archive/api-define-admin.md 1.2절
        // "삭제 시, 하위 코드도 모두 삭제"). code_path 접두어로 자신+모든
        // 하위를 찾는다.
        List<CodeBase> targets = codeBaseMapper.findSelfAndDescendants(existing.getPath());
        List<String> ids = targets.stream().map(CodeBase::getId).toList();
        codeLangMapper.deleteByCodeIds(ids);
        codeBaseMapper.deleteByIds(ids);
    }

    private void validate(CodePersistRequest request) {
        for (CodeContent content : request.insertOrEmpty()) {
            if (!isBlank(content.getId())) {
                log.warn("공통코드 등록 검증 실패 - insert 항목에 id가 지정됨: code={}", content.getCode());
                throw new CodeValidationException(ERR_MALFORMED_REQUEST);
            }
            if (!CodeValue.matches(content.getCode())) {
                log.warn("공통코드 등록 검증 실패 - code 값 형식이 올바르지 않음: code={}", content.getCode());
                throw new CodeValidationException(ERR_INVALID_FORMAT);
            }
            validateLocale(content);
        }
        for (CodeContent content : request.updateOrEmpty()) {
            if (isBlank(content.getId())) {
                log.warn("공통코드 수정 검증 실패 - update 항목에 id가 없음");
                throw new CodeValidationException(ERR_MALFORMED_REQUEST);
            }
            if (content.getLocale() != null) {
                validateLocale(content);
            }
        }
        for (CodeContent content : request.deleteOrEmpty()) {
            if (isBlank(content.getId())) {
                log.warn("공통코드 삭제 검증 실패 - delete 항목에 id가 없음");
                throw new CodeValidationException(ERR_MALFORMED_REQUEST);
            }
        }
    }

    private void validateLocale(CodeContent content) {
        if (content.getLocale() == null || content.getLocale().isEmpty()) {
            log.warn("공통코드 저장 검증 실패 - locale이 비어 있음: id={}, code={}", content.getId(), content.getCode());
            throw new CodeValidationException(ERR_LOCALE_REQUIRED);
        }
        for (Map.Entry<String, CodeLocale> entry : content.getLocale().entrySet()) {
            if (entry.getValue() == null || isBlank(entry.getValue().getName())) {
                log.warn("공통코드 저장 검증 실패 - locale.{}.name이 비어 있음: id={}, code={}",
                        entry.getKey(), content.getId(), content.getCode());
                throw new CodeValidationException(ERR_LOCALE_REQUIRED);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, Map<String, CodeLocale>> groupLocale(List<CodeLang> rows) {
        Map<String, Map<String, CodeLocale>> grouped = new LinkedHashMap<>();
        for (CodeLang row : rows) {
            grouped.computeIfAbsent(row.getCodeId(), k -> new LinkedHashMap<>())
                    .put(row.getLangCode(), new CodeLocale(row.getName(), row.getRemarks()));
        }
        return grouped;
    }

    private static CodeContent toContent(CodeBase row, Map<String, CodeLocale> locale) {
        CodeContent content = new CodeContent(row.getId(), row.getParentId(), row.getCode(), locale, row.getUse(),
                row.getExtra1(), row.getExtra2(), row.getExtra3(), row.getExtra4(), row.getExtra5(),
                row.getPath(), row.getLevel(), row.getSort());
        content.setTotalSize(row.getTotalSize());
        return content;
    }
}
