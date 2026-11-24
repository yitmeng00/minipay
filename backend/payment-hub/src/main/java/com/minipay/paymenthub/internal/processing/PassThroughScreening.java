package com.minipay.paymenthub.internal.processing;

import com.minipay.paymenthub.internal.domain.Payment;
import org.springframework.stereotype.Component;

/**
 * Placeholder until the compliance module (M6) replaces it. It says so in the audit trail rather than
 * pretending the payment was screened: an auditor reading the history must see that no screening
 * took place.
 */
@Component
public class PassThroughScreening implements ScreeningPort {

    @Override
    public Result screen(Payment payment) {
        return new Result(false, "No screening engine configured (compliance arrives in M6) — passed unscreened");
    }
}
