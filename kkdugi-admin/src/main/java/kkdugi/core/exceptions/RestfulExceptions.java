package kkdugi.core.exceptions;

import java.util.List;

import org.springframework.http.HttpStatus;

public class RestfulExceptions extends Exception {

    private final HttpStatus status;
    private final List<ExceptionMessage> exceptionMessages;

    public RestfulExceptions(List<ExceptionMessage> exceptionMessages) {
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
        this.exceptionMessages = exceptionMessages;
    }

    public RestfulExceptions(HttpStatus status, List<ExceptionMessage> exceptionMessages) {
        this.status = status;
        this.exceptionMessages = exceptionMessages;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public List<ExceptionMessage> getExceptionMessages() {
        return exceptionMessages;
    }
}
