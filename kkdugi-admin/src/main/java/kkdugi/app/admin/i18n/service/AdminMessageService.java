package kkdugi.app.admin.i18n.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import kkdugi.app.admin.i18n.exceptions.AdminMessageConflictException;
import kkdugi.app.admin.i18n.exceptions.AdminMessageValidationException;
import kkdugi.app.admin.i18n.mapper.AdminMessageMapper;
import kkdugi.app.admin.i18n.models.AdminMessage;
import kkdugi.app.admin.i18n.models.AdminMessageParams;
import kkdugi.app.admin.i18n.models.AdminMessagePersistRequest;
import kkdugi.app.admin.i18n.models.MessageCode;
import kkdugi.app.admin.i18n.models.MessageCodeRow;
import kkdugi.core.i18n.models.I18nMessage;
import kkdugi.core.i18n.service.KkdugiMessageSource;
import kkdugi.core.models.Page;

@Service
public class AdminMessageService {

    private static final Logger log = LoggerFactory.getLogger(AdminMessageService.class);

    public static final String ERR_INVALID_FORMAT = "message.err.invalid_format";
    public static final String ERR_LOCALE_REQUIRED = "message.err.locale_required";
    public static final String ERR_DUPLICATE = "message.err.duplicate";
    public static final String ERR_NOT_FOUND = "message.err.not_found";

    private static final String SYSTEM_USER_ID = "SYSTEM";

    private final AdminMessageMapper mapper;
    private final KkdugiMessageSource messageSource;

    public AdminMessageService(AdminMessageMapper mapper, KkdugiMessageSource messageSource) {
        this.mapper = mapper;
        this.messageSource = messageSource;
    }

    @Transactional(readOnly = true)
    public Page<AdminMessage> search(AdminMessageParams params) {
        params.setPage(params.resolvedPage());
        params.setPageSize(params.resolvedPageSize());

        List<MessageCodeRow> codeRows = mapper.searchDistinctCodes(
                params.getCode(), params.getMessage(), params.getOffset(), params.getLimit());
        List<String> codes = codeRows.stream().map(MessageCodeRow::getCode).toList();

        List<AdminMessage> contents;
        if (codes.isEmpty()) {
            contents = List.of();
        } else {
            List<I18nMessage> rows = mapper.findByCodes(codes);
            Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
            for (String code : codes) {
                grouped.put(code, new LinkedHashMap<>());
            }
            for (I18nMessage row : rows) {
                grouped.get(row.getMsgCode()).put(row.getLangCode(), row.getMsgText());
            }
            contents = codeRows.stream()
                    .map(row -> {
                        AdminMessage content = new AdminMessage(row.getCode(), grouped.get(row.getCode()));
                        content.setTotalSize(row.getTotalSize());
                        return content;
                    })
                    .toList();
        }

        return Page.of(contents, params);
    }

