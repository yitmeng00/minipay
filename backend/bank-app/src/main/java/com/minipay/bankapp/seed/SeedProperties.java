package com.minipay.bankapp.seed;

import com.minipay.ledger.api.AccountType;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Seed accounts for this bank, from {@code application-bank-*.yml}. */
@ConfigurationProperties("minipay.seed")
public record SeedProperties(boolean enabled, List<SeedAccount> accounts) {

    public SeedProperties {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
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
