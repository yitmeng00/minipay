package com.minipay.paymenthub.internal.web;

import com.minipay.paymenthub.api.PaymentException;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 problem details for the payments API, in the same shape as the ledger's. */
@RestControllerAdvice(basePackageClasses = PaymentController.class)
class PaymentExceptionHandler {

    @ExceptionHandler(PaymentException.class)
    ProblemDetail payment(PaymentException e) {
        HttpStatus status = switch (e.error()) {
            case PAYMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ILLEGAL_TRANSITION -> HttpStatus.CONFLICT;
            case IDEMPOTENCY_KEY_REUSED, INVALID_NETWORK_EVENT, INVALID_MESSAGE, UNSUPPORTED_MESSAGE -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setType(URI.create("https://minipay.local/problems/" + e.error().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        p.setTitle(e.error().name());
        p.setProperty("code", e.error().name());
        return p;
    }

    @ExceptionHandler(PaymentController.MissingIdempotencyKey.class)
    ProblemDetail missingKey(RuntimeException e) {
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
