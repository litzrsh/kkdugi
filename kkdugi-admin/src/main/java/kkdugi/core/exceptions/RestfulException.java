package kkdugi.core.exceptions;

import org.springframework.http.HttpStatus;

public class RestfulException extends Exception {

    public static final String ERR_DEFAULT = "system.err.default";

    private final HttpStatus status;
    private final ExceptionMessage exceptionMessage;

    public RestfulException() {
        super(ERR_DEFAULT);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
        this.exceptionMessage = new ExceptionMessage(ERR_DEFAULT);
    }

    public RestfulException(String code, Object... args) {
        super(code);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
        this.exceptionMessage = new ExceptionMessage(code, args);
    }

    public RestfulException(HttpStatus status, String code, Object... args) {
        super(code);
        this.status = status;
        this.exceptionMessage = new ExceptionMessage(code, args);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ExceptionMessage getExceptionMessage() {
        return exceptionMessage;
    }
}
