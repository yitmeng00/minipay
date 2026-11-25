package com.minipay.paymenthub.internal.domain;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.paymenthub.api.PaymentDirection;
import com.minipay.paymenthub.api.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The payment aggregate. Status only changes through {@link #transitionTo}, which consults the
 * {@link PaymentStateMachine}; callers must also record a {@link PaymentStateHistory} row and an outbox
 * event in the same transaction (see {@code PaymentTransitions}).
 *
 * <p>{@code @Version} gives optimistic locking: if two threads load version 3 and both try to save,
 * the second gets an OptimisticLockException instead of overwriting the first. For payments that is
 * the right trade-off (contention on one payment is rare) — the opposite of the ledger, where one hot
 * account sees constant contention and pessimistic locks are used (ADR-002 §5).
 */
@Entity
@Table(name = "payment", schema = "payment")
public class Payment {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uetr;

    @Column(name = "end_to_end_id", nullable = false, updatable = false)
    private String endToEndId;

    @Column(name = "instruction_id", nullable = false, updatable = false)
    private String instructionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, columnDefinition = "bpchar")
    private String currency;

    @Column(name = "interbank_settlement_amount")
    private BigDecimal interbankSettlementAmount;

    @Column(name = "charges_amount", nullable = false)
    private BigDecimal chargesAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_bearer", nullable = false, updatable = false, columnDefinition = "bpchar")
    private ChargeBearer chargeBearer;

    @Column(name = "debtor_name", nullable = false)
    private String debtorName;

    @Column(name = "debtor_account", nullable = false, updatable = false)
    private String debtorAccount;

    @Column(name = "debtor_agent_bic", nullable = false, updatable = false)
    private String debtorAgentBic;

    @Column(name = "creditor_name", nullable = false, updatable = false)
    private String creditorName;

    @Column(name = "creditor_account", nullable = false, updatable = false)
    private String creditorAccount;

    @Column(name = "creditor_agent_bic", nullable = false, updatable = false)
    private String creditorAgentBic;

    @Column(name = "instructed_agent_bic")
    private String instructedAgentBic;

    @Column(name = "remittance_info", updatable = false)
    private String remittanceInfo;

    @Column(name = "requested_exec_date", nullable = false, updatable = false)
    private LocalDate requestedExecDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(name = "execution_date")
    private LocalDate executionDate;

    @Column(name = "routing_rule_id")
    private String routingRuleId;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "rejection_detail")
    private String rejectionDetail;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "settlement_account_id")
    private Long settlementAccountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Payment() {
        // JPA
    }

    @SuppressWarnings("java:S107")
    public static Payment receiveOutgoing(UUID uetr, String endToEndId, String instructionId, BigDecimal amount,
                                          String currency, ChargeBearer chargeBearer, String debtorName,
                                          String debtorAccount, String debtorAgentBic, String creditorName,
                                          String creditorAccount, String creditorAgentBic, String remittanceInfo,
                                          LocalDate requestedExecDate, Instant now) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.uetr = uetr;
        p.endToEndId = endToEndId;
        p.instructionId = instructionId;
        p.direction = PaymentDirection.OUTGOING;
        p.status = PaymentStatus.RECEIVED;
        p.amount = amount;
        p.currency = currency;
        p.chargesAmount = BigDecimal.ZERO;
        p.chargeBearer = chargeBearer;
        p.debtorName = debtorName;
        p.debtorAccount = debtorAccount;
        p.debtorAgentBic = debtorAgentBic;
        p.creditorName = creditorName;
        p.creditorAccount = creditorAccount;
        p.creditorAgentBic = creditorAgentBic;
        p.remittanceInfo = remittanceInfo;
        p.requestedExecDate = requestedExecDate;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    /** Changes status after checking the state machine. Returns the previous status. */
    public PaymentStatus transitionTo(PaymentStatus next, Instant now) {
        PaymentStateMachine.check(status, next);
        PaymentStatus previous = status;
        status = next;
        updatedAt = now;
        return previous;
    }

    public void reject(String reasonCode, String detail) {
        rejectionReason = reasonCode;
        rejectionDetail = truncate(detail);
    }

    public void fundsReserved(UUID reservation, BigDecimal charges, BigDecimal interbankAmount) {
        reservationId = reservation;
        chargesAmount = charges;
        interbankSettlementAmount = interbankAmount;
    }

    /** Validation fixed the business day we execute on (the requested date, or the next business day). */
    public void executeOn(LocalDate date) {
        executionDate = date;
    }

    public void routed(String nextAgentBic, long settlementAccount, LocalDate settlementDate, String ruleId) {
        instructedAgentBic = nextAgentBic;
        settlementAccountId = settlementAccount;
        valueDate = settlementDate;
        routingRuleId = ruleId;
    }

    public void debtorName(String name) {
        debtorName = name;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }

    public UUID id() { return id; }
    public UUID uetr() { return uetr; }
    public String endToEndId() { return endToEndId; }
    public String instructionId() { return instructionId; }
    public PaymentDirection direction() { return direction; }
    public PaymentStatus status() { return status; }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    public BigDecimal interbankSettlementAmount() { return interbankSettlementAmount; }
    public BigDecimal chargesAmount() { return chargesAmount; }
    public ChargeBearer chargeBearer() { return chargeBearer; }
    public String debtorName() { return debtorName; }
    public String debtorAccount() { return debtorAccount; }
    public String debtorAgentBic() { return debtorAgentBic; }
    public String creditorName() { return creditorName; }
    public String creditorAccount() { return creditorAccount; }
    public String creditorAgentBic() { return creditorAgentBic; }
    public String instructedAgentBic() { return instructedAgentBic; }
    public String remittanceInfo() { return remittanceInfo; }
    public LocalDate requestedExecDate() { return requestedExecDate; }
    public LocalDate valueDate() { return valueDate; }
    /** Falls back to the requested date for a payment that has not been validated yet. */
    public LocalDate executionDate() { return executionDate != null ? executionDate : requestedExecDate; }
    public String routingRuleId() { return routingRuleId; }
    public String rejectionReason() { return rejectionReason; }
    public String rejectionDetail() { return rejectionDetail; }
    public UUID reservationId() { return reservationId; }
    public Long settlementAccountId() { return settlementAccountId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
