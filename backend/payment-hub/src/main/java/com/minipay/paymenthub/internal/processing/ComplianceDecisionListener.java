package com.minipay.paymenthub.internal.processing;

import com.minipay.compliance.api.ComplianceDecisionMade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A compliance decision closed a case: resume the payment now rather than at the next sweep. The event
 * arrives after the decision was committed and outside any transaction, so the pipeline runs its own
 * transactions as always. If this fails, or the process dies first, the recovery sweeper resumes the
 * payment from the committed decision.
 */
@Component
class ComplianceDecisionListener {

    private static final Logger log = LoggerFactory.getLogger(ComplianceDecisionListener.class);

    private final PaymentProcessor processor;

    ComplianceDecisionListener(PaymentProcessor processor) {
        this.processor = processor;
    }

    @EventListener
    void on(ComplianceDecisionMade decision) {
        try {
            processor.process(decision.paymentId());
        } catch (RuntimeException e) {
            log.warn("Payment {} not resumed after compliance decision ({}); the recovery sweeper will retry: {}",
                    decision.paymentId(), decision.status(), e.toString());
        }
    }
}
