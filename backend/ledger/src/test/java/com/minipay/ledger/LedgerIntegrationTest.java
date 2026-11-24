package com.minipay.ledger;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.OpenAccountCommand;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base class for ledger integration tests.
 *
 * <p>The ledger is append-only, so tests can never "clean up" postings. Instead every test opens its
 * own uniquely numbered accounts and only asserts on those — which is also how you test a real ledger.
 */
@SpringBootTest(classes = LedgerTestApplication.class)
@Import(PostgresTestcontainer.class)
public abstract class LedgerIntegrationTest {

    @Autowired
    protected LedgerApi ledger;

    protected static String key() {
        return "test-" + UUID.randomUUID();
    }

    protected AccountView openCustomer(String ccy) {
        return ledger.openAccount(new OpenAccountCommand(uniqueNumber("CUS"), AccountType.CUSTOMER, ccy,
                "Test customer", null, false));
    }

    protected AccountView openNostro(String ccy) {
        return ledger.openAccount(new OpenAccountCommand(uniqueNumber("NOS"), AccountType.NOSTRO, ccy,
                "Nostro " + ccy + " at Bank B", "MNPBDEFFXXX", false));
    }

    protected AccountView openFeeIncome(String ccy) {
        return ledger.openAccount(new OpenAccountCommand(uniqueNumber("FEE"), AccountType.FEE_INCOME, ccy,
                "Fee income", null, false));
    }

    /**
     * Credits the customer with money that arrived on our nostro: debit nostro (our asset at the
     * correspondent goes up), credit customer (what we owe the customer goes up).
     */
    protected void fund(AccountView customer, AccountView nostro, String amount) {
        ledger.post(new PostEntryCommand(key(), EntryType.SETTLE, null, null, "Test funding",
                List.of(new PostingLine(nostro.id(), Direction.D, Money.of(amount, customer.currency())),
                        new PostingLine(customer.id(), Direction.C, Money.of(amount, customer.currency())))));
    }

    protected AccountView reload(AccountView a) {
        return ledger.getAccount(a.id());
    }

    private static String uniqueNumber(String prefix) {
        return prefix + "-" + Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE), 36).toUpperCase();
    }
}
