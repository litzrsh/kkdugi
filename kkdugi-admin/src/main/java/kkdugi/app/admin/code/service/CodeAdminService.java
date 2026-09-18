package kkdugi.app.admin.code.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.app.admin.code.exceptions.CodeConflictException;
import kkdugi.app.admin.code.exceptions.CodeValidationException;
import kkdugi.app.admin.code.models.CodeContent;
import kkdugi.app.admin.code.models.CodeError;
import kkdugi.app.admin.code.models.CodeLocale;
import kkdugi.app.admin.code.models.CodePersistRequest;
import kkdugi.app.admin.code.models.CodeSearchParams;
import kkdugi.core.code.mapper.CodeBaseMapper;
import kkdugi.core.code.mapper.CodeLangMapper;
import kkdugi.core.code.models.CodeBase;
import kkdugi.core.code.models.CodeLang;
import kkdugi.core.models.Page;
import kkdugi.core.serial.SerialConfig;
import kkdugi.core.util.SerialUtils;

@Service
public class CodeAdminService {

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
        int page = params.resolvedPage();
        int pageSize = params.resolvedPageSize();

        List<CodeBase> rows = codeBaseMapper.findChildren(
                params.getParentId(), params.getCode(), params.getPath(), params.getName(), params.getUse(),
                params.getOffset(), pageSize);
        long totalItems = codeBaseMapper.countChildren(
                params.getParentId(), params.getCode(), params.getPath(), params.getName(), params.getUse());

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

        return Page.of(contents, page, pageSize, totalItems);
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
        CodeBase parent = null;
        if (parentId != null) {
            parent = codeBaseMapper.findById(parentId);
            if (parent == null) {
                throw new CodeConflictException(null, content.getCode(), "상위 코드를 찾을 수 없습니다: " + parentId);
            }
        }

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
            throw new CodeConflictException(null, content.getCode(), "같은 경로에 이미 존재하는 코드입니다: " + content.getCode());
        }

        for (Map.Entry<String, CodeLocale> entry : content.getLocale().entrySet()) {
            CodeLang lang = new CodeLang(id, entry.getKey(), entry.getValue().getName(), entry.getValue().getRemarks());
            lang.setCreatedAt(now);
            lang.setCreatorId(SYSTEM_USER_ID);
            codeLangMapper.insert(lang);
        }
    }

    private void updateOne(CodeContent content, LocalDateTime now) {
        CodeBase existing = codeBaseMapper.findById(content.getId());
        if (existing == null) {
            throw new CodeConflictException(content.getId(), content.getCode(), "대상 코드를 찾을 수 없습니다: " + content.getId());
        }
        // code 값(경로 세그먼트)과 parentId는 이번 범위에서 수정 불가로 뒀다 —
        // 바꾸려면 이 노드와 모든 하위 노드의 code_path/code_lvl을 재계산해야
        // 하는데, 그 캐스케이드 재계산은 아직 구현하지 않았다. 바꾸고 싶으면
        // 삭제 후 재등록한다(삭제는 하위까지 캐스케이드된다).
        if (content.getCode() != null && !content.getCode().equals(existing.getCode())) {
            throw new CodeConflictException(content.getId(), content.getCode(),
                    "코드 값은 수정할 수 없습니다(삭제 후 재등록하세요): " + content.getId());
        }
        boolean parentChanged = content.getParentId() != null
                ? !content.getParentId().equals(existing.getParentId())
                : existing.getParentId() != null;
        if (parentChanged) {
            throw new CodeConflictException(content.getId(), content.getCode(),
                    "상위 코드는 수정할 수 없습니다(삭제 후 재등록하세요): " + content.getId());
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
        CodeBase existing = codeBaseMapper.findById(content.getId());
        if (existing == null) {
            throw new CodeConflictException(content.getId(), content.getCode(), "대상 코드를 찾을 수 없습니다: " + content.getId());
        }
        // 하위 코드도 모두 삭제 (api-define-admin.md 1.2절 "삭제 시, 하위
        // 코드도 모두 삭제"). code_path 접두어로 자신+모든 하위를 찾는다.
        List<CodeBase> targets = codeBaseMapper.findSelfAndDescendants(existing.getPath());
        List<String> ids = targets.stream().map(CodeBase::getId).toList();
        codeLangMapper.deleteByCodeIds(ids);
        codeBaseMapper.deleteByIds(ids);
    }

    private void validate(CodePersistRequest request) {
        List<CodeError> errors = new ArrayList<>();
        for (CodeContent content : request.insertOrEmpty()) {
            if (!isBlank(content.getId())) {
                errors.add(new CodeError(content.getId(), content.getCode(),
                        "insert 항목의 id는 비어 있어야 합니다(서버가 채번합니다)"));
            }
            if (isBlank(content.getCode())) {
                errors.add(new CodeError(content.getId(), content.getCode(), "code는 필수입니다"));
            }
            validateLocale(content, errors);
        }
        for (CodeContent content : request.updateOrEmpty()) {
            if (isBlank(content.getId())) {
                errors.add(new CodeError(content.getId(), content.getCode(), "update 항목의 id는 필수입니다"));
            }
            if (content.getLocale() != null) {
                validateLocale(content, errors);
            }
        }
        for (CodeContent content : request.deleteOrEmpty()) {
            if (isBlank(content.getId())) {
                errors.add(new CodeError(content.getId(), content.getCode(), "delete 항목의 id는 필수입니다"));
            }
        }
        if (!errors.isEmpty()) {
            throw new CodeValidationException(errors);
        }
    }

    private void validateLocale(CodeContent content, List<CodeError> errors) {
        if (content.getLocale() == null || content.getLocale().isEmpty()) {
            errors.add(new CodeError(content.getId(), content.getCode(), "locale은 최소 1개 이상이어야 합니다"));
            return;
        }
        for (Map.Entry<String, CodeLocale> entry : content.getLocale().entrySet()) {
            if (entry.getValue() == null || isBlank(entry.getValue().getName())) {
                errors.add(new CodeError(content.getId(), content.getCode(),
                        "locale." + entry.getKey() + ".name은 필수입니다"));
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
        return new CodeContent(row.getId(), row.getParentId(), row.getCode(), locale, row.getUse(),
                row.getExtra1(), row.getExtra2(), row.getExtra3(), row.getExtra4(), row.getExtra5(),
                row.getPath(), row.getLevel(), row.getSort());
    }
}
