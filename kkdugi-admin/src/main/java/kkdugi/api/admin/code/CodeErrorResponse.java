package kkdugi.api.admin.code;

import java.util.List;

import kkdugi.app.admin.code.models.CodeError;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CodeErrorResponse {

    private final List<CodeError> errors;
}
