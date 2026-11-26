package com.minipay.paymenthub.internal.processing;

import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.internal.config.PaymentHubProperties;
import com.minipay.paymenthub.internal.persistence.PaymentRepository;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Crash recovery for the pipeline. Finds payments that are stuck before SENT — their processing thread
 * died (crash, deploy, timeout) — and payments warehoused until a date that has now arrived, and runs
 * the processor on them again. Re-running is safe because every step is idempotent (see
 * {@link PaymentProcessor}).
 */
@Component
public class RecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(RecoverySweeper.class);
    /** Before the payment left us, plus incoming payments waiting for their cover (re-checked when stale). */
    private static final EnumSet<PaymentStatus> IN_FLIGHT = EnumSet.of(
            PaymentStatus.RECEIVED, PaymentStatus.VALIDATED, PaymentStatus.SCREENED, PaymentStatus.FUNDS_RESERVED,
            PaymentStatus.AWAITING_COVER);

    private final PaymentRepository payments;
    private final PaymentProcessor processor;
    private final PaymentHubProperties props;
    private final Clock clock;
    private final boolean scheduled;

    public RecoverySweeper(PaymentRepository payments, PaymentProcessor processor, PaymentHubProperties props,
                           ObjectProvider<Clock> clock,
                           @Value("${minipay.payments.recovery.enabled:true}") boolean scheduled) {
        this.scheduled = scheduled;
        this.payments = payments;
        this.processor = processor;
        this.props = props;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Scheduled(fixedDelayString = "${minipay.payments.recovery.interval:PT10S}")
    public void sweep() {
        if (scheduled) {
            resumeStuckPayments();
        }
    }

    /** @return how many payments were looked at */
    public int resumeStuckPayments() {
        List<UUID> ids = payments.findResumable(IN_FLIGHT, PaymentStatus.FUNDS_RESERVED,
                clock.instant().minus(props.recoveryStaleAfter()), processor.businessDate(), PageRequest.of(0, 100));
        for (UUID id : ids) {
            try {
                processor.process(id);
            } catch (RuntimeException e) {
                log.warn("Recovery of payment {} failed, will retry: {}", id, e.toString());
            }
        }
        return ids.size();
    }
}
