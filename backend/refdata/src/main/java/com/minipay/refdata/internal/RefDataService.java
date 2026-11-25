package com.minipay.refdata.internal;

import com.minipay.common.id.Bic;
import com.minipay.common.money.CurrencyUnits;
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
import com.minipay.refdata.api.RoutingRuleImport;
import com.minipay.refdata.api.RoutingRuleView;
import com.minipay.refdata.api.SchemeView;
import com.minipay.refdata.api.ValueDating;
import com.minipay.refdata.internal.domain.BusinessCalendar;
import com.minipay.refdata.internal.domain.IbanValidator;
import com.minipay.refdata.internal.domain.RequestFingerprint;
import com.minipay.refdata.internal.domain.RouteSelector;
import com.minipay.refdata.internal.domain.ValueDater;
import com.minipay.refdata.internal.persistence.RefDataRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefDataService implements RefDataApi {

    /** How far ahead calendars are loaded when walking to the next business day. */
    private static final int LOOKAHEAD_DAYS = 40;

    private final RefDataRepository repo;
    private final Clock clock;
    private final ZoneId bankZone;

    public RefDataService(RefDataRepository repo, ObjectProvider<Clock> clock,
                          @Value("${minipay.bank.time-zone:UTC}") ZoneId bankZone) {
        this.repo = repo;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.bankZone = bankZone;
    }

    // ------------------------------------------------------------------ BIC directory

    @Override
    public Optional<BicEntry> findBic(String bic, LocalDate on) {
        if (!Bic.isValid(normalize(bic))) {
            return Optional.empty();
        }
        return repo.findBic(Bic.parse(bic).value(), on);
    }

    @Override
    public List<BicEntry> bicDirectory(LocalDate on) {
        return repo.bicDirectory(on);
    }

    @Override
    public List<BicEntry> bicHistory(String bic) {
        return Bic.isValid(normalize(bic)) ? repo.bicHistory(Bic.parse(bic).value()) : List.of();
    }

    // ------------------------------------------------------------------ IBAN

    @Override
    public IbanCheck checkIban(String iban, LocalDate on) {
        String s = iban == null ? "" : iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        String cc = s.length() >= 2 ? s.substring(0, 2) : "";
        return IbanValidator.validate(iban, cc.isEmpty() ? Optional.empty() : repo.ibanFormat(cc, on),
                (country, bankId) -> repo.bicForBankId(country, bankId, on));
    }

    @Override
    public boolean usesIban(String countryCode, LocalDate on) {
        return repo.ibanFormat(countryCode, on).isPresent();
    }

    // ------------------------------------------------------------------ calendars and cut-offs

    @Override
    public Optional<SchemeView> scheme(String currency, LocalDate on) {
        return repo.scheme(currency, on);
    }

    @Override
    public List<SchemeView> schemes(LocalDate on) {
        return repo.schemes(on);
    }

    @Override
    public boolean isBusinessDay(String currency, LocalDate date) {
        return calendar(requireScheme(currency, date), date).isBusinessDay(date);
    }

    @Override
    public LocalDate nextBusinessDay(String currency, LocalDate from) {
        return calendar(requireScheme(currency, from), from).nextBusinessDay(from);
    }

    @Override
    public ValueDating valueDate(String currency, LocalDate earliest, Instant now) {
        SchemeView scheme = requireScheme(currency, earliest);
        return ValueDater.valueDate(scheme, calendar(scheme, earliest), earliest, now);
    }

    @Override
    public List<CutoffView> cutoffs(Instant now) {
        List<CutoffView> out = new ArrayList<>();
        for (SchemeView s : repo.schemes(now.atZone(bankZone).toLocalDate())) {
            LocalDate schemeToday = now.atZone(ZoneId.of(s.timeZone())).toLocalDate();
            BusinessCalendar cal = calendar(s, schemeToday);
            ValueDating v = ValueDater.valueDate(s, cal, schemeToday, now);
            out.add(new CutoffView(s.currency(), s.schemeName(), s.timeZone(), s.cutoffTime(), v.valueDate(),
                    v.cutoff(), cal.isBusinessDay(schemeToday), cal.whyClosed(schemeToday)));
        }
        return out;
    }

    @Override
    public List<HolidayView> holidays(LocalDate from, LocalDate to) {
        return repo.allHolidays(from, to);
    }

    private SchemeView requireScheme(String currency, LocalDate on) {
        return repo.scheme(currency, on).orElseThrow(() -> new RefDataException(RefDataError.NO_SCHEME,
                "No settlement scheme for " + currency + " on " + on + ": we do not settle this currency"));
    }

    private BusinessCalendar calendar(SchemeView scheme, LocalDate from) {
        return new BusinessCalendar(scheme.calendarCode(),
                repo.holidays(scheme.calendarCode(), from, from.plusDays(LOOKAHEAD_DAYS)));
    }

    // ------------------------------------------------------------------ routing

    @Override
    public RouteResolution route(String creditorAgentBic, String currency, LocalDate valueDate) {
        return RouteSelector.select(repo.rules(currency), creditorAgentBic, currency, valueDate,
                bic -> repo.findBic(bic, valueDate));
    }

    @Override
    public List<RoutingRuleView> routingRules() {
        return repo.rules();
    }

    @Override
    @Transactional
    public Idempotent<RoutingRuleView> createRoutingRule(CreateRoutingRuleCommand c) {
        LocalDate today = LocalDate.now(clock.withZone(bankZone));
        RoutingRuleView draft = validate(c, today);
        String hash = RequestFingerprint.of("create-routing-rule", draft.destination(), draft.currency(), draft.method(),
                draft.nextHopBic(), draft.settlementAccount(), draft.priority(), draft.effectiveFrom(), draft.effectiveTo(),
                c.supersedes(), c.publishedBy(), c.approvedBy(), c.description());

        repo.lockIdempotencyKey(c.idempotencyKey());
        Optional<RefDataRepository.StoredKey> stored = repo.findIdempotencyKey(c.idempotencyKey());
        if (stored.isPresent()) {
            if (!stored.get().requestHash().equals(hash)) {
                throw new RefDataException(RefDataError.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key " + c.idempotencyKey() + " was already used for a different request");
            }
            return new Idempotent<>(repo.rule(stored.get().ruleId()).orElseThrow(), true);
        }

        repo.lockRoutingTable();
        if (c.supersedes() != null) {
            supersede(c.supersedes(), draft);
        }
        String version = repo.nextReleaseVersion(today);
        repo.insertRelease(version, c.publishedBy(), c.approvedBy(), c.description());
        RoutingRuleView rule = new RoutingRuleView(repo.nextRuleId(), draft.destination(), draft.currency(), draft.method(),
                draft.nextHopBic(), draft.settlementAccount(), draft.priority(), draft.effectiveFrom(), draft.effectiveTo(),
                version);
        insert(rule);
        repo.insertIdempotencyKey(c.idempotencyKey(), hash, rule.ruleId());
        return new Idempotent<>(rule, false);
    }

    /** The old rule ends the day before the new one starts: no gap, no overlap. */
    private void supersede(String ruleId, RoutingRuleView next) {
        RoutingRuleView old = repo.rule(ruleId).orElseThrow(() ->
                new RefDataException(RefDataError.NOT_FOUND, "Rule " + ruleId + " does not exist"));
        if (!old.destination().equals(next.destination()) || !old.currency().equals(next.currency())) {
            throw invalid("A rule can only supersede a rule for the same destination and currency ("
                    + old.ruleId() + " is " + old.destination() + " " + old.currency() + ")");
        }
        if (!old.validOn(next.effectiveFrom()) || !old.effectiveFrom().isBefore(next.effectiveFrom())) {
            throw invalid(old.ruleId() + " (" + old.effectiveFrom() + " to " + (old.effectiveTo() == null ? "open" : old.effectiveTo())
                    + ") must already be in force the day before " + next.effectiveFrom());
        }
        repo.endDateRule(old.ruleId(), next.effectiveFrom().minusDays(1));
    }

    @Override
    @Transactional
    public void importRoutingRules(List<RoutingRuleImport> rules) {
        repo.lockRoutingTable();
        for (RoutingRuleImport i : rules) {
            RoutingRuleView rule = new RoutingRuleView(i.ruleId(), i.destination(), i.currency(), i.method(),
                    i.nextHopBic() == null ? null : Bic.parse(i.nextHopBic()).value(), i.settlementAccount(), i.priority(),
                    i.effectiveFrom(), i.effectiveTo(), i.releaseVersion());
            Optional<RoutingRuleView> existing = repo.rule(i.ruleId());
            if (existing.isPresent()) {
                if (!sameRuleOrEndDatedSince(existing.get(), rule)) {
                    throw new IllegalStateException("Rule " + i.ruleId() + " already exists with different content: "
                            + existing.get() + " vs " + rule);
                }
                continue;
            }
            if (!repo.releaseExists(i.releaseVersion())) {
                throw new IllegalStateException("Rule " + i.ruleId() + " refers to unknown release " + i.releaseVersion());
            }
            insert(rule);
        }
    }

    /**
     * The stored rule is the imported one, possibly end-dated since by a later release (superseded).
     * The database then knows more than the file: that is not a conflict. Anything else is.
     */
    private static boolean sameRuleOrEndDatedSince(RoutingRuleView stored, RoutingRuleView imported) {
        if (stored.equals(imported)) {
            return true;
        }
        RoutingRuleView unEnded = new RoutingRuleView(stored.ruleId(), stored.destination(), stored.currency(), stored.method(),
                stored.nextHopBic(), stored.settlementAccount(), stored.priority(), stored.effectiveFrom(),
                imported.effectiveTo(), stored.releaseVersion());
        boolean shortened = stored.effectiveTo() != null
                && (imported.effectiveTo() == null || stored.effectiveTo().isBefore(imported.effectiveTo()));
        return shortened && unEnded.equals(imported);
    }

    private void insert(RoutingRuleView rule) {
        try {
            repo.insertRule(rule);
        } catch (DataIntegrityViolationException e) {
            if ("23P01".equals(sqlState(e))) { // exclusion_violation
                throw new RefDataException(RefDataError.RULE_OVERLAP, "Another rule for " + rule.destination() + " "
                        + rule.currency() + " with priority " + rule.priority() + " is valid on overlapping dates");
            }
            throw e;
        }
    }

    private RoutingRuleView validate(CreateRoutingRuleCommand c, LocalDate today) {
        if (c.idempotencyKey() == null || c.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        String dest = normalize(c.destination());
        if (!dest.matches("^([A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}\\*|\\*)$")) {
            throw invalid("Destination must be an institution's BIC8 followed by * (e.g. MNPBDEFF*) or * for any institution");
        }
        String ccy = normalize(c.currency());
        try {
            CurrencyUnits.currency(ccy);
        } catch (RuntimeException e) {
            throw invalid("Unknown currency " + c.currency());
        }
        if (c.method() == null) {
            throw invalid("Method is required");
        }
        String hop = c.nextHopBic() == null || c.nextHopBic().isBlank() ? null : normalize(c.nextHopBic());
        if (c.method().needsNextHop() != (hop != null)) {
            throw invalid(c.method() + (c.method().needsNextHop() ? " needs a next hop (the correspondent)"
                    : " goes to the creditor agent itself and takes no next hop"));
        }
        if (hop != null && !Bic.isValid(hop)) {
            throw invalid("Next hop " + c.nextHopBic() + " is not a valid BIC");
        }
        if (c.settlementAccount() == null || c.settlementAccount().isBlank() || c.settlementAccount().length() > 34) {
            throw invalid("Settlement account is required (at most 34 characters)");
        }
        if (c.priority() < 1 || c.priority() > 999) {
            throw invalid("Priority must be between 1 and 999");
        }
        if (c.effectiveFrom() == null || c.effectiveFrom().isBefore(today)) {
            // Back-dating would rewrite history: payments already routed on those days used other rules.
            throw invalid("Effective from must be today (" + today + ") or later: reference data is never back-dated");
        }
        if (c.effectiveTo() != null && c.effectiveTo().isBefore(c.effectiveFrom())) {
            throw invalid("Effective to must not be before effective from");
        }
        if (c.publishedBy() == null || c.publishedBy().isBlank() || c.approvedBy() == null || c.approvedBy().isBlank()) {
            throw invalid("Publisher and approver are required");
        }
        if (c.publishedBy().trim().equalsIgnoreCase(c.approvedBy().trim())) {
            throw new RefDataException(RefDataError.FOUR_EYES_VIOLATION,
                    "A change must be approved by someone other than its author (four-eyes principle)");
        }
        if (c.description() == null || c.description().isBlank() || c.description().length() > 500) {
            throw invalid("Description is required (at most 500 characters)");
        }
        if (hop != null && repo.findBic(Bic.parse(hop).value(), c.effectiveFrom()).isEmpty()) {
            throw invalid("Next hop " + hop + " is not in the BIC directory on " + c.effectiveFrom());
        }
        return new RoutingRuleView(null, dest, ccy, c.method(), hop == null ? null : Bic.parse(hop).value(),
                c.settlementAccount().trim(), c.priority(), c.effectiveFrom(), c.effectiveTo(), null);
    }

    // ------------------------------------------------------------------ releases

    @Override
    public List<ReleaseView> releases() {
        return repo.releases();
    }

    // ------------------------------------------------------------------ helpers

    private static RefDataException invalid(String message) {
        return new RefDataException(RefDataError.INVALID_RULE, message);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
