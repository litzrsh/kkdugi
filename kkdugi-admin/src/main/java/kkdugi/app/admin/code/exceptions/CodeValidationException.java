package kkdugi.app.admin.code.exceptions;

import java.util.List;

import kkdugi.app.admin.code.models.CodeError;

public class CodeValidationException extends RuntimeException {

    private final List<CodeError> errors;

    public CodeValidationException(List<CodeError> errors) {
        super("공통코드 저장 요청 검증에 실패했습니다");
        this.errors = errors;
    }

    public List<CodeError> getErrors() {
        return errors;
    }
}
