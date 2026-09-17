package kkdugi.api.admin.code;

import java.util.List;

import kkdugi.app.admin.code.models.CodeError;

public record CodeErrorResponse(
        List<CodeError> errors
) {
}
