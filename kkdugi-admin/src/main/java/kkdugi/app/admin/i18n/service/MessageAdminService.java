package kkdugi.app.admin.i18n.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import kkdugi.app.admin.i18n.exceptions.MessageConflictException;
import kkdugi.app.admin.i18n.exceptions.MessageValidationException;
import kkdugi.app.admin.i18n.models.MessageContent;
import kkdugi.app.admin.i18n.models.MessageError;
import kkdugi.app.admin.i18n.models.MessagePersistRequest;
import kkdugi.app.admin.i18n.models.MessageSearchParams;
import kkdugi.core.i18n.mapper.I18nMessageMapper;
import kkdugi.core.i18n.models.I18nMessage;
import kkdugi.core.i18n.models.MessageCode;
import kkdugi.core.i18n.service.KkdugiMessageSource;
import kkdugi.core.models.Page;

@Service
public class MessageAdminService {

    private static final String SYSTEM_USER_ID = "SYSTEM";

    private final I18nMessageMapper mapper;
    private final KkdugiMessageSource messageSource;

    public MessageAdminService(I18nMessageMapper mapper, KkdugiMessageSource messageSource) {
        this.mapper = mapper;
        this.messageSource = messageSource;
    }

    @Transactional(readOnly = true)
    public Page<MessageContent> search(MessageSearchParams params) {
        int page = params.resolvedPage();
        int pageSize = params.resolvedPageSize();

        List<String> codes = mapper.searchDistinctCodes(params.code(), params.message(), params.offset(), pageSize);
        long totalItems = mapper.countDistinctCodes(params.code(), params.message());

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
                grouped.get(row.msgCode()).put(row.langCode(), row.msgText());
            }
            contents = codes.stream()
                    .map(code -> new MessageContent(code, grouped.get(code)))
                    .toList();
        }

        return Page.of(contents, page, pageSize, totalItems);
    }

    @Transactional
    public void persist(MessagePersistRequest request) {
        validate(request);

        Set<AffectedKey> affectedKeys = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now();

        for (MessageContent content : request.insertOrEmpty()) {
            for (Map.Entry<String, String> entry : content.locale().entrySet()) {
                insertRow(content.code(), entry.getKey(), entry.getValue(), now);
                affectedKeys.add(new AffectedKey(content.code(), entry.getKey()));
            }
        }

        for (MessageContent content : request.updateOrEmpty()) {
            List<I18nMessage> existingForCode = mapper.findByCode(content.code());
            if (existingForCode.isEmpty()) {
                throw new MessageConflictException(content.code(), "대상 코드를 찾을 수 없습니다: " + content.code());
            }
            Set<String> existingLangs = existingForCode.stream()
                    .map(I18nMessage::langCode)
                    .collect(Collectors.toSet());
            for (Map.Entry<String, String> entry : content.locale().entrySet()) {
                String lang = entry.getKey();
                String text = entry.getValue();
                // "update" 버킷은 기존 코드에 한정된다. 그 코드에 아직 없는
                // 언어가 locale 맵에 포함되면(새 언어 번역 추가) INSERT로,
                // 이미 있는 언어면 UPDATE로 처리한다. 코드 자체가 없으면
                // 위에서 이미 거부했으므로 여기서는 "새 코드를 update로
                // 만드는" 경우를 허용하지 않는다.
                if (existingLangs.contains(lang)) {
                    updateRow(content.code(), lang, text, now);
                } else {
                    insertRow(content.code(), lang, text, now);
                }
                affectedKeys.add(new AffectedKey(content.code(), lang));
            }
        }

        for (MessageContent content : request.deleteOrEmpty()) {
            List<I18nMessage> existing = mapper.findByCode(content.code());
            if (existing.isEmpty()) {
                throw new MessageConflictException(content.code(), "대상 코드를 찾을 수 없습니다: " + content.code());
            }
            // 코드 단위 삭제: 요청의 locale 값과 무관하게, 그 코드에 등록된
            // 모든 언어를 함께 삭제한다 (kkdugi-design ADR-0003 결정 #6).
            for (I18nMessage row : existing) {
                affectedKeys.add(new AffectedKey(row.msgCode(), row.langCode()));
            }
            mapper.deleteByCode(content.code());
        }

        registerCacheRefreshAfterCommit(affectedKeys);
    }

    private void insertRow(String code, String lang, String text, LocalDateTime now) {
        I18nMessage message = new I18nMessage(code, lang, text, now, SYSTEM_USER_ID, null, null);
        try {
            mapper.insert(message);
        } catch (DuplicateKeyException e) {
            throw new MessageConflictException(code, "이미 존재하는 메시지입니다: " + code + " (" + lang + ")");
        }
    }

    private void updateRow(String code, String lang, String text, LocalDateTime now) {
        I18nMessage message = new I18nMessage(code, lang, text, null, null, now, SYSTEM_USER_ID);
        int affected = mapper.update(message);
        if (affected == 0) {
            throw new MessageConflictException(code, "대상 언어를 찾을 수 없습니다: " + code + " (" + lang + ")");
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
        List<MessageError> errors = new ArrayList<>();
        validateBucket(request.insertOrEmpty(), true, errors);
        validateBucket(request.updateOrEmpty(), true, errors);
        validateBucket(request.deleteOrEmpty(), false, errors);
        if (!errors.isEmpty()) {
            throw new MessageValidationException(errors);
        }
    }

    private void validateBucket(List<MessageContent> contents, boolean requireLocaleValues, List<MessageError> errors) {
        for (MessageContent content : contents) {
            if (isBlank(content.code())) {
                errors.add(new MessageError(content.code(), "code는 필수입니다"));
                continue;
            }
            if (!MessageCode.matches(content.code())) {
                errors.add(new MessageError(content.code(), "code 형식이 올바르지 않습니다"));
            }
            if (requireLocaleValues) {
                if (content.locale() == null || content.locale().isEmpty()) {
                    errors.add(new MessageError(content.code(), "locale은 최소 1개 이상이어야 합니다"));
                } else {
                    for (Map.Entry<String, String> entry : content.locale().entrySet()) {
                        if (isBlank(entry.getValue())) {
                            errors.add(new MessageError(content.code(), "locale." + entry.getKey() + "은 필수입니다"));
                        }
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
