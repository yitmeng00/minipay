package com.minipay.refdata.internal.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.refdata.api.BicEntry;
import com.minipay.refdata.api.Connectivity;
import com.minipay.refdata.api.HolidayView;
import com.minipay.refdata.api.ReleaseView;
import com.minipay.refdata.api.RoutingMethod;
import com.minipay.refdata.api.RoutingRuleView;
import com.minipay.refdata.api.SchemeView;
import com.minipay.refdata.api.ClearingSystemView;
import com.minipay.refdata.internal.domain.IbanFormat;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the {@code refdata} schema.
 *
 * <p>Validity is a PostgreSQL {@code daterange} {@code [from, to)}: {@code valid @> :date} asks "is
 * this row in force on that date?", and the EXCLUDE constraints guarantee at most one such row per
 * key. The API exposes {@code validTo} as the last day <em>inclusive</em> (upper bound − 1), which is
 * how people read "effective to"; the conversion happens only here.
 */
@Repository
public class RefDataRepository {

    private static final TypeReference<List<IbanFormat.BbanPart>> PARTS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RefDataRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ BIC directory

    private static final String BIC_COLUMNS = """
            bic, institution_name, country_code, city, connectivity, currencies,
            lower(valid) AS valid_from, upper(valid) AS valid_upper, release_version
            """;

    public Optional<BicEntry> findBic(String bic11, LocalDate on) {
        return jdbc.sql("SELECT " + BIC_COLUMNS + " FROM refdata.bic_directory WHERE bic = :bic AND valid @> :on")
                .param("bic", bic11).param("on", on)
                .query((rs, n) -> bic(rs)).optional();
    }

    public List<BicEntry> bicDirectory(LocalDate on) {
        return jdbc.sql("SELECT " + BIC_COLUMNS + " FROM refdata.bic_directory WHERE valid @> :on ORDER BY country_code, bic")
                .param("on", on).query((rs, n) -> bic(rs)).list();
    }

    public List<BicEntry> bicHistory(String bic11) {
        return jdbc.sql("SELECT " + BIC_COLUMNS + " FROM refdata.bic_directory WHERE bic = :bic ORDER BY lower(valid)")
                .param("bic", bic11).query((rs, n) -> bic(rs)).list();
    }

    private static BicEntry bic(ResultSet rs) throws SQLException {
        Array ccys = rs.getArray("currencies");
        List<String> currencies = ccys == null ? List.of()
                : Arrays.stream((Object[]) ccys.getArray()).map(o -> o.toString().trim()).toList();
        return new BicEntry(rs.getString("bic"), rs.getString("institution_name"), rs.getString("country_code"),
                rs.getString("city"), Connectivity.valueOf(rs.getString("connectivity")), currencies,
                rs.getObject("valid_from", LocalDate.class), lastDay(rs), rs.getString("release_version"));
    }

    // ------------------------------------------------------------------ IBAN

    public Optional<IbanFormat> ibanFormat(String countryCode, LocalDate on) {
        return jdbc.sql("SELECT country_code, iban_length, bban_parts::text AS parts FROM refdata.iban_format "
                        + "WHERE country_code = :cc AND valid @> :on")
                .param("cc", countryCode).param("on", on)
                .query((rs, n) -> new IbanFormat(rs.getString("country_code"), rs.getInt("iban_length"), parts(rs.getString("parts"))))
                .optional();
    }

    public Optional<String> bicForBankId(String countryCode, String bankId, LocalDate on) {
        return jdbc.sql("SELECT bic FROM refdata.iban_bank_id WHERE country_code = :cc AND bank_id = :id AND valid @> :on")
                .param("cc", countryCode).param("id", bankId).param("on", on)
                .query(String.class).optional();
    }

    private List<IbanFormat.BbanPart> parts(String text) {
        try {
            return json.readValue(text, PARTS);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable bban_parts: " + text, e);
        }
    }

    // ------------------------------------------------------------------ schemes and holidays

    private static final String SCHEME_COLUMNS = """
            currency, scheme_name, calendar_code, time_zone, cutoff_time,
            lower(valid) AS valid_from, upper(valid) AS valid_upper, release_version
            """;

    public Optional<SchemeView> scheme(String currency, LocalDate on) {
        return jdbc.sql("SELECT " + SCHEME_COLUMNS + " FROM refdata.settlement_scheme WHERE currency = :ccy AND valid @> :on")
                .param("ccy", currency).param("on", on).query((rs, n) -> scheme(rs)).optional();
    }

    public List<SchemeView> schemes(LocalDate on) {
        return jdbc.sql("SELECT " + SCHEME_COLUMNS + " FROM refdata.settlement_scheme WHERE valid @> :on ORDER BY currency")
                .param("on", on).query((rs, n) -> scheme(rs)).list();
    }

