package com.minipay.paymenthub.internal.domain;

import com.minipay.paymenthub.api.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;

/** One status change. Append-only: {@code @Immutable} here, and a trigger in the database. */
@Entity
@Immutable
@Table(name = "payment_state_history", schema = "payment")
public class PaymentStateHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private PaymentStatus toStatus;

    @Column(name = "reason_code")
    private String reasonCode;

    private String detail;

    @Column(nullable = false)
    private String actor;

    /** Set by the database (clock_timestamp()) so rows within one transaction still order correctly. */
    @Generated
    @Column(name = "occurred_at", insertable = false, updatable = false)
    private Instant occurredAt;

    protected PaymentStateHistory() {
    }

    public PaymentStateHistory(UUID paymentId, PaymentStatus from, PaymentStatus to, String reasonCode,
                               String detail, String actor) {
        this.paymentId = paymentId;
        this.fromStatus = from;
        this.toStatus = to;
        this.reasonCode = reasonCode;
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 497) + "...";
        this.actor = actor;
    }

    public Long id() { return id; }
    public UUID paymentId() { return paymentId; }
    public PaymentStatus fromStatus() { return fromStatus; }
    public PaymentStatus toStatus() { return toStatus; }
    public String reasonCode() { return reasonCode; }
    public String detail() { return detail; }
    public String actor() { return actor; }
    public Instant occurredAt() { return occurredAt; }
}
