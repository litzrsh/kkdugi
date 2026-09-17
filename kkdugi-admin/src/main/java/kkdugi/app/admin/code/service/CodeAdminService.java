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
                params.parentId(), params.code(), params.path(), params.name(), params.use(),
                params.offset(), pageSize);
        long totalItems = codeBaseMapper.countChildren(
                params.parentId(), params.code(), params.path(), params.name(), params.use());

        List<CodeContent> contents;
        if (rows.isEmpty()) {
            contents = List.of();
        } else {
            List<String> ids = rows.stream().map(CodeBase::id).toList();
            Map<String, Map<String, CodeLocale>> grouped = groupLocale(codeLangMapper.findByCodeIds(ids));
            contents = rows.stream()
                    .map(row -> toContent(row, grouped.getOrDefault(row.id(), Map.of())))
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
        String parentId = content.parentId();
        CodeBase parent = null;
        if (parentId != null) {
            parent = codeBaseMapper.findById(parentId);
            if (parent == null) {
                throw new CodeConflictException(null, content.code(), "상위 코드를 찾을 수 없습니다: " + parentId);
            }
        }

        String id = SerialUtils.next(SERIAL_CONFIG);
        int level = parent == null ? 0 : parent.level() + 1;
        String path = (parent == null ? "" : parent.path()) + "/" + content.code();
        String use = content.use() != null ? content.use() : DEFAULT_USE;

        CodeBase row = new CodeBase(id, parentId, content.code(),
                content.extra1(), content.extra2(), content.extra3(), content.extra4(), content.extra5(),
                level, path, content.sort(), use, now, SYSTEM_USER_ID, null, null);
        try {
            codeBaseMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new CodeConflictException(null, content.code(), "같은 경로에 이미 존재하는 코드입니다: " + content.code());
        }

        for (Map.Entry<String, CodeLocale> entry : content.locale().entrySet()) {
            codeLangMapper.insert(new CodeLang(id, entry.getKey(),
                    entry.getValue().name(), entry.getValue().remarks(), now, SYSTEM_USER_ID, null, null));
        }
    }

    private void updateOne(CodeContent content, LocalDateTime now) {
        CodeBase existing = codeBaseMapper.findById(content.id());
        if (existing == null) {
            throw new CodeConflictException(content.id(), content.code(), "대상 코드를 찾을 수 없습니다: " + content.id());
        }
        // code 값(경로 세그먼트)과 parentId는 이번 범위에서 수정 불가로 뒀다 —
        // 바꾸려면 이 노드와 모든 하위 노드의 code_path/code_lvl을 재계산해야
        // 하는데, 그 캐스케이드 재계산은 아직 구현하지 않았다. 바꾸고 싶으면
        // 삭제 후 재등록한다(삭제는 하위까지 캐스케이드된다).
        if (content.code() != null && !content.code().equals(existing.code())) {
            throw new CodeConflictException(content.id(), content.code(),
                    "코드 값은 수정할 수 없습니다(삭제 후 재등록하세요): " + content.id());
        }
        boolean parentChanged = content.parentId() != null
                ? !content.parentId().equals(existing.parentId())
                : existing.parentId() != null;
        if (parentChanged) {
            throw new CodeConflictException(content.id(), content.code(),
                    "상위 코드는 수정할 수 없습니다(삭제 후 재등록하세요): " + content.id());
        }

        String use = content.use() != null ? content.use() : existing.use();
        CodeBase row = new CodeBase(existing.id(), existing.parentId(), existing.code(),
                content.extra1(), content.extra2(), content.extra3(), content.extra4(), content.extra5(),
                existing.level(), existing.path(), content.sort(), use, null, null, now, SYSTEM_USER_ID);
        codeBaseMapper.update(row);

        if (content.locale() != null) {
            List<CodeLang> existingLangs = codeLangMapper.findByCodeId(existing.id());
            Set<String> existingLangCodes = existingLangs.stream()
                    .map(CodeLang::langCode)
                    .collect(Collectors.toSet());
            for (Map.Entry<String, CodeLocale> entry : content.locale().entrySet()) {
                String lang = entry.getKey();
                CodeLocale value = entry.getValue();
                if (existingLangCodes.contains(lang)) {
                    codeLangMapper.update(new CodeLang(existing.id(), lang, value.name(), value.remarks(),
                            null, null, now, SYSTEM_USER_ID));
                } else {
                    codeLangMapper.insert(new CodeLang(existing.id(), lang, value.name(), value.remarks(),
                            now, SYSTEM_USER_ID, null, null));
                }
            }
        }
    }

    private void deleteOne(CodeContent content) {
        CodeBase existing = codeBaseMapper.findById(content.id());
        if (existing == null) {
            throw new CodeConflictException(content.id(), content.code(), "대상 코드를 찾을 수 없습니다: " + content.id());
        }
        // 하위 코드도 모두 삭제 (api-define-admin.md 1.2절 "삭제 시, 하위
        // 코드도 모두 삭제"). code_path 접두어로 자신+모든 하위를 찾는다.
        List<CodeBase> targets = codeBaseMapper.findSelfAndDescendants(existing.path());
        List<String> ids = targets.stream().map(CodeBase::id).toList();
        codeLangMapper.deleteByCodeIds(ids);
        codeBaseMapper.deleteByIds(ids);
    }

    private void validate(CodePersistRequest request) {
        List<CodeError> errors = new ArrayList<>();
        for (CodeContent content : request.insertOrEmpty()) {
            if (!isBlank(content.id())) {
                errors.add(new CodeError(content.id(), content.code(),
                        "insert 항목의 id는 비어 있어야 합니다(서버가 채번합니다)"));
            }
            if (isBlank(content.code())) {
                errors.add(new CodeError(content.id(), content.code(), "code는 필수입니다"));
            }
            validateLocale(content, errors);
        }
        for (CodeContent content : request.updateOrEmpty()) {
            if (isBlank(content.id())) {
                errors.add(new CodeError(content.id(), content.code(), "update 항목의 id는 필수입니다"));
            }
            if (content.locale() != null) {
                validateLocale(content, errors);
            }
        }
        for (CodeContent content : request.deleteOrEmpty()) {
            if (isBlank(content.id())) {
                errors.add(new CodeError(content.id(), content.code(), "delete 항목의 id는 필수입니다"));
            }
        }
        if (!errors.isEmpty()) {
            throw new CodeValidationException(errors);
        }
    }

    private void validateLocale(CodeContent content, List<CodeError> errors) {
        if (content.locale() == null || content.locale().isEmpty()) {
            errors.add(new CodeError(content.id(), content.code(), "locale은 최소 1개 이상이어야 합니다"));
            return;
        }
        for (Map.Entry<String, CodeLocale> entry : content.locale().entrySet()) {
            if (entry.getValue() == null || isBlank(entry.getValue().name())) {
                errors.add(new CodeError(content.id(), content.code(),
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
            grouped.computeIfAbsent(row.codeId(), k -> new LinkedHashMap<>())
                    .put(row.langCode(), new CodeLocale(row.name(), row.remarks()));
        }
        return grouped;
    }

    private static CodeContent toContent(CodeBase row, Map<String, CodeLocale> locale) {
        return new CodeContent(row.id(), row.parentId(), row.code(), locale, row.use(),
                row.extra1(), row.extra2(), row.extra3(), row.extra4(), row.extra5(),
                row.path(), row.level(), row.sort());
    }
}