    private static SchemeView scheme(ResultSet rs) throws SQLException {
        return new SchemeView(rs.getString("currency"), rs.getString("scheme_name"), rs.getString("calendar_code"),
                rs.getString("time_zone"), rs.getObject("cutoff_time", LocalTime.class),
                rs.getObject("valid_from", LocalDate.class), lastDay(rs), rs.getString("release_version"));
    }

    // ------------------------------------------------------------------ clearing systems

    private static final String SYSTEM_COLUMNS = """
            code, name, kind, currency, operator_bic, settles_in, time_zone, cutoff_time, cycle_times::text AS cycles,
            timeout_seconds, lower(valid) AS valid_from, upper(valid) AS valid_upper, release_version
            """;

    public List<ClearingSystemView> clearingSystems(LocalDate on) {
        return jdbc.sql("SELECT " + SYSTEM_COLUMNS + " FROM refdata.clearing_system WHERE valid @> :on ORDER BY currency, kind")
                .param("on", on).query((rs, n) -> clearingSystem(rs)).list();
    }

    private static ClearingSystemView clearingSystem(ResultSet rs) throws SQLException {
        String cycles = rs.getString("cycles");
        List<LocalTime> times = cycles == null ? List.of() : java.util.Arrays.stream(cycles.replaceAll("[{}\\s\"]", "").split(","))
                .filter(t -> !t.isEmpty()).map(LocalTime::parse).toList();
        Number timeout = (Number) rs.getObject("timeout_seconds");
        return new ClearingSystemView(rs.getString("code"), rs.getString("name"),
                com.minipay.refdata.api.RoutingMethod.valueOf(rs.getString("kind")), rs.getString("currency"),
                rs.getString("operator_bic"), rs.getString("settles_in"), rs.getString("time_zone"),
                rs.getObject("cutoff_time", LocalTime.class), times, timeout == null ? null : timeout.intValue(),
                rs.getObject("valid_from", LocalDate.class), lastDay(rs), rs.getString("release_version"));
    }

