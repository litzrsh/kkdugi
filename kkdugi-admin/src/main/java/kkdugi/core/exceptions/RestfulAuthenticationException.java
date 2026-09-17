package kkdugi.core.exceptions;

import org.springframework.security.core.AuthenticationException;

import kkdugi.core.security.models.SessionUser;

public class RestfulAuthenticationException extends AuthenticationException {

    private final SessionUser userDetails;
    private final ExceptionMessage exceptionMessage;

    public RestfulAuthenticationException(String code, Object... args) {
        super(code);
        this.userDetails = null;
        this.exceptionMessage = new ExceptionMessage(code, args);
    }

    public RestfulAuthenticationException(SessionUser userDetails, String code, Object... args) {
        super(code);
        this.userDetails = userDetails;
        this.exceptionMessage = new ExceptionMessage(code, args);
    }

    public SessionUser getUserDetails() {
        return userDetails;
    }

    public ExceptionMessage getExceptionMessage() {
        return exceptionMessage;
    }
}
