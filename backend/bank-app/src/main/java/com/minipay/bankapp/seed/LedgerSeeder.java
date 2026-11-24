package com.minipay.bankapp.seed;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.OpenAccountCommand;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.bankapp.seed.SeedProperties.SeedAccount;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the bank's chart of accounts and opening balances on startup — through the ledger's public
 * API, so seed data obeys exactly the same rules as real traffic (balanced entries, idempotency).
 *
 * <p>Safe to run on every start: accounts are looked up by number, and each opening balance uses a
 * fixed idempotency key ({@code seed-opening-<account>}) so a restart replays instead of re-posting.
 *
 * <p>Opening balances need a contra account. We use the bank's settlement account (or a nostro):
 * "the customer's deposit is backed by money we hold at the central bank / correspondent".
 * That keeps the books balanced without inventing an equity account.
 */
@Component
class LedgerSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LedgerSeeder.class);

    private final SeedProperties seed;
    private final LedgerApi ledger;

    LedgerSeeder(SeedProperties seed, LedgerApi ledger) {
        this.seed = seed;
        this.ledger = ledger;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seed.enabled()) {
            return;
        }
        int opened = 0;
        for (SeedAccount a : seed.accounts()) {
            if (ledger.findAccountByNumber(a.number()).isEmpty()) {
                ledger.openAccount(new OpenAccountCommand(a.number(), a.type(), a.currency(), a.owner(),
                        a.counterpartyBic(), a.allowOverdraft()));
                opened++;
            }
        }
        int funded = 0;
        for (SeedAccount a : seed.accounts()) {
            if (a.openingBalance() == null || a.openingBalance().signum() == 0) {
                continue;
            }
            AccountView account = ledger.findAccountByNumber(a.number()).orElseThrow();
            AccountView contra = ledger.findAccountByNumber(a.fundedFrom()).orElseThrow(
                    () -> new IllegalStateException("Seed account " + a.number() + " funded from unknown " + a.fundedFrom()));
            Money amount = Money.of(a.openingBalance(), a.currency());
            // Increase the seeded account on its normal side; the contra takes the opposite side.
            Direction side = account.type().normalSide();
            var result = ledger.post(new PostEntryCommand("seed-opening-" + a.number(), EntryType.SETTLE, null, null,
                    "Opening balance",
                    List.of(new PostingLine(account.id(), side, amount),
                            new PostingLine(contra.id(), side.opposite(), amount))));
            if (!result.replayed()) {
                funded++;
            }
        }
        log.info("Ledger seed: {} accounts opened, {} opening balances booked ({} accounts configured)",
                opened, funded, seed.accounts().size());
    }
}