    /** Holiday date → name for one calendar, in [from, to]. */
    public Map<LocalDate, String> holidays(String calendarCode, LocalDate from, LocalDate to) {
        Map<LocalDate, String> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT holiday_date, name FROM refdata.holiday
                         WHERE calendar_code = :cal AND holiday_date BETWEEN :from AND :to ORDER BY holiday_date
                        """)
                .param("cal", calendarCode).param("from", from).param("to", to)
                .query(rs -> {
                    out.put(rs.getObject("holiday_date", LocalDate.class), rs.getString("name"));
                });
        return out;
    }

    public List<HolidayView> allHolidays(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT calendar_code, holiday_date, name, release_version FROM refdata.holiday
                         WHERE holiday_date BETWEEN :from AND :to ORDER BY holiday_date, calendar_code
                        """)
                .param("from", from).param("to", to)
                .query((rs, n) -> new HolidayView(rs.getString("calendar_code"), rs.getObject("holiday_date", LocalDate.class),
                        rs.getString("name"), rs.getString("release_version")))
                .list();
    }

    // ------------------------------------------------------------------ routing rules

    private static final String RULE_COLUMNS = """
            rule_id, destination, currency, method, next_hop_bic, settlement_account, priority,
            lower(valid) AS valid_from, upper(valid) AS valid_upper, release_version
            """;

    public List<RoutingRuleView> rules() {
        return jdbc.sql("SELECT " + RULE_COLUMNS + " FROM refdata.routing_rule ORDER BY rule_id")
                .query((rs, n) -> rule(rs)).list();
    }

    public List<RoutingRuleView> rules(String currency) {
        return jdbc.sql("SELECT " + RULE_COLUMNS + " FROM refdata.routing_rule WHERE currency = :ccy ORDER BY rule_id")
                .param("ccy", currency).query((rs, n) -> rule(rs)).list();
    }

    public Optional<RoutingRuleView> rule(String ruleId) {
        return jdbc.sql("SELECT " + RULE_COLUMNS + " FROM refdata.routing_rule WHERE rule_id = :id")
                .param("id", ruleId).query((rs, n) -> rule(rs)).optional();
    }

    private static RoutingRuleView rule(ResultSet rs) throws SQLException {
        return new RoutingRuleView(rs.getString("rule_id"), rs.getString("destination"), rs.getString("currency"),
                RoutingMethod.valueOf(rs.getString("method")), rs.getString("next_hop_bic"),
                rs.getString("settlement_account"), rs.getInt("priority"),
                rs.getObject("valid_from", LocalDate.class), lastDay(rs), rs.getString("release_version"));
    }

    /** Effective-to is inclusive: [from, to) means the last day is to − 1. */
    public void insertRule(RoutingRuleView r) {
        jdbc.sql("""
                        INSERT INTO refdata.routing_rule (rule_id, destination, currency, method, next_hop_bic,
                                                          settlement_account, priority, valid, release_version)
                        VALUES (:id, :dest, :ccy, :method, :hop, :acct, :prio, daterange(:from, :upper, '[)'), :rel)
                        """)
                .param("id", r.ruleId()).param("dest", r.destination()).param("ccy", r.currency())
                .param("method", r.method().name()).param("hop", r.nextHopBic()).param("acct", r.settlementAccount())
                .param("prio", r.priority()).param("from", Date.valueOf(r.effectiveFrom()))
                .param("upper", r.effectiveTo() == null ? null : Date.valueOf(r.effectiveTo().plusDays(1)))
                .param("rel", r.releaseVersion())
                .update();
    }

    /** End-date a rule: it applies up to and including {@code lastDay}. The trigger allows only shortening. */
    public void endDateRule(String ruleId, LocalDate lastDay) {
        jdbc.sql("UPDATE refdata.routing_rule SET valid = daterange(lower(valid), :upper, '[)') WHERE rule_id = :id")
                .param("id", ruleId).param("upper", Date.valueOf(lastDay.plusDays(1))).update();
    }

    /**
     * Serialises rule publication: id and release-number allocation read the current maximum, so two
     * publishers at once must take turns. SHARE ROW EXCLUSIVE conflicts with itself but not with readers.
     */
    public void lockRoutingTable() {
        jdbc.sql("LOCK TABLE refdata.routing_rule IN SHARE ROW EXCLUSIVE MODE").update();
    }

    public String nextRuleId() {
        int next = jdbc.sql("SELECT coalesce(max(substr(rule_id, 3)::int), 0) + 1 FROM refdata.routing_rule")
                .query(Integer.class).single();
        return String.format("R-%03d", next);
    }

    // ------------------------------------------------------------------ releases

    public List<ReleaseView> releases() {
        return jdbc.sql("""
                        SELECT r.version, r.published_at, r.published_by, r.approved_by, r.description,
                               (SELECT count(*) FROM refdata.routing_rule rr WHERE rr.release_version = r.version) AS rules
                          FROM refdata.release r ORDER BY r.published_at DESC, r.version DESC
                        """)
                .query((rs, n) -> new ReleaseView(rs.getString("version"), rs.getTimestamp("published_at").toInstant(),
                        rs.getString("published_by"), rs.getString("approved_by"), rs.getString("description"),
                        rs.getInt("rules")))
                .list();
    }

    public boolean releaseExists(String version) {
        return jdbc.sql("SELECT count(*) FROM refdata.release WHERE version = :v").param("v", version)
                .query(Long.class).single() > 0;
    }

    /** Next free version in the month: v2026.11.3 → v2026.11.4. */
    public String nextReleaseVersion(LocalDate today) {
        String prefix = String.format("v%04d.%02d.", today.getYear(), today.getMonthValue());
        int next = jdbc.sql("""
                        SELECT coalesce(max(substr(version, length(:p) + 1)::int), 0) + 1
                          FROM refdata.release WHERE version LIKE :p || '%'
                        """)
                .param("p", prefix).query(Integer.class).single();
        return prefix + next;
    }

    public void insertRelease(String version, String publishedBy, String approvedBy, String description) {
        jdbc.sql("""
                        INSERT INTO refdata.release (version, published_by, approved_by, description)
                        VALUES (:v, :by, :approver, :d)
                        """)
                .param("v", version).param("by", publishedBy).param("approver", approvedBy).param("d", description)
                .update();
    }

    // ------------------------------------------------------------------ idempotency

    /** Concurrent requests with the same key take turns (transaction-scoped advisory lock). */
    public void lockIdempotencyKey(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").param("k", "refdata:" + key).query().singleRow();
    }

    public record StoredKey(String requestHash, String ruleId) {
    }

    public Optional<StoredKey> findIdempotencyKey(String key) {
        return jdbc.sql("SELECT request_hash, rule_id FROM refdata.idempotency_key WHERE idempotency_key = :k")
                .param("k", key).query((rs, n) -> new StoredKey(rs.getString(1), rs.getString(2))).optional();
    }

    public void insertIdempotencyKey(String key, String hash, String ruleId) {
        jdbc.sql("INSERT INTO refdata.idempotency_key (idempotency_key, request_hash, rule_id) VALUES (:k, :h, :r)")
                .param("k", key).param("h", hash).param("r", ruleId).update();
    }

    // ------------------------------------------------------------------ helpers

    private static LocalDate lastDay(ResultSet rs) throws SQLException {
        LocalDate upper = rs.getObject("valid_upper", LocalDate.class);
        return upper == null ? null : upper.minusDays(1);
    }
}
