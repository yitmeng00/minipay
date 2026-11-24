package com.minipay.paymenthub;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.OpenAccountCommand;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.paymenthub.api.CreatePaymentCommand;
import com.minipay.paymenthub.api.PaymentHubApi;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base for payment hub integration tests.
 *
 * <p>The routing world is shared and created once (idempotently): Bank A with one EUR nostro at
 * Bank B, one GBP nostro at Bank C, a MYR vostro for Bank B, and fee income accounts. Each test opens
 * its own uniquely numbered customer accounts, because ledgers cannot be cleaned up.
 */
@SpringBootTest(classes = PaymentHubTestApplication.class)
@Import(TestInfrastructure.class)
public abstract class PaymentHubIntegrationTest {

    protected static final String BANK_B = "MNPBDEFFXXX";
    protected static final String BANK_C = "MNPCGB2LXXX";

    @Autowired
    protected PaymentHubApi hub;

    @Autowired
    protected LedgerApi ledger;

    @Autowired
    protected TestInfrastructure.MutableClock clock;

    @Autowired
    protected TestInfrastructure.ControllableScreening screening;

    protected AccountView eurNostro;
    protected AccountView gbpNostro;
    protected AccountView eurFees;
    protected AccountView fundingSuspense;

    @BeforeEach
    void world() {
        screening.crash(false);
        eurNostro = ensure("T-NOSTRO-EUR-MNPB", AccountType.NOSTRO, "EUR", "Nostro EUR @ Bank B", BANK_B, false);
        gbpNostro = ensure("T-NOSTRO-GBP-MNPC", AccountType.NOSTRO, "GBP", "Nostro GBP @ Bank C", BANK_C, false);
        ensure("T-VOSTRO-MYR-MNPB", AccountType.VOSTRO, "MYR", "Vostro Bank B (MYR)", BANK_B, false);
        ensure("T-NOSTRO-MYR-CB", AccountType.NOSTRO, "MYR", "RENTAS", "MNCBMYKLXXX", false);
        eurFees = ensure("T-FEE-EUR", AccountType.FEE_INCOME, "EUR", "Fee income EUR", null, false);
        ensure("T-FEE-GBP", AccountType.FEE_INCOME, "GBP", "Fee income GBP", null, false);
        fundingSuspense = ensure("T-FUNDING-JPY", AccountType.SUSPENSE, "JPY", "Test funding JPY", null, true);
    }

    private AccountView ensure(String number, AccountType type, String ccy, String owner, String bic, boolean overdraft) {
        return ledger.findAccountByNumber(number).orElseGet(() ->
                ledger.openAccount(new OpenAccountCommand(number, type, ccy, owner, bic, overdraft)));
    }

    /** A customer funded through the currency's nostro (or the JPY funding account). */
    protected AccountView customer(String ccy, String balance) {
        AccountView c = ledger.openAccount(new OpenAccountCommand(
                "C" + Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE), 36).toUpperCase(),
                AccountType.CUSTOMER, ccy, "Selangor Precision Components Sdn Bhd", null, false));
        AccountView contra = switch (ccy) {
            case "EUR" -> eurNostro;
            case "GBP" -> gbpNostro;
            case "JPY" -> fundingSuspense;
            default -> ledger.findAccountByNumber("T-NOSTRO-MYR-CB").orElseThrow();
        };
        Money m = Money.of(balance, ccy);
        ledger.post(new PostEntryCommand("fund-" + UUID.randomUUID(), EntryType.SETTLE, null, null, "Test funding",
                List.of(new PostingLine(contra.id(), Direction.D, m), new PostingLine(c.id(), Direction.C, m))));
        return c;
    }

    protected AccountView reload(AccountView a) {
        return ledger.getAccount(a.id());
    }

    protected static String key() {
        return "test-" + UUID.randomUUID();
    }

    protected CreatePaymentCommand payment(AccountView debtor, String amount, ChargeBearer bearer) {
        return new CreatePaymentCommand(key(), debtor.accountNumber(), "Harlow & Finch Engineering Ltd",
                "GB96 MNPC 6016 1331 9268 19", BANK_C, Money.of(amount, debtor.currency()), bearer, null,
                "INV-HF-2026-1187", "INV-HF-2026-1187 CNC spindle assemblies");
    }

    protected CreatePaymentCommand withDate(CreatePaymentCommand c, LocalDate date) {
        return new CreatePaymentCommand(c.idempotencyKey(), c.debtorAccount(), c.creditorName(), c.creditorAccount(),
                c.creditorAgentBic(), c.amount(), c.chargeBearer(), date, c.endToEndId(), c.remittanceInformation());
    }

    protected static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }
}
