package kkdugi.app.admin.code.exceptions;

import java.util.List;

import kkdugi.app.admin.code.models.CodeError;

public class CodeConflictException extends RuntimeException {

    private final List<CodeError> errors;

    public CodeConflictException(String id, String code, String reason) {
        super(reason);
        this.errors = List.of(new CodeError(id, code, reason));
    }

    public List<CodeError> getErrors() {
        return errors;
    }
}
