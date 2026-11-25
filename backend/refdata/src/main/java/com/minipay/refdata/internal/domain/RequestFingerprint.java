package com.minipay.refdata.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over the parsed request, to detect an Idempotency-Key reused for a different request (422). */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(String operation, Object... fields) {
        StringBuilder sb = new StringBuilder(operation);
        for (Object field : fields) {
            sb.append('\u001F').append(field == null ? "∅" : field.toString());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
