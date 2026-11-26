package com.minipay.settlement.internal.web;

import com.minipay.settlement.api.SettlementException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Settlement errors as RFC 9457 Problem Details. */
@RestControllerAdvice(basePackageClasses = SettlementController.class)
class SettlementExceptionHandler {

    @ExceptionHandler(SettlementException.class)
    ProblemDetail settlement(SettlementException e) {
        HttpStatus status = switch (e.error()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NOT_OPERATOR, ILLEGAL_COMMAND -> HttpStatus.CONFLICT;
            case INVALID_MESSAGE, UNSUPPORTED_MESSAGE, INVALID_REQUEST, IDEMPOTENCY_CONFLICT -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setType(URI.create("https://minipay.local/problems/" + e.error().name().toLowerCase().replace('_', '-')));
        p.setTitle(e.error().name());
        p.setProperty("code", e.error().name());
        return p;
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ProblemDetail badRequest(Exception e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setTitle("BAD_REQUEST");
        p.setProperty("code", "BAD_REQUEST");
        return p;
    }
}
