package com.minipay.refdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.refdata.api.BicEntry;
import com.minipay.refdata.api.Connectivity;
import com.minipay.refdata.api.CreateRoutingRuleCommand;
import com.minipay.refdata.api.CutoffView;
import com.minipay.refdata.api.IbanCheck;
import com.minipay.refdata.api.Idempotent;
import com.minipay.refdata.api.RefDataApi;
import com.minipay.refdata.api.RefDataError;
import com.minipay.refdata.api.RefDataException;
import com.minipay.refdata.api.RouteResolution;
import com.minipay.refdata.api.RoutingMethod;
import com.minipay.refdata.api.RoutingRuleImport;
import com.minipay.refdata.api.RoutingRuleView;
import com.minipay.refdata.api.ValueDating;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reference data against real PostgreSQL. Reference data is append-only like the ledger, so tests
 * never clean up: each one publishes rules for its own, randomly named destination.
 */
@SpringBootTest(classes = RefDataTestApplication.class)
class RefDataIT {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    @Autowired
    RefDataApi refdata;

    @Autowired
    JdbcClient jdbc;

    // ------------------------------------------------------------------ BIC directory

    @Test
    void the_directory_answers_as_of_a_date() {
        // Renamed on 2026-10-02: both versions are kept, and each date sees its own.
        assertThat(refdata.findBic("MNPBDEFF100", LocalDate.of(2026, 10, 1))).get()
                .extracting(BicEntry::institutionName).isEqualTo("MiniPay Bank Deutschland AG - Filiale Berlin");
        assertThat(refdata.findBic("MNPBDEFF100", LocalDate.of(2026, 10, 2))).get()
                .extracting(BicEntry::institutionName).isEqualTo("MiniPay Bank Deutschland AG - Berlin");
        assertThat(refdata.bicHistory("MNPBDEFF100")).hasSize(2);

        // Scheduled to leave the directory: present on 31 Dec, gone on 1 Jan.
        assertThat(refdata.findBic("MNPXNL2AXXX", LocalDate.of(2026, 12, 31))).get()
                .satisfies(e -> assertThat(e.validTo()).isEqualTo(LocalDate.of(2026, 12, 31)));
        assertThat(refdata.findBic("MNPXNL2AXXX", LocalDate.of(2027, 1, 1))).isEmpty();

        // Joined on 2026-11-18.
        assertThat(refdata.findBic("MNPSSGSGXXX", LocalDate.of(2026, 11, 17))).isEmpty();
        assertThat(refdata.findBic("mnpssgsg", TODAY)).get().extracting(BicEntry::connectivity).isEqualTo(Connectivity.PENDING_RMA);
    }

