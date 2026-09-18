package kkdugi.app.admin.code.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CodeError {

    private final String id;
    private final String code;
    private final String reason;
}
