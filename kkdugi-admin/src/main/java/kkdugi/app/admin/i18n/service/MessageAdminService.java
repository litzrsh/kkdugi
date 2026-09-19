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

import kkdugi.app.admin.i18n.exceptions.MessageConflictException;
import kkdugi.app.admin.i18n.exceptions.MessageValidationException;
import kkdugi.app.admin.i18n.models.MessageContent;
import kkdugi.app.admin.i18n.models.MessagePersistRequest;
import kkdugi.app.admin.i18n.models.MessageSearchParams;
import kkdugi.core.i18n.mapper.I18nMessageMapper;
import kkdugi.core.i18n.models.I18nMessage;
import kkdugi.core.i18n.models.MessageCode;
import kkdugi.core.i18n.models.MessageCodeRow;
import kkdugi.core.i18n.service.KkdugiMessageSource;
import kkdugi.core.models.Page;

@Service
public class MessageAdminService {

    private static final Logger log = LoggerFactory.getLogger(MessageAdminService.class);

    public static final String ERR_INVALID_FORMAT = "message.err.invalid_format";
    public static final String ERR_LOCALE_REQUIRED = "message.err.locale_required";
    public static final String ERR_DUPLICATE = "message.err.duplicate";
    public static final String ERR_NOT_FOUND = "message.err.not_found";

    private static final String SYSTEM_USER_ID = "SYSTEM";

    private final I18nMessageMapper mapper;
    private final KkdugiMessageSource messageSource;

    public MessageAdminService(I18nMessageMapper mapper, KkdugiMessageSource messageSource) {
        this.mapper = mapper;
        this.messageSource = messageSource;
    }

    @Transactional(readOnly = true)
    public Page<MessageContent> search(MessageSearchParams params) {
        params.setPage(params.resolvedPage());
        params.setPageSize(params.resolvedPageSize());

        List<MessageCodeRow> codeRows = mapper.searchDistinctCodes(
                params.getCode(), params.getMessage(), params.getOffset(), params.getLimit());
        List<String> codes = codeRows.stream().map(MessageCodeRow::getCode).toList();

        List<MessageContent> contents;
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
                        MessageContent content = new MessageContent(row.getCode(), grouped.get(row.getCode()));
                        content.setTotalSize(row.getTotalSize());
                        return content;
                    })
                    .toList();
        }

        return Page.of(contents, params);
    }

    @Transactional
    public void persist(MessagePersistRequest request) {
        validate(request);

        Set<AffectedKey> affectedKeys = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now();

        for (MessageContent content : request.insertOrEmpty()) {
            for (Map.Entry<String, String> entry : content.getLocale().entrySet()) {
                insertRow(content.getCode(), entry.getKey(), entry.getValue(), now);
                affectedKeys.add(new AffectedKey(content.getCode(), entry.getKey()));
            }
        }

        for (MessageContent content : request.updateOrEmpty()) {
            List<I18nMessage> existingForCode = mapper.findByCode(content.getCode());
            if (existingForCode.isEmpty()) {
                log.warn("메시지 수정 실패 - 대상 코드를 찾을 수 없음: code={}", content.getCode());
                throw new MessageConflictException(ERR_NOT_FOUND);
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

        for (MessageContent content : request.deleteOrEmpty()) {
            List<I18nMessage> existing = mapper.findByCode(content.getCode());
            if (existing.isEmpty()) {
                log.warn("메시지 삭제 실패 - 대상 코드를 찾을 수 없음: code={}", content.getCode());
                throw new MessageConflictException(ERR_NOT_FOUND);
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
            throw new MessageConflictException(ERR_DUPLICATE);
        }
    }

    private void updateRow(String code, String lang, String text, LocalDateTime now) {
        I18nMessage message = new I18nMessage(code, lang, text);
        message.setUpdatedAt(now);
        message.setUpdaterId(SYSTEM_USER_ID);
        int affected = mapper.update(message);
        if (affected == 0) {
            log.warn("메시지 수정 실패 - 대상 언어를 찾을 수 없음: code={}, lang={}", code, lang);
            throw new MessageConflictException(ERR_NOT_FOUND);
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

    private void validate(MessagePersistRequest request) {
        validateBucket(request.insertOrEmpty(), true);
        validateBucket(request.updateOrEmpty(), true);
        validateBucket(request.deleteOrEmpty(), false);
    }

    private void validateBucket(List<MessageContent> contents, boolean requireLocaleValues) {
        for (MessageContent content : contents) {
            if (!MessageCode.matches(content.getCode())) {
                log.warn("메시지 저장 검증 실패 - code 값 형식이 올바르지 않음: code={}", content.getCode());
                throw new MessageValidationException(ERR_INVALID_FORMAT);
            }
            if (requireLocaleValues) {
                if (content.getLocale() == null || content.getLocale().isEmpty()) {
                    log.warn("메시지 저장 검증 실패 - locale이 비어 있음: code={}", content.getCode());
                    throw new MessageValidationException(ERR_LOCALE_REQUIRED);
                }
                for (Map.Entry<String, String> entry : content.getLocale().entrySet()) {
                    if (isBlank(entry.getValue())) {
                        log.warn("메시지 저장 검증 실패 - locale.{} 값이 비어 있음: code={}",
                                entry.getKey(), content.getCode());
                        throw new MessageValidationException(ERR_LOCALE_REQUIRED);
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
