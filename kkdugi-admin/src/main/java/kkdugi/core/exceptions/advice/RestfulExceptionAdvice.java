package kkdugi.core.exceptions.advice;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.exceptions.RestfulException;
import kkdugi.core.exceptions.RestfulExceptions;
import kkdugi.core.security.authentication.RestfulAccessDeniedHandler;
import kkdugi.core.security.authentication.RestfulAuthenticationEntryPoint;

@RestControllerAdvice
public class RestfulExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(RestfulExceptionAdvice.class);
    
    @ExceptionHandler({ RestfulException.class })
    public ResponseEntity<ExceptionMessage> handleRestfulException(RestfulException e) {
        if (log.isTraceEnabled()) {
            e.printStackTrace(System.err);
        }
        return new ResponseEntity<>(e.getExceptionMessage(), e.getStatus());
    }

    @ExceptionHandler({ RestfulExceptions.class })
    public ResponseEntity<List<ExceptionMessage>> handleRestfulExceptions(RestfulExceptions e) {
        if(log.isTraceEnabled()) {
            e.printStackTrace(System.err);
        }
        return new ResponseEntity<>(e.getExceptionMessages(), e.getStatus());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ExceptionMessage> handleAuthentication(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                .body(new ExceptionMessage(RestfulAuthenticationEntryPoint.ERR_UNAUTHORIZED));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ExceptionMessage> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                .body(new ExceptionMessage(RestfulAccessDeniedHandler.ERR_ACCESS_DENIED));
    }

    @ExceptionHandler({ Exception.class })
    public ResponseEntity<ExceptionMessage> handlerDefaultException(Exception e) {
        if (log.isTraceEnabled()) {
            e.printStackTrace(System.err);
        }
        return new ResponseEntity<>(new ExceptionMessage(RestfulException.ERR_DEFAULT), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
