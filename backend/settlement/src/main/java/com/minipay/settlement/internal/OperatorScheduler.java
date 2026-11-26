package com.minipay.settlement.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** The systems' clocks, only on the operator: instant timeouts, DNS cycle closes, the RTGS cut-off, queue retries. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnExpression("${minipay.settlement.operator:false} and ${minipay.settlement.timers:true}")
class OperatorScheduler {

    private static final Logger log = LoggerFactory.getLogger(OperatorScheduler.class);

    private final OperatorService operator;

    OperatorScheduler(OperatorService operator) {
        this.operator = operator;
    }

    /** Instant payments are measured in seconds: the timer runs four times a second. */
    @Scheduled(fixedDelayString = "${minipay.settlement.instant-timer:PT0.25S}")
    void instantTimers() {
        operator.instantTimers();
    }

    @Scheduled(fixedDelayString = "${minipay.settlement.cycle-timer:PT5S}")
    void cycles() {
        try {
            operator.closeDueCycles();
            int rejected = operator.endOfDay();
            if (rejected > 0) {
                log.info("T2 cut-off: {} queued payments rejected", rejected);
            }
        } catch (RuntimeException e) {
            log.warn("Cycle/cut-off timer failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${minipay.settlement.queue-timer:PT10S}")
    void queues() {
        try {
            operator.retryQueues();
        } catch (RuntimeException e) {
            log.warn("Queue retry failed: {}", e.getMessage());
        }
    }
}
