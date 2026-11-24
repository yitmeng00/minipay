package com.minipay.ledger.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over the <em>semantic</em> content of a request, used to detect an Idempotency-Key being
 * reused for a different request (HTTP 422).
 *
 * <p>We hash the parsed command, not the raw HTTP body, so that harmless differences (JSON key order,
 * whitespace, {@code "10"} vs {@code "10.00"}) count as the same request. Fields are joined with the
 * ASCII unit separator so that {@code ("ab","c")} and {@code ("a","bc")} hash differently.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(String operation, Object... fields) {
        StringBuilder sb = new StringBuilder(operation);
        for (Object field : fields) {
            sb.append('\u001F').append(field == null ? "\u2205" : field.toString());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
