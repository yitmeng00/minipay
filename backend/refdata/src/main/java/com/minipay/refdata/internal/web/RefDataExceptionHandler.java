package com.minipay.refdata.internal.web;

import com.minipay.refdata.api.RefDataException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Reference data errors as RFC 9457 Problem Details. */
@RestControllerAdvice(basePackageClasses = RefDataController.class)
class RefDataExceptionHandler {

    @ExceptionHandler(RefDataException.class)
    ProblemDetail refdata(RefDataException e) {
        HttpStatus status = switch (e.error().category()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setType(URI.create("https://minipay.local/problems/" + e.error().name().toLowerCase().replace('_', '-')));
        p.setTitle(e.error().name());
        p.setProperty("code", e.error().name());
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
