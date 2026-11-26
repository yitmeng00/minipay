package com.minipay.recon.internal.web;

import com.minipay.recon.api.ReconException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Reconciliation errors as RFC 9457 Problem Details. */
@RestControllerAdvice(basePackageClasses = ReconController.class)
class ReconExceptionHandler {

    @ExceptionHandler(ReconException.class)
    ProblemDetail recon(ReconException e) {
        HttpStatus status = switch (e.error()) {
            case NOT_FOUND, UNKNOWN_ACCOUNT -> HttpStatus.NOT_FOUND;
            case ILLEGAL_ACTION -> HttpStatus.CONFLICT;
            case INVALID_MESSAGE, INVALID_REQUEST, IDEMPOTENCY_CONFLICT -> HttpStatus.UNPROCESSABLE_ENTITY;
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
