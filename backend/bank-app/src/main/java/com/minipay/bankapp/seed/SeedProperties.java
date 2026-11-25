package com.minipay.bankapp.seed;

import com.minipay.ledger.api.AccountType;
import com.minipay.refdata.api.RoutingMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;

/** Seed accounts and routing rules for this bank, from {@code application-bank-*.yml}. */
@ConfigurationProperties("minipay.seed")
public record SeedProperties(boolean enabled, List<SeedAccount> accounts, List<SeedRoutingRule> routingRules) {

    public SeedProperties {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        routingRules = routingRules == null ? List.of() : List.copyOf(routingRules);
    }

    /**
     * One row of the bank's routing table, as it would arrive in a reference data file.
     *
     * @param to      last day the rule applies (inclusive), or empty for open-ended
     * @param release the reference data release that introduced the rule
     */
    public record SeedRoutingRule(String id, String destination, String currency, RoutingMethod method, String nextHop,
                                  String settlementAccount, int priority,
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  String release) {
    }

    /**
     * @param openingBalance optional; booked as a SETTLE entry against {@code fundedFrom}
     * @param fundedFrom     the account on the other side of the opening entry (usually the settlement
     *                       account at the central bank, or a nostro)
     */
    public record SeedAccount(String number, AccountType type, String currency, String owner,
                              String counterpartyBic, boolean allowOverdraft,
                              BigDecimal openingBalance, String fundedFrom) {
    }
}
