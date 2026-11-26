package com.minipay.refdata.internal.web;

import com.minipay.refdata.api.BicEntry;
import com.minipay.refdata.api.CreateRoutingRuleCommand;
import com.minipay.refdata.api.CutoffView;
import com.minipay.refdata.api.HolidayView;
import com.minipay.refdata.api.IbanCheck;
import com.minipay.refdata.api.Idempotent;
import com.minipay.refdata.api.RefDataApi;
import com.minipay.refdata.api.RefDataError;
import com.minipay.refdata.api.RefDataException;
import com.minipay.refdata.api.ReleaseView;
import com.minipay.refdata.api.RouteResolution;
import com.minipay.refdata.api.RoutingMethod;
import com.minipay.refdata.api.RoutingRuleView;
import com.minipay.refdata.api.SchemeView;
import com.minipay.refdata.api.ValueDating;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API of reference data. Every lookup takes an optional {@code date} (ISO yyyy-MM-dd) and
 * defaults to the bank's business date. Ask about any date: the past shows what applied then, the
 * future shows what is already scheduled.
 */
@RestController
@RequestMapping("/api/refdata")
@Tag(name = "Reference data", description = "BIC directory, IBAN registry, routing rules, calendars and cut-offs (effective-dated)")
class RefDataController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final RefDataApi refdata;
    private final Clock clock;
    private final ZoneId bankZone;

    RefDataController(RefDataApi refdata, ObjectProvider<Clock> clock, @Value("${minipay.bank.time-zone:UTC}") ZoneId bankZone) {
        this.refdata = refdata;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.bankZone = bankZone;
    }

    record Summary(String dataVersion, ReleaseView currentRelease, LocalDate date, int institutions, int routingRules) {
    }

    @GetMapping
    @Operation(summary = "Current data version and counts")
    Summary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate on = on(date);
        List<ReleaseView> releases = refdata.releases();
        ReleaseView current = releases.isEmpty() ? null : releases.get(0);
        return new Summary(current == null ? null : current.version(), current, on, refdata.bicDirectory(on).size(),
                refdata.routingRules().size());
    }

    @GetMapping("/bics")
    @Operation(summary = "The BIC directory as of a date")
    List<BicEntry> bics(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return refdata.bicDirectory(on(date));
    }

    @GetMapping("/bics/{bic}")
    @Operation(summary = "One BIC as of a date (404 if it does not exist then)")
    BicEntry bic(@PathVariable String bic,
                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate on = on(date);
        return refdata.findBic(bic, on).orElseThrow(() ->
                new RefDataException(RefDataError.NOT_FOUND, bic + " is not in the BIC directory on " + on));
    }

    @GetMapping("/bics/{bic}/history")
    @Operation(summary = "Every version of a BIC's directory record")
    List<BicEntry> bicHistory(@PathVariable String bic) {
        return refdata.bicHistory(bic);
    }

    @GetMapping("/iban/{iban}")
    @Operation(summary = "Validate an IBAN against the IBAN registry; every check is reported")
    IbanCheck iban(@PathVariable String iban,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return refdata.checkIban(iban, on(date));
    }

    @GetMapping("/schemes")
    List<SchemeView> schemes(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return refdata.schemes(on(date));
    }

    @GetMapping("/clearing-systems")
    @Operation(summary = "The clearing and settlement systems on the network (RTGS, DNS, instant) on a date")
    List<com.minipay.refdata.api.ClearingSystemView> clearingSystems(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return refdata.clearingSystems(on(date));
    }

    @GetMapping("/cutoffs")
    @Operation(summary = "Per currency: the settlement date and cut-off for a payment sent now")
    List<CutoffView> cutoffs() {
        return refdata.cutoffs(clock.instant());
    }

    @GetMapping("/value-date")
    @Operation(summary = "The earliest value date for a payment in a currency, given the earliest acceptable date and the time now")
    ValueDating valueDate(@RequestParam String currency,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return refdata.valueDate(currency.toUpperCase(), on(date), clock.instant());
    }

    @GetMapping("/holidays")
    List<HolidayView> holidays(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = on(from);
        return refdata.holidays(start, to == null ? start.plusYears(1) : to);
    }

    @GetMapping("/routing-rules")
    @Operation(summary = "Every routing rule ever published, including expired and scheduled ones")
    List<RoutingRuleView> rules() {
        return refdata.routingRules();
    }

    @GetMapping("/routes")
    @Operation(summary = "Which rule would route a payment to this creditor agent, in this currency, on this value date")
    RouteResolution route(@RequestParam String bic, @RequestParam String currency,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (!com.minipay.common.id.Bic.isValid(bic.trim().toUpperCase())) {
            throw new IllegalArgumentException(bic + " is not a valid BIC");
        }
        return refdata.route(bic, currency.toUpperCase(), on(date));
    }

    record CreateRuleRequest(String destination, String currency, RoutingMethod method, String nextHopBic,
                             String settlementAccount, Integer priority, LocalDate effectiveFrom, LocalDate effectiveTo,
                             String supersedes, String publishedBy, String approvedBy, String description) {
    }

    @PostMapping("/routing-rules")
    @Operation(summary = "Publish a routing rule as a new release (four-eyes: publisher ≠ approver). "
            + "Name a rule in 'supersedes' to end-date it the day before.")
    ResponseEntity<RoutingRuleView> create(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                           @RequestBody CreateRuleRequest r) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (r.priority() == null) {
            throw new IllegalArgumentException("priority is required");
        }
        Idempotent<RoutingRuleView> result = refdata.createRoutingRule(new CreateRoutingRuleCommand(key, r.destination(),
                r.currency(), r.method(), r.nextHopBic(), r.settlementAccount(), r.priority(), r.effectiveFrom(),
                r.effectiveTo(), r.supersedes(), r.publishedBy(), r.approvedBy(), r.description()));
        return result.replayed()
                ? ResponseEntity.ok().header(REPLAYED, "true").body(result.value())
                : ResponseEntity.status(HttpStatus.CREATED)
                        .location(URI.create("/api/refdata/routing-rules/" + result.value().ruleId())).body(result.value());
    }

    @GetMapping("/releases")
    List<ReleaseView> releases() {
        return refdata.releases();
    }

    private LocalDate on(LocalDate date) {
        return date != null ? date : LocalDate.now(clock.withZone(bankZone));
    }
}