    @Transactional
    public void persist(AdminMessagePersistRequest request) {
        validate(request);

        Set<AffectedKey> affectedKeys = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now();

        for (AdminMessage content : request.insertOrEmpty()) {
            for (Map.Entry<String, String> entry : content.getLocale().entrySet()) {
                insertRow(content.getCode(), entry.getKey(), entry.getValue(), now);
                affectedKeys.add(new AffectedKey(content.getCode(), entry.getKey()));
            }
        }

        for (AdminMessage content : request.updateOrEmpty()) {
            List<I18nMessage> existingForCode = mapper.findByCode(content.getCode());
            if (existingForCode.isEmpty()) {
                log.warn("메시지 수정 실패 - 대상 코드를 찾을 수 없음: code={}", content.getCode());
                throw new AdminMessageConflictException(ERR_NOT_FOUND);
            }
            Set<String> existingLangs = existingForCode.stream()
                    .map(I18nMessage::getLangCode)
                    .collect(Collectors.toSet());
            for (Map.Entry<String, String> entry : content.getLocale().entrySet()) {
                String lang = entry.getKey();
                String text = entry.getValue();
                // "update" 버킷은 기존 코드에 한정된다. 그 코드에 아직 없는
                // 언어가 locale 맵에 포함되면(새 언어 번역 추가) INSERT로,
                // 이미 있는 언어면 UPDATE로 처리한다. 코드 자체가 없으면
                // 위에서 이미 거부했으므로 여기서는 "새 코드를 update로
                // 만드는" 경우를 허용하지 않는다.
                if (existingLangs.contains(lang)) {
                    updateRow(content.getCode(), lang, text, now);
                } else {
                    insertRow(content.getCode(), lang, text, now);
                }
                affectedKeys.add(new AffectedKey(content.getCode(), lang));
            }
        }

        for (AdminMessage content : request.deleteOrEmpty()) {
            List<I18nMessage> existing = mapper.findByCode(content.getCode());
            if (existing.isEmpty()) {
                log.warn("메시지 삭제 실패 - 대상 코드를 찾을 수 없음: code={}", content.getCode());
                throw new AdminMessageConflictException(ERR_NOT_FOUND);
            }
            // 코드 단위 삭제: 요청의 locale 값과 무관하게, 그 코드에 등록된
            // 모든 언어를 함께 삭제한다 (kkdugi-design ADR-0003 결정 #6).
            for (I18nMessage row : existing) {
                affectedKeys.add(new AffectedKey(row.getMsgCode(), row.getLangCode()));
            }
            mapper.deleteByCode(content.getCode());
        }

        registerCacheRefreshAfterCommit(affectedKeys);
    }

    private void insertRow(String code, String lang, String text, LocalDateTime now) {
        I18nMessage message = new I18nMessage(code, lang, text);
        message.setCreatedAt(now);
        message.setCreatorId(SYSTEM_USER_ID);
        try {
            mapper.insert(message);
        } catch (DuplicateKeyException e) {
            log.warn("메시지 등록 실패 - 이미 존재하는 메시지: code={}, lang={}", code, lang);
            throw new AdminMessageConflictException(ERR_DUPLICATE);
        }
    }

    private void updateRow(String code, String lang, String text, LocalDateTime now) {
        I18nMessage message = new I18nMessage(code, lang, text);
        message.setUpdatedAt(now);
        message.setUpdaterId(SYSTEM_USER_ID);
        int affected = mapper.update(message);
        if (affected == 0) {
            log.warn("메시지 수정 실패 - 대상 언어를 찾을 수 없음: code={}, lang={}", code, lang);
            throw new AdminMessageConflictException(ERR_NOT_FOUND);
        }
    }

    private void registerCacheRefreshAfterCommit(Set<AffectedKey> affectedKeys) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (AffectedKey key : affectedKeys) {
                    messageSource.refresh(key.code(), key.lang());
                }
            }
        });
    }

    private void validate(AdminMessagePersistRequest request) {
        validateBucket(request.insertOrEmpty(), true);
        validateBucket(request.updateOrEmpty(), true);
        validateBucket(request.deleteOrEmpty(), false);
    }

    private void validateBucket(List<AdminMessage> contents, boolean requireLocaleValues) {
        for (AdminMessage content : contents) {
            if (!MessageCode.matches(content.getCode())) {
                log.warn("메시지 저장 검증 실패 - code 값 형식이 올바르지 않음: code={}", content.getCode());
                throw new AdminMessageValidationException(ERR_INVALID_FORMAT);
            }
            if (requireLocaleValues) {
                if (content.getLocale() == null || content.getLocale().isEmpty()) {
                    log.warn("메시지 저장 검증 실패 - locale이 비어 있음: code={}", content.getCode());
                    throw new AdminMessageValidationException(ERR_LOCALE_REQUIRED);
                }
                for (Map.Entry<String, String> entry : content.getLocale().entrySet()) {
                    if (isBlank(entry.getValue())) {
                        log.warn("메시지 저장 검증 실패 - locale.{} 값이 비어 있음: code={}",
                                entry.getKey(), content.getCode());
                        throw new AdminMessageValidationException(ERR_LOCALE_REQUIRED);
                    }
                }
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record AffectedKey(String code, String lang) {
    }
}
