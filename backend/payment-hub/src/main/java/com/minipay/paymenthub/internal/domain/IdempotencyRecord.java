package com.minipay.paymenthub.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Idempotency-Key → the payment it created or changed, plus a hash of the request that used it. */
@Entity
@Immutable
@Table(name = "idempotency_key", schema = "payment")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key")
    private String key;

    @Column(name = "request_hash", nullable = false, columnDefinition = "bpchar")
    private String requestHash;

    @Column(nullable = false)
    private String operation;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String key, String requestHash, String operation, UUID paymentId, Instant createdAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.operation = operation;
        this.paymentId = paymentId;
        this.createdAt = createdAt;
    }

    public String key() { return key; }
    public String requestHash() { return requestHash; }
    public String operation() { return operation; }
    public UUID paymentId() { return paymentId; }
}
