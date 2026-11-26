package com.minipay.recon.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** End of day, as a servicer: yesterday's statements go out once the day is over in our time zone. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "minipay.recon.auto-publish", havingValue = "true", matchIfMissing = true)
class ReconScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconScheduler.class);

    private final StatementPublisher publisher;
    private final Clock clock;
    private final ZoneId zone;

    ReconScheduler(StatementPublisher publisher, ObjectProvider<Clock> clock, @Value("${minipay.bank.time-zone:UTC}") ZoneId zone) {
        this.publisher = publisher;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.zone = zone;
    }

    /** Every few minutes; once per account and date (the statement's key), so a restart cannot send one twice. */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${minipay.recon.publish-interval:PT5M}")
    void endOfDay() {
        try {
            publisher.publish(LocalDate.now(clock.withZone(zone)).minusDays(1));
        } catch (RuntimeException e) {
            log.warn("End-of-day statements failed: {}", e.getMessage());
        }
    }
}
