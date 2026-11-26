package com.minipay.refdata.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reference data, always asked "as of" a date (rule 9): the answer for a past date is what was true
 * then, and the answer for a future date is what is already scheduled to be true.
 */
public interface RefDataApi {

    // ------------------------------------------------------------------ BIC directory

    /** The directory record for an exact BIC (BIC8 means the head office, {@code XXX}) on a date. */
    Optional<BicEntry> findBic(String bic, LocalDate on);

    /** All institutions in the directory on a date. */
    List<BicEntry> bicDirectory(LocalDate on);

    /** Every version of one BIC's record, oldest first. */
    List<BicEntry> bicHistory(String bic);

    // ------------------------------------------------------------------ IBAN

    IbanCheck checkIban(String iban, LocalDate on);

    /** Does this country use IBANs (is it in the IBAN registry) on that date? */
    boolean usesIban(String countryCode, LocalDate on);

    // ------------------------------------------------------------------ calendars and cut-offs

    Optional<SchemeView> scheme(String currency, LocalDate on);

    List<SchemeView> schemes(LocalDate on);

    boolean isBusinessDay(String currency, LocalDate date);

    /** The first business day on or after {@code from} (the "following" convention). */
    LocalDate nextBusinessDay(String currency, LocalDate from);

    /** The first business day on or after {@code earliest} whose cut-off is still ahead of {@code now}. */
    ValueDating valueDate(String currency, LocalDate earliest, Instant now);

    /** For each scheme: the settlement date and cut-off for a payment sent at {@code now}. */
    List<CutoffView> cutoffs(Instant now);

    List<HolidayView> holidays(LocalDate from, LocalDate to);

    // ------------------------------------------------------------------ clearing systems

    /** The system of this kind for a currency on a date (one at most). */
    Optional<ClearingSystemView> clearingSystem(RoutingMethod kind, String currency, LocalDate on);

    Optional<ClearingSystemView> clearingSystem(String code, LocalDate on);

    List<ClearingSystemView> clearingSystems(LocalDate on);

    // ------------------------------------------------------------------ routing

    RouteResolution route(String creditorAgentBic, String currency, LocalDate valueDate);

    /**
     * Routing restricted to some methods: an instant payment may only go by INSTANT, an urgent one never by
     * DNS (ADR-008). Rules of other methods are not candidates at all.
     */
    RouteResolution route(String creditorAgentBic, String currency, LocalDate valueDate, Set<RoutingMethod> allowed);

    /** Every rule ever published, including expired and scheduled ones. */
    List<RoutingRuleView> routingRules();

    Idempotent<RoutingRuleView> createRoutingRule(CreateRoutingRuleCommand command);

    /** Load rules from a reference data file (seeding). Idempotent per rule id. */
    void importRoutingRules(List<RoutingRuleImport> rules);

    // ------------------------------------------------------------------ releases

    /** Newest first. The first one is the current data version. */
    List<ReleaseView> releases();
}
