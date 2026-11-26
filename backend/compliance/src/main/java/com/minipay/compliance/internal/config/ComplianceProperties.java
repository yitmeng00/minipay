package com.minipay.compliance.internal.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code minipay.compliance.*}
 *
 * @param holdThreshold     a score at or above this holds the payment (default 75)
 * @param fourEyesThreshold a case whose best score is at or above this needs a second approver to release (default 90)
 * @param sla               how long a held payment may wait for a decision (default 2 h)
 * @param lists             list codes this bank screens against; empty = all
 */
@ConfigurationProperties("minipay.compliance")
public record ComplianceProperties(Integer holdThreshold, Integer fourEyesThreshold, Duration sla, List<String> lists) {

    public ComplianceProperties {
        holdThreshold = holdThreshold == null ? 75 : holdThreshold;
        fourEyesThreshold = fourEyesThreshold == null ? 90 : fourEyesThreshold;
        sla = sla == null ? Duration.ofHours(2) : sla;
        lists = lists == null ? List.of() : List.copyOf(lists);
        if (holdThreshold < 1 || holdThreshold > 100 || fourEyesThreshold < holdThreshold || fourEyesThreshold > 101) {
            throw new IllegalArgumentException("Need 1 ≤ hold-threshold ≤ four-eyes-threshold ≤ 101");
        }
    }
}