    @Test
    void overlapping_versions_of_one_bic_are_impossible() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO refdata.bic_directory (bic, institution_name, country_code, city, connectivity, valid, release_version)
                VALUES ('MNPBDEFFXXX', 'Duplicate', 'DE', 'Frankfurt', 'CONNECTED', '[2026-01-01,)', 'v2024.01.1')
                """).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("bic_directory_bic_valid_excl");
    }

    @Test
    void the_bic_country_must_match_its_country_code() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO refdata.bic_directory (bic, institution_name, country_code, city, connectivity, valid, release_version)
                VALUES ('MNPQFRPPXXX', 'Wrong country', 'DE', 'Paris', 'CONNECTED', '[2026-01-01,)', 'v2024.01.1')
                """).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void history_is_never_rewritten_only_end_dated() {
        // Changing a fact in place is refused ...
        assertThatThrownBy(() -> jdbc.sql("UPDATE refdata.bic_directory SET institution_name = 'Renamed' WHERE bic = 'MNPDBEBBXXX'").update())
                .hasMessageContaining("can only be end-dated");
        // ... so is deleting it, or extending a closed validity, or touching holidays and releases.
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM refdata.bic_directory WHERE bic = 'MNPDBEBBXXX'").update())
                .hasMessageContaining("never deleted");
        assertThatThrownBy(() -> jdbc.sql("UPDATE refdata.bic_directory SET valid = daterange(lower(valid), NULL) WHERE bic = 'MNPXNL2AXXX'").update())
                .hasMessageContaining("can only be end-dated");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM refdata.holiday WHERE calendar_code = 'TARGET'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE refdata.release SET approved_by = 'someone' WHERE version = 'v2024.01.1'").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void a_release_cannot_be_approved_by_its_author() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO refdata.release (version, published_by, approved_by, description) VALUES ('v2099.01.1', 'r.lim', 'r.lim', 'x')
                """).update()).isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------ IBAN

    @Test
    void ibans_are_checked_against_the_registry_and_mapped_to_their_bank() {
        IbanCheck gb = refdata.checkIban("GB86 MNPC 4005 1288 3344 10", TODAY);
        assertThat(gb.valid()).isTrue();
        assertThat(gb.bic()).isEqualTo("MNPCGB2LXXX");

        IbanCheck be = refdata.checkIban("BE71096123456769", TODAY);
        assertThat(be.valid()).isTrue();
        assertThat(be.parts()).extracting(IbanCheck.Part::name).contains("National check");
        assertThat(be.bic()).isEqualTo("MNPDBEBBXXX");

        assertThat(refdata.checkIban("FR7630006000011234567890189", TODAY).bic()).isEqualTo("MNPEFRPPXXX");
        assertThat(refdata.checkIban("GB97MNPC60161331926819", TODAY).valid()).isFalse();
        // Malaysia does not use IBANs.
        assertThat(refdata.usesIban("MY", TODAY)).isFalse();
        assertThat(refdata.usesIban("DE", TODAY)).isTrue();
    }

    // ------------------------------------------------------------------ calendars and cut-offs

    @Test
    void value_dates_respect_each_currencys_calendar_and_cut_off() {
        Instant tuesdayMorningKl = RefDataTestApplication.NOW; // 02:00 UTC = 03:00 Frankfurt, 02:00 London
        assertThat(refdata.valueDate("EUR", TODAY, tuesdayMorningKl).valueDate()).isEqualTo(TODAY);

        // Christmas: TARGET closes 25 and 26 Dec, England 25 and 28 Dec (Boxing Day substitute).
        Instant christmasEve = Instant.parse("2026-12-24T20:00:00Z");
        assertThat(refdata.valueDate("EUR", LocalDate.of(2026, 12, 24), christmasEve).valueDate()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(refdata.valueDate("GBP", LocalDate.of(2026, 12, 24), christmasEve).valueDate()).isEqualTo(LocalDate.of(2026, 12, 29));

        // Thanksgiving closes Fedwire, not TARGET.
        assertThat(refdata.isBusinessDay("USD", LocalDate.of(2026, 11, 26))).isFalse();
        assertThat(refdata.isBusinessDay("EUR", LocalDate.of(2026, 11, 26))).isTrue();
        assertThat(refdata.nextBusinessDay("USD", LocalDate.of(2026, 11, 26))).isEqualTo(LocalDate.of(2026, 11, 27));

        // A Saturday request settles on Monday.
        ValueDating sat = refdata.valueDate("MYR", LocalDate.of(2026, 11, 28), tuesdayMorningKl);
        assertThat(sat.valueDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(sat.reasons()).hasSize(2);
    }

    @Test
    void a_currency_without_a_scheme_cannot_be_value_dated() {
        assertThatThrownBy(() -> refdata.valueDate("JPY", TODAY, RefDataTestApplication.NOW))
                .isInstanceOf(RefDataException.class).extracting("error").isEqualTo(RefDataError.NO_SCHEME);
    }

    @Test
    void cut_offs_are_reported_per_scheme_for_now() {
        List<CutoffView> cutoffs = refdata.cutoffs(RefDataTestApplication.NOW);
        assertThat(cutoffs).extracting(CutoffView::currency).containsExactly("EUR", "GBP", "MYR", "USD");
        CutoffView eur = cutoffs.get(0);
        assertThat(eur.settlementDate()).isEqualTo(TODAY);
        assertThat(eur.cutoff()).isEqualTo(Instant.parse("2026-11-24T16:00:00Z"));
        // RENTAS closes at 18:00 KL = 10:00 UTC.
        assertThat(cutoffs.get(2).cutoff()).isEqualTo(Instant.parse("2026-11-24T10:00:00Z"));
    }

    // ------------------------------------------------------------------ routing rules

    @Test
    void imported_rules_route_by_value_date_and_import_is_idempotent() {
        String dest = randomBic8();
        List<RoutingRuleImport> file = List.of(
                new RoutingRuleImport(nextId(), dest + "*", "EUR", RoutingMethod.SERIAL, "MNPBDEFFXXX", "1101-EUR-MNPB", 20,
                        LocalDate.of(2024, 1, 1), LocalDate.of(2026, 11, 30), "v2024.01.1"),
                new RoutingRuleImport(nextId(1), dest + "*", "EUR", RoutingMethod.COVER, "MNPBDEFFXXX", "1101-EUR-MNPB", 20,
                        LocalDate.of(2026, 12, 1), null, "v2026.11.3"));
        refdata.importRoutingRules(file);
        refdata.importRoutingRules(file); // again: no-op

        // Until 30 Nov: serial via Bank B. Only the next hop has to be reachable, so a creditor agent
        // that is not on the network at all can still be paid this way.
        RouteResolution nov = refdata.route(dest + "XXX", "EUR", LocalDate.of(2026, 11, 30));
        assertThat(nov.route().orElseThrow().rule().ruleId()).isEqualTo(file.get(0).ruleId());
        assertThat(nov.route().orElseThrow().instructedAgentBic()).isEqualTo("MNPBDEFFXXX");
        // From 1 Dec: cover. The pacs.008 now goes to the creditor agent itself, which must be connected.
        RouteResolution dec = refdata.route(dest + "XXX", "EUR", LocalDate.of(2026, 12, 1));
        assertThat(dec.route()).isEmpty();
        assertThat(dec.problem()).isEqualTo(file.get(1).ruleId() + " cannot be used: " + dest
                + "XXX is not in the BIC directory on 2026-12-01");
        assertThat(refdata.routingRules()).extracting(RoutingRuleView::ruleId).contains(file.get(0).ruleId(), file.get(1).ruleId());
    }

    @Test
    void re_importing_a_rule_that_was_superseded_since_is_not_a_conflict() {
        // A bank seeds its routing table, publishes a change that end-dates a seeded rule, and restarts:
        // the seed file still says "open-ended", the database knows better. Startup must not fail.
        String dest = randomBic8() + "*";
        String id = nextId();
        RoutingRuleImport seeded = new RoutingRuleImport(id, dest, "EUR", RoutingMethod.SERIAL, "MNPBDEFFXXX",
                "1101-EUR-MNPB", 20, LocalDate.of(2024, 1, 1), null, "v2024.01.1");
        refdata.importRoutingRules(List.of(seeded));
        refdata.createRoutingRule(command(key(), dest, 20, TODAY.plusDays(3), null, id));

        refdata.importRoutingRules(List.of(seeded)); // restart

        assertThat(refdata.routingRules()).filteredOn(r -> r.ruleId().equals(id)).singleElement()
                .satisfies(r -> assertThat(r.effectiveTo()).isEqualTo(TODAY.plusDays(2)));
        // A real difference is still refused.
        RoutingRuleImport changed = new RoutingRuleImport(id, dest, "EUR", RoutingMethod.SERIAL, "MNPBDEFFXXX",
                "1101-EUR-MNPB", 25, LocalDate.of(2024, 1, 1), null, "v2024.01.1");
        assertThatThrownBy(() -> refdata.importRoutingRules(List.of(changed))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishing_a_rule_that_supersedes_another_end_dates_it_with_no_gap() {
        String dest = randomBic8() + "*";
        RoutingRuleView first = publish(dest, 30, TODAY, null).value();
        assertThat(first.effectiveTo()).isNull();

        RoutingRuleView second = refdata.createRoutingRule(command(key(), dest, 30, TODAY.plusDays(7), null, first.ruleId())).value();
        RoutingRuleView firstNow = refdata.routingRules().stream().filter(r -> r.ruleId().equals(first.ruleId())).findFirst().orElseThrow();
        assertThat(firstNow.effectiveTo()).isEqualTo(TODAY.plusDays(6));
        assertThat(second.effectiveFrom()).isEqualTo(TODAY.plusDays(7));
        assertThat(second.releaseVersion()).isNotEqualTo(first.releaseVersion()).startsWith("v2026.11.");
        assertThat(refdata.releases().get(0).version()).isEqualTo(second.releaseVersion());
    }

    @Test
    void overlapping_rules_with_the_same_priority_are_refused() {
        String dest = randomBic8() + "*";
        publish(dest, 40, TODAY, null);
        assertThatThrownBy(() -> publish(dest, 40, TODAY.plusDays(3), null))
                .isInstanceOf(RefDataException.class).extracting("error").isEqualTo(RefDataError.RULE_OVERLAP);
        // A different priority is fine: it is a deliberate fallback.
        assertThat(publish(dest, 41, TODAY.plusDays(3), null).value().priority()).isEqualTo(41);
    }

    @Test
    void rule_publication_enforces_four_eyes_and_no_back_dating() {
        String dest = randomBic8() + "*";
        CreateRoutingRuleCommand c = command(key(), dest, 30, TODAY, null, null);
        assertThatThrownBy(() -> refdata.createRoutingRule(new CreateRoutingRuleCommand(c.idempotencyKey(), c.destination(),
                c.currency(), c.method(), c.nextHopBic(), c.settlementAccount(), c.priority(), c.effectiveFrom(), c.effectiveTo(),
                null, "r.lim", "R.LIM ", c.description())))
                .extracting("error").isEqualTo(RefDataError.FOUR_EYES_VIOLATION);
        assertThatThrownBy(() -> refdata.createRoutingRule(command(key(), dest, 30, TODAY.minusDays(1), null, null)))
                .extracting("error").isEqualTo(RefDataError.INVALID_RULE);
        assertThatThrownBy(() -> refdata.createRoutingRule(new CreateRoutingRuleCommand(key(), dest, "EUR", RoutingMethod.DIRECT,
                "MNPBDEFFXXX", "1101-EUR-MNPB", 30, TODAY, null, null, "r.lim", "a.wong", "Direct needs no next hop")))
                .hasMessageContaining("takes no next hop");
    }

    @Test
    void same_key_replays_and_a_different_request_under_it_is_refused() {
        String dest = randomBic8() + "*";
        String key = key();
        Idempotent<RoutingRuleView> first = refdata.createRoutingRule(command(key, dest, 30, TODAY, null, null));
        Idempotent<RoutingRuleView> again = refdata.createRoutingRule(command(key, dest, 30, TODAY, null, null));
        assertThat(again.replayed()).isTrue();
        assertThat(again.value()).isEqualTo(first.value());
        assertThatThrownBy(() -> refdata.createRoutingRule(command(key, dest, 31, TODAY, null, null)))
                .extracting("error").isEqualTo(RefDataError.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void a_rejected_publication_leaves_nothing_behind() {
        String dest = randomBic8() + "*";
        RoutingRuleView first = publish(dest, 50, TODAY, null).value();
        publish(dest, 51, TODAY.plusDays(10), null);
        int releases = refdata.releases().size();
        // Superseding first from day 10 with priority 51 collides with the rule above: everything rolls back,
        // including the end-dating of the superseded rule and the new release.
        assertThatThrownBy(() -> refdata.createRoutingRule(command(key(), dest, 51, TODAY.plusDays(10), null, first.ruleId())))
                .extracting("error").isEqualTo(RefDataError.RULE_OVERLAP);
        assertThat(refdata.routingRules().stream().filter(r -> r.ruleId().equals(first.ruleId())).findFirst().orElseThrow()
                .effectiveTo()).isNull();
        assertThat(refdata.releases()).hasSize(releases);
    }

    @Test
    void concurrent_publications_get_distinct_rule_ids_and_release_versions() throws Exception {
        // Ids and release numbers are "current maximum + 1". Without the table lock, two publishers read
        // the same maximum and one of them fails on the primary key.
        int n = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<RoutingRuleView>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                String dest = randomBic8() + "*";
                futures.add(pool.submit(() -> {
                    go.await();
                    return publish(dest, 60, TODAY, null).value();
                }));
            }
            go.countDown();
            List<RoutingRuleView> rules = new java.util.ArrayList<>();
            for (var f : futures) {
                rules.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(rules).extracting(RoutingRuleView::ruleId).doesNotHaveDuplicates();
            assertThat(rules).extracting(RoutingRuleView::releaseVersion).doesNotHaveDuplicates();
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------ helpers

    private Idempotent<RoutingRuleView> publish(String dest, int priority, LocalDate from, LocalDate to) {
        return refdata.createRoutingRule(command(key(), dest, priority, from, to, null));
    }

    private static CreateRoutingRuleCommand command(String key, String dest, int priority, LocalDate from, LocalDate to, String supersedes) {
        return new CreateRoutingRuleCommand(key, dest, "EUR", RoutingMethod.SERIAL, "MNPBDEFFXXX", "1101-EUR-MNPB",
                priority, from, to, supersedes, "r.lim", "a.wong", "Test rule");
    }

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    /** A random, format-valid BIC8 in Germany, so each test has its own destination. */
    private static String randomBic8() {
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("T");
        for (int i = 0; i < 3; i++) {
            sb.append(letters.charAt(ThreadLocalRandom.current().nextInt(letters.length())));
        }
        return sb.append("DE").append(ThreadLocalRandom.current().nextInt(10)).append(letters.charAt(ThreadLocalRandom.current().nextInt(26))).toString();
    }

    private String nextId() {
        return nextId(0);
    }

    /** Imported ids from a high range, so they do not collide with ids handed out by publication. */
    private String nextId(int offset) {
        int max = jdbc.sql("SELECT coalesce(max(substr(rule_id, 3)::int), 0) FROM refdata.routing_rule").query(Integer.class).single();
        return String.format("R-%03d", Math.max(max, 899) + 1 + offset);
    }
}
