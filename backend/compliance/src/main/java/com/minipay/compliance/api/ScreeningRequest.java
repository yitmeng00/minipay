package com.minipay.compliance.api;

import com.minipay.common.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A payment to screen. Screening is idempotent per payment: a second request for the same payment
 * returns the first screening (a re-run of the pipeline step after a crash), whatever it carries.
 *
 * @param direction OUTGOING or INCOMING, as the payment hub sees it
 * @param kind      CUSTOMER, or COVER for a pacs.009 COV (whose parties are the underlying customer's)
 * @param route     the banks on the way, for the analyst, e.g. {@code MNPAMYKLXXX → MNPBDEFFXXX}
 * @param asOf      screen against the lists in force on this date (the bank's business date)
 */
public record ScreeningRequest(
        UUID paymentId,
        UUID uetr,
        String direction,
        String kind,
        Money amount,
        String debtorName,
        String creditorName,
        String route,
        List<ScreenedParty> parties,
        LocalDate asOf) {
}
