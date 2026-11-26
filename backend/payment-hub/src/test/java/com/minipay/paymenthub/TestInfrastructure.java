package com.minipay.paymenthub;

import com.minipay.paymenthub.internal.domain.Payment;
import com.minipay.compliance.api.CaseVerdict;
import com.minipay.paymenthub.internal.processing.ComplianceScreening;
import com.minipay.paymenthub.internal.processing.ScreeningPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** Real PostgreSQL and real Kafka (KRaft), plus a controllable clock and screening. */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine").withCommand("postgres", "-c", "max_connections=200");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.0.0");
    }

    @Bean
    @Primary
    MutableClock clock() {
        return new MutableClock(Instant.parse("2026-11-24T02:00:00Z")); // 10:00 in Kuala Lumpur
    }

    @Bean
    @Primary
    ControllableScreening screening(ComplianceScreening real) {
        return new ControllableScreening(real);
    }

    /** A clock tests can move forward, e.g. to make a warehoused payment's date arrive. */
    public static class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        public void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /**
     * The real screening (the compliance module with the seeded lists), unless a test tells it to crash,
     * simulating a process dying mid-step.
     */
    public static class ControllableScreening implements ScreeningPort {
        private final ScreeningPort real;
        private volatile boolean crash;
        private volatile boolean crashVerdict;

        ControllableScreening(ScreeningPort real) {
            this.real = real;
        }

        public void crash(boolean crash) {
            this.crash = crash;
        }

        /** Asking compliance for its decision fails (the payment cannot be resumed until it works again). */
        public void crashVerdict(boolean crash) {
            this.crashVerdict = crash;
        }

        @Override
        public Result screen(Payment payment, LocalDate asOf) {
            if (crash) {
                throw new IllegalStateException("Simulated crash during screening");
            }
            return real.screen(payment, asOf);
        }

        @Override
        public CaseVerdict verdict(Payment payment) {
            if (crashVerdict) {
                throw new IllegalStateException("Simulated crash while resuming a held payment");
            }
            return real.verdict(payment);
        }
    }
}
