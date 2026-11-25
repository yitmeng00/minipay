package com.minipay.messaging.internal.web;

import com.minipay.messaging.api.MessagingException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Messaging errors as RFC 9457 Problem Details; the individual check failures are in {@code errors}. */
@RestControllerAdvice(basePackageClasses = MessagingController.class)
class MessagingExceptionHandler {

    @ExceptionHandler(MessagingException.class)
    ProblemDetail messaging(MessagingException e) {
        HttpStatus status = switch (e.error().category()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setType(URI.create("https://minipay.local/problems/" + e.error().name().toLowerCase().replace('_', '-')));
        p.setTitle(e.error().name());
        p.setProperty("code", e.error().name());
        if (!e.details().isEmpty()) {
            p.setProperty("errors", e.details());
        }
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
