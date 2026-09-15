package kkdugi.api.admin.i18n;

import kkdugi.app.admin.i18n.MessageAdminService;
import kkdugi.app.admin.i18n.MessageConflictException;
import kkdugi.app.admin.i18n.MessageRowCommand;
import kkdugi.app.admin.i18n.MessageRowError;
import kkdugi.app.admin.i18n.MessageSaveResult;
import kkdugi.app.admin.i18n.MessageSearchResult;
import kkdugi.app.admin.i18n.MessageValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/i18n/messages")
public class MessageAdminController {

    private final MessageAdminService service;

    public MessageAdminController(MessageAdminService service) {
        this.service = service;
    }

    @GetMapping
    public MessageListResponse list(
            @RequestParam(required = false) String msgCd,
            @RequestParam(required = false) String langCd,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        MessageSearchResult result = service.search(msgCd, langCd, page, size);

        List<MessageRowResponse> rows = result.rows().stream()
                .map(m -> new MessageRowResponse(m.msgCd(), m.langCd(), m.msgVal(), m.updDtm()))
                .toList();

        return new MessageListResponse(rows, result.totalCount(), result.page(), result.size());
    }

    @PostMapping
    public MessageSaveResponse save(@RequestBody MessageSaveRequest request) {
        List<MessageRowRequest> requestRows = request.rows() != null ? request.rows() : List.of();
        if (requestRows.isEmpty()) {
            throw new MessageValidationException(List.of(
                    new MessageRowError(-1, null, null, "rows는 최소 1개 이상이어야 합니다")));
        }
        List<MessageRowCommand> commands = requestRows.stream()
                .map(r -> new MessageRowCommand(r.crudType(), r.msgCd(), r.langCd(), r.msgVal()))
                .toList();

        MessageSaveResult result = service.saveAll(commands);

        List<MessageRowResponse> rows = result.rows().stream()
                .map(r -> new MessageRowResponse(r.msgCd(), r.langCd(), r.msgVal(), r.updDtm()))
                .toList();

        return new MessageSaveResponse(
                result.insertedCount(), result.updatedCount(), result.deletedCount(), rows);
    }

    @ExceptionHandler(MessageValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public MessageSaveErrorResponse handleValidation(MessageValidationException e) {
        return toErrorResponse(e.getErrors());
    }

    @ExceptionHandler(MessageConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public MessageSaveErrorResponse handleConflict(MessageConflictException e) {
        return toErrorResponse(e.getErrors());
    }

    private static MessageSaveErrorResponse toErrorResponse(List<MessageRowError> errors) {
        List<MessageRowErrorResponse> rows = errors.stream()
                .map(err -> new MessageRowErrorResponse(
                        err.rowIndex(), err.msgCd(), err.langCd(), err.reason()))
                .toList();
        return new MessageSaveErrorResponse(rows);
    }
}
