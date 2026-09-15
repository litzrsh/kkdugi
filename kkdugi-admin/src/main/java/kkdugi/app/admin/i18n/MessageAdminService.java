package kkdugi.app.admin.i18n;

import kkdugi.core.i18n.I18nMessage;
import kkdugi.core.i18n.I18nMessageMapper;
import kkdugi.core.i18n.KkdugiMessageSource;
import kkdugi.core.i18n.MessageCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class MessageAdminService {

    private static final String SYSTEM_USER_ID = "SYSTEM";

    private final I18nMessageMapper mapper;
    private final KkdugiMessageSource messageSource;

    public MessageAdminService(I18nMessageMapper mapper, KkdugiMessageSource messageSource) {
        this.mapper = mapper;
        this.messageSource = messageSource;
    }

    @Transactional
    public MessageSaveResult saveAll(List<MessageRowCommand> rows) {
        validateAll(rows);

        int insertedCount = 0;
        int updatedCount = 0;
        int deletedCount = 0;
        List<MessageRowResult> results = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // DB 오류가 나면 즉시 예외를 던져 트랜잭션 전체를 롤백한다. Postgres는
        // 한 statement가 실패하면 같은 트랜잭션 안에서 추가 statement 실행을
        // 거부하므로, 실패 후 나머지 행을 계속 처리하려고 시도하면 안 된다.
        for (int i = 0; i < rows.size(); i++) {
            MessageRowCommand row = rows.get(i);
            switch (row.crudType()) {
                case INSERT -> {
                    insertRow(i, row, now);
                    insertedCount++;
                    results.add(new MessageRowResult(row.msgCd(), row.langCd(), row.msgVal(), now));
                }
                case UPDATE -> {
                    updateRow(i, row, now);
                    updatedCount++;
                    results.add(new MessageRowResult(row.msgCd(), row.langCd(), row.msgVal(), now));
                }
                case DELETE -> {
                    deleteRow(i, row);
                    deletedCount++;
                }
            }
        }

        registerCacheRefreshAfterCommit(rows);

        return new MessageSaveResult(insertedCount, updatedCount, deletedCount, results);
    }

    private void insertRow(int rowIndex, MessageRowCommand row, LocalDateTime now) {
        I18nMessage message = new I18nMessage(
                row.msgCd(), row.langCd(), row.msgVal(), now, SYSTEM_USER_ID, null, null);
        try {
            mapper.insert(message);
        } catch (DuplicateKeyException e) {
            throw new MessageConflictException(rowIndex, row.msgCd(), row.langCd(), "이미 존재하는 메시지입니다");
        }
    }

    private void updateRow(int rowIndex, MessageRowCommand row, LocalDateTime now) {
        I18nMessage message = new I18nMessage(
                row.msgCd(), row.langCd(), row.msgVal(), null, null, now, SYSTEM_USER_ID);
        int affected = mapper.update(message);
        if (affected == 0) {
            throw new MessageConflictException(rowIndex, row.msgCd(), row.langCd(), "대상 행을 찾을 수 없습니다");
        }
    }

    private void deleteRow(int rowIndex, MessageRowCommand row) {
        int affected = mapper.delete(row.msgCd(), row.langCd());
        if (affected == 0) {
            throw new MessageConflictException(rowIndex, row.msgCd(), row.langCd(), "대상 행을 찾을 수 없습니다");
        }
    }

    private void registerCacheRefreshAfterCommit(List<MessageRowCommand> rows) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (MessageRowCommand row : rows) {
                    messageSource.refresh(row.msgCd(), row.langCd());
                }
            }
        });
    }

    private void validateAll(List<MessageRowCommand> rows) {
        List<MessageRowError> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            MessageRowCommand row = rows.get(i);
            if (isBlank(row.msgCd())) {
                errors.add(new MessageRowError(i, row.msgCd(), row.langCd(), "msgCd는 필수입니다"));
            }
            if (isBlank(row.langCd())) {
                errors.add(new MessageRowError(i, row.msgCd(), row.langCd(), "langCd는 필수입니다"));
            }
            if (!isBlank(row.msgCd()) && !MessageCode.matches(row.msgCd())) {
                errors.add(new MessageRowError(i, row.msgCd(), row.langCd(), "msgCd 형식이 올바르지 않습니다"));
            }
            if (row.crudType() != CrudType.DELETE && isBlank(row.msgVal())) {
                errors.add(new MessageRowError(i, row.msgCd(), row.langCd(), "msgVal은 필수입니다"));
            }
            if (row.crudType() == null) {
                errors.add(new MessageRowError(i, row.msgCd(), row.langCd(), "crudType는 필수입니다"));
            }
        }
        if (!errors.isEmpty()) {
            throw new MessageValidationException(errors);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Transactional(readOnly = true)
    public MessageSearchResult search(String msgCd, String langCd, int page, int size) {
        int clampedPage = Math.max(page, 0);
        int clampedSize = Math.min(Math.max(size, 1), 200);
        int offset = clampedPage * clampedSize;

        List<I18nMessage> rows = mapper.search(msgCd, langCd, offset, clampedSize);
        long totalCount = mapper.count(msgCd, langCd);

        return new MessageSearchResult(rows, totalCount, clampedPage, clampedSize);
    }
}
