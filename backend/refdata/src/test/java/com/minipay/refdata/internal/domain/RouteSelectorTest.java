package com.minipay.refdata.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.refdata.api.BicEntry;
import com.minipay.refdata.api.Connectivity;
import com.minipay.refdata.api.RouteResolution;
import com.minipay.refdata.api.RoutingMethod;
import com.minipay.refdata.api.RoutingRuleView;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RouteSelectorTest {

    static final LocalDate D2024 = LocalDate.of(2024, 1, 1);

    static RoutingRuleView rule(String id, String dest, RoutingMethod m, String hop, int prio, String from, String to) {
        return new RoutingRuleView(id, dest, "EUR", m, hop, "ACC-" + id, prio, LocalDate.parse(from),
                to == null ? null : LocalDate.parse(to), "v1");
    }

    static final Map<String, Connectivity> DIR = Map.of(
            "MNPBDEFFXXX", Connectivity.CONNECTED, "MNPEFRPPXXX", Connectivity.CONNECTED,
            "MNPDBEBBXXX", Connectivity.CONNECTED, "MNPBDEFF100", Connectivity.CONNECTED, "MNPSSGSGXXX", Connectivity.PENDING_RMA,
            "MNPXNL2AXXX", Connectivity.NOT_CONNECTED);

    static Optional<BicEntry> lookup(String bic) {
        Connectivity c = DIR.get(bic);
        return c == null ? Optional.empty()
                : Optional.of(new BicEntry(bic, "x", bic.substring(4, 6), "x", c, List.of(), D2024, null, "v1"));
    }

    static RouteResolution route(List<RoutingRuleView> rules, String bic, String date) {
        return RouteSelector.select(rules, bic, "EUR", LocalDate.parse(date), RouteSelectorTest::lookup);
    }

    static final List<RoutingRuleView> RULES = List.of(
            rule("R-001", "MNPBDEFF*", RoutingMethod.DIRECT, null, 10, "2024-01-01", null),
            rule("R-005", "MNPEFRPP*", RoutingMethod.SERIAL, "MNPBDEFFXXX", 20, "2024-01-01", "2026-11-30"),
            rule("R-006", "MNPEFRPP*", RoutingMethod.COVER, "MNPBDEFFXXX", 20, "2026-12-01", null),
            rule("R-011", "*", RoutingMethod.SERIAL, "MNPBDEFFXXX", 99, "2024-01-01", null));

    @Test
    void a_specific_rule_beats_the_catch_all_by_priority() {
        RouteResolution r = route(RULES, "MNPBDEFF100", "2026-11-25"); // a branch: matched on BIC8
        assertThat(r.route()).get().satisfies(x -> {
            assertThat(x.rule().ruleId()).isEqualTo("R-001");
            assertThat(x.instructedAgentBic()).isEqualTo("MNPBDEFF100");
        });
        assertThat(r.considered()).extracting(c -> c.rule().ruleId()).containsExactly("R-001", "R-011");
    }

    @Test
    void the_value_date_decides_which_version_of_a_route_applies() {
        RouteResolution nov = route(RULES, "MNPEFRPPXXX", "2026-11-30");
        RouteResolution dec = route(RULES, "MNPEFRPPXXX", "2026-12-01");
        assertThat(nov.route().orElseThrow().rule().ruleId()).isEqualTo("R-005");
        assertThat(nov.route().orElseThrow().instructedAgentBic()).isEqualTo("MNPBDEFFXXX"); // serial: to the correspondent
        assertThat(dec.route().orElseThrow().rule().ruleId()).isEqualTo("R-006");
        assertThat(dec.route().orElseThrow().instructedAgentBic()).isEqualTo("MNPEFRPPXXX"); // cover: pacs.008 to the creditor agent
        assertThat(nov.considered()).anySatisfy(c -> assertThat(c.note()).isEqualTo("Scheduled: starts 2026-12-01"));
    }

    @Test
    void equal_priority_prefers_the_specific_rule() {
        List<RoutingRuleView> rules = List.of(
                rule("R-001", "*", RoutingMethod.SERIAL, "MNPBDEFFXXX", 20, "2024-01-01", null),
                rule("R-002", "MNPDBEBB*", RoutingMethod.DIRECT, null, 20, "2024-01-01", null));
        assertThat(route(rules, "MNPDBEBBXXX", "2026-11-25").route().orElseThrow().rule().ruleId()).isEqualTo("R-002");
    }

    @Test
    void a_branch_rule_beats_the_institution_rule() {
        List<RoutingRuleView> rules = List.of(
                rule("R-001", "MNPBDEFF*", RoutingMethod.DIRECT, null, 20, "2024-01-01", null),
                rule("R-002", "MNPBDEFF100", RoutingMethod.COVER, "MNPBDEFFXXX", 20, "2024-01-01", null));
        assertThat(route(rules, "MNPBDEFF100", "2026-11-25").route().orElseThrow().rule().ruleId()).isEqualTo("R-002");
        assertThat(route(rules, "MNPBDEFFXXX", "2026-11-25").route().orElseThrow().rule().ruleId()).isEqualTo("R-001");
    }

    @Test
    void no_matching_rule_means_no_route() {
        RouteResolution r = route(RULES.subList(0, 1), "MNPDBEBBXXX", "2026-11-25");
        assertThat(r.route()).isEmpty();
        assertThat(r.problem()).isEqualTo("No routing rule for MNPDBEBB in EUR valid on 2026-11-25");
    }

    @Test
    void an_unreachable_winner_is_reported_not_skipped() {
        List<RoutingRuleView> rules = List.of(
                rule("R-020", "MNPXNL2A*", RoutingMethod.DIRECT, null, 10, "2024-01-01", null),
                rule("R-011", "*", RoutingMethod.SERIAL, "MNPBDEFFXXX", 99, "2024-01-01", null));
        RouteResolution r = route(rules, "MNPXNL2AXXX", "2026-11-25");
        assertThat(r.route()).isEmpty();
        assertThat(r.problem()).isEqualTo("R-020 cannot be used: MNPXNL2AXXX is NOT_CONNECTED (we cannot send to it)");
    }

    @Test
    void serial_routing_reaches_an_unconnected_bank_through_a_correspondent() {
        RouteResolution r = route(RULES, "MNPXNL2AXXX", "2026-11-25");
        assertThat(r.route().orElseThrow().rule().ruleId()).isEqualTo("R-011");
        assertThat(r.route().orElseThrow().instructedAgentBic()).isEqualTo("MNPBDEFFXXX");
    }

    @Test
    void a_next_hop_without_an_authorised_relationship_cannot_be_used() {
        List<RoutingRuleView> rules = List.of(rule("R-030", "*", RoutingMethod.SERIAL, "MNPSSGSGXXX", 10, "2024-01-01", null));
        assertThat(route(rules, "MNPEFRPPXXX", "2026-11-25").problem()).contains("MNPSSGSGXXX is PENDING_RMA");
    }
}
