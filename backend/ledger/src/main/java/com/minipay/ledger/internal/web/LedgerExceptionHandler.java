package com.minipay.ledger.internal.web;

import com.minipay.ledger.api.LedgerException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps ledger errors to RFC 9457 Problem Details. The ISO 20022 reason code (AM04, AC01 ...) is
 * included as {@code reasonCode}, so API clients see the same code a pacs.002 rejection would carry.
 */
@RestControllerAdvice(basePackageClasses = LedgerController.class)
class LedgerExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    ProblemDetail ledger(LedgerException e) {
        HttpStatus status = switch (e.error().category()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setType(URI.create("https://minipay.local/problems/" + e.error().name().toLowerCase().replace('_', '-')));
        p.setTitle(e.error().name());
        p.setProperty("code", e.error().name());
        if (e.error().isoReasonCode() != null) {
            p.setProperty("reasonCode", e.error().isoReasonCode());
        }
        return p;
    }

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    ProblemDetail missingKey(MissingIdempotencyKeyException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setTitle("IDEMPOTENCY_KEY_REQUIRED");
        p.setProperty("code", "IDEMPOTENCY_KEY_REQUIRED");
        return p;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setTitle("BAD_REQUEST");
        p.setProperty("code", "BAD_REQUEST");
        return p;
    }
}
