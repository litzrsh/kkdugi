package kkdugi.core.exceptions.advice;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import kkdugi.core.exceptions.ExceptionMessage;
import kkdugi.core.exceptions.RestfulException;
import kkdugi.core.exceptions.RestfulExceptions;

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

    @ExceptionHandler({ Exception.class })
    public ResponseEntity<ExceptionMessage> handlerDefaultException(Exception e) {
        if (log.isTraceEnabled()) {
            e.printStackTrace(System.err);
        }
        return new ResponseEntity<>(new ExceptionMessage(RestfulException.ERR_DEFAULT), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
