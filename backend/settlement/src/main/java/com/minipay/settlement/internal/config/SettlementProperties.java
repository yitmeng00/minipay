package com.minipay.settlement.internal.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code minipay.settlement}: whether this instance is the operator of the clearing systems, and who takes part.
 *
 * @param operator              true only on the simulated central bank (profile {@code operator})
 * @param participants          the banks with accounts at the central bank. Each has an RTGS account
 *                              ({@code RTGS-<BIC8>}) and an instant account ({@code INST-<BIC8>}) on the operator's
 *                              ledger
 * @param simulatedAnswerDelay  how long a participant the operator plays takes to accept an instant payment
 */
@ConfigurationProperties("minipay.settlement")
public record SettlementProperties(boolean operator, List<Participant> participants, Duration simulatedAnswerDelay) {

    public SettlementProperties {
        participants = participants == null ? List.of() : List.copyOf(participants);
        simulatedAnswerDelay = simulatedAnswerDelay == null ? Duration.ofMillis(1500) : simulatedAnswerDelay;
    }

    /** @param simulated a bank MiniPay does not run: the operator plays it (it answers instant payments itself) */
    public record Participant(String bic, String name, boolean simulated) {
    }

    public static String rtgsAccount(String bic) {
        return "RTGS-" + bic.substring(0, 8);
    }

    public static String instantAccount(String bic) {
        return "INST-" + bic.substring(0, 8);
    }
}
