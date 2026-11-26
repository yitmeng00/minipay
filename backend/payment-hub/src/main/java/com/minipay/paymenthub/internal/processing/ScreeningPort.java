package com.minipay.paymenthub.internal.processing;

import com.minipay.compliance.api.CaseVerdict;
import com.minipay.paymenthub.internal.domain.Payment;
import java.time.LocalDate;

/**
 * Sanctions screening, as seen by the payment hub (ADR-007). Implemented by {@link ComplianceScreening};
 * tests wrap it to inject crashes.
 */
public interface ScreeningPort {

    record Result(boolean hit, String detail) {
    }

    /** Screens the payment once; a repeat returns the first result. @param asOf the lists in force on this date */
    Result screen(Payment payment, LocalDate asOf);

    /** Where the compliance decision on a held payment stands. */
    CaseVerdict verdict(Payment payment);
}
