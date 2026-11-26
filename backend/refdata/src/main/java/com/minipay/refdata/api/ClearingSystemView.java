package com.minipay.refdata.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * A clearing or settlement system run by a third party on the network (ADR-008).
 *
 * @param code           what the pacs.008 names in SttlmInf/ClrSys/Prtry, e.g. {@code T2}
 * @param kind           RTGS, DNS or INSTANT: the routing method it serves
 * @param operatorBic    who runs it: our message goes there (AppHdr/To), the creditor's bank stays InstdAgt
 * @param settlesIn      DNS: the RTGS on which the net positions settle; null otherwise
 * @param timeZone       the system's own time zone; cut-off and cycle times are wall-clock time there
 * @param cutoffTime     RTGS: queued payments not settled by then are rejected; null otherwise
 * @param cycleTimes     DNS: when each cycle closes and settles; empty otherwise
 * @param timeoutSeconds INSTANT: how long the creditor's bank has to answer; null otherwise
 * @param validTo        inclusive, or null if open-ended
 */
public record ClearingSystemView(
        String code,
        String name,
        RoutingMethod kind,
        String currency,
        String operatorBic,
        String settlesIn,
        String timeZone,
        LocalTime cutoffTime,
        List<LocalTime> cycleTimes,
        Integer timeoutSeconds,
        LocalDate validFrom,
        LocalDate validTo,
        String releaseVersion) {

    public ClearingSystemView {
        cycleTimes = cycleTimes == null ? List.of() : List.copyOf(cycleTimes);
    }
}
