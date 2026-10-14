package com.seekora.api;

import com.seekora.ai.AiProviderException;
import com.seekora.account.EmailAlreadyExistsException;
import com.seekora.account.InvalidCredentialsException;
import com.seekora.web.ResearchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps failures to RFC 9457 problem responses. Extending {@link ResponseEntityExceptionHandler} gives correct
 * statuses for the standard Spring MVC failures (unknown path 404, wrong content type 415, wrong method 405, ...);
 * previously the catch-all handler turned all of them into HTTP 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex) { return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage()); }

    @ExceptionHandler(AiProviderException.class)
    ProblemDetail aiProviderError(AiProviderException ex) { return ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage()); }

    @ExceptionHandler(ResearchException.class)
    ProblemDetail researchError(ResearchException ex) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage()); }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    ProblemDetail emailAlreadyExists(EmailAlreadyExistsException ex) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage()); }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail invalidCredentials(InvalidCredentialsException ex) { return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage()); }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail invalidPathArgument() { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "A path parameter has an invalid format."); }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).findFirst().orElse("Invalid request");
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Request body is missing or contains invalid JSON."), headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unhandled request failure", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed. Please try again.");
    }
}
