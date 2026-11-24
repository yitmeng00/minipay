package com.minipay.paymenthub.internal.processing;

import com.minipay.paymenthub.api.PaymentEvents;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.internal.domain.Payment;
import com.minipay.paymenthub.internal.domain.PaymentStateHistory;
import com.minipay.paymenthub.internal.outbox.KafkaTopicsConfiguration;
import com.minipay.paymenthub.internal.outbox.OutboxWriter;
import com.minipay.paymenthub.internal.persistence.PaymentRepository;
import com.minipay.paymenthub.internal.persistence.PaymentStateHistoryRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Performs a status change the only correct way: in ONE transaction,
 * <ol>
 *   <li>re-load the payment and check it is still in the status the caller expected,</li>
 *   <li>apply the caller's changes and the transition (validated by the state machine),</li>
 *   <li>append the history row,</li>
 *   <li>append the outbox event.</li>
 * </ol>
 * If any step fails, none of them happened. If another thread moved the payment first (status no
 * longer as expected, or an optimistic-lock conflict on {@code version}), nothing is written and the
 * caller is told it lost the race.
 */
@Component
public class PaymentTransitions {

    private final PaymentRepository payments;
    private final PaymentStateHistoryRepository history;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String bankBic;
    private final String topic;

    public PaymentTransitions(PaymentRepository payments, PaymentStateHistoryRepository history, OutboxWriter outbox,
                              PlatformTransactionManager txManager, ObjectProvider<Clock> clock,
                              @Value("${minipay.bank.bic}") String bankBic) {
        this.payments = payments;
        this.history = history;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.bankBic = bankBic;
        this.topic = KafkaTopicsConfiguration.paymentEventsTopic(bankBic);
    }

    /** What a step wants to do once it holds the payment inside the transaction. */
    public record Change(PaymentStatus to, String reasonCode, String detail, String actor) {
    }

    /**
     * @param expected status the caller based its work on
     * @param mutate   applies field changes and returns the transition to make
     * @return true if this caller made the transition; false if another worker got there first
     */
    public boolean transition(UUID paymentId, PaymentStatus expected, Function<Payment, Change> mutate) {
        try {
            Boolean done = tx.execute(s -> {
                Payment p = payments.findById(paymentId).orElseThrow();
                if (p.status() != expected) {
                    return false;
                }
                Change change = mutate.apply(p);
                record(p, change);
                payments.saveAndFlush(p); // flush now: a version conflict surfaces here, inside the transaction
                return true;
            });
            return Boolean.TRUE.equals(done);
        } catch (ObjectOptimisticLockingFailureException e) {
            return false;
        }
    }

    /** For a brand-new payment: record its initial RECEIVED state. Caller's transaction. */
    public void recordReceived(Payment p, String detail, String actor) {
        history.save(new PaymentStateHistory(p.id(), null, PaymentStatus.RECEIVED, null, detail, actor));
        publish(p, null, PaymentStatus.RECEIVED, null);
    }

    private void record(Payment p, Change change) {
        PaymentStatus from = p.transitionTo(change.to(), clock.instant());
        history.save(new PaymentStateHistory(p.id(), from, change.to(), change.reasonCode(), change.detail(), change.actor()));
        publish(p, from, change.to(), change.reasonCode());
    }

    private void publish(Payment p, PaymentStatus from, PaymentStatus to, String reasonCode) {
        UUID eventId = UUID.randomUUID();
        outbox.append(eventId, "payment", p.id(), PaymentEvents.STATUS_CHANGED, topic, p.uetr().toString(),
                new PaymentEvents.StatusChanged(eventId, PaymentEvents.STATUS_CHANGED, bankBic, from, to, reasonCode,
                        clock.instant(), PaymentMapper.toView(p)));
    }
}
