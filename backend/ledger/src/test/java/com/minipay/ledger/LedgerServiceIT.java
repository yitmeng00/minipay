package com.minipay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.Idempotent;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.ledger.api.LedgerError;
import com.minipay.ledger.api.LedgerException;
import com.minipay.ledger.api.OpenAccountCommand;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.ledger.api.ReleaseReservationCommand;
import com.minipay.ledger.api.ReservationStatus;
import com.minipay.ledger.api.ReservationView;
import com.minipay.ledger.api.ReserveFundsCommand;
import com.minipay.ledger.api.ReverseEntryCommand;
import com.minipay.ledger.api.SettleReservationCommand;
import com.minipay.ledger.api.StatementView;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The ledger's business behaviour, end to end against PostgreSQL. */
class LedgerServiceIT extends LedgerIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    private static void assertError(ThrowingCallable call, LedgerError expected) {
        assertThatThrownBy(call).isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error()).isEqualTo(expected);
    }

    @Test
    void opening_an_account_creates_its_holds_control_account() {
        AccountView c = openCustomer("EUR");
        assertThat(c.booked()).isEqualTo(eur("0"));
        assertThat(ledger.findAccountByNumber("HOLDS-EUR")).get()
                .satisfies(h -> {
                    assertThat(h.type()).isEqualTo(AccountType.SUSPENSE);
                    assertThat(h.allowOverdraft()).isTrue();
                });
        assertError(() -> ledger.openAccount(new OpenAccountCommand(c.accountNumber(), AccountType.CUSTOMER, "EUR",
                "dup", null, false)), LedgerError.DUPLICATE_ACCOUNT);
    }

    @Test
    void nostro_needs_a_counterparty_bic() {
        assertThatThrownBy(() -> ledger.openAccount(new OpenAccountCommand("NOS-NO-BIC", AccountType.NOSTRO, "EUR",
                "Nostro", null, false))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void funding_moves_booked_balances_on_both_sides() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");

        fund(customer, nostro, "1000.00");

        assertThat(reload(customer).booked()).isEqualTo(eur("1000.00"));
        assertThat(reload(nostro).booked()).isEqualTo(eur("1000.00"));
    }

    @Test
    void reserve_then_release_restores_available() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        fund(customer, nostro, "500.00");

        ReservationView hold = ledger.reserve(new ReserveFundsCommand(key(), customer.id(), eur("120.00"),
                UUID.randomUUID(), "pacs.008 to MNPBDEFFXXX")).value();

        AccountView held = reload(customer);
        assertThat(held.booked()).isEqualTo(eur("500.00"));
        assertThat(held.reserved()).isEqualTo(eur("120.00"));
        assertThat(held.available()).isEqualTo(eur("380.00"));
        assertThat(hold.status()).isEqualTo(ReservationStatus.ACTIVE);

        ReservationView released = ledger.release(new ReleaseReservationCommand(key(), hold.id(), "AM04 at next agent")).value();
        assertThat(released.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reload(customer).available()).isEqualTo(eur("500.00"));
        assertThat(reload(customer).reserved()).isEqualTo(eur("0"));

        assertError(() -> ledger.release(new ReleaseReservationCommand(key(), hold.id(), null)),
                LedgerError.RESERVATION_NOT_ACTIVE);
    }

    @Test
    void cannot_reserve_more_than_available() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        fund(customer, nostro, "100.00");
        ledger.reserve(new ReserveFundsCommand(key(), customer.id(), eur("60.00"), null, null));

        assertError(() -> ledger.reserve(new ReserveFundsCommand(key(), customer.id(), eur("40.01"), null, null)),
                LedgerError.INSUFFICIENT_FUNDS);
        assertThat(reload(customer).available()).isEqualTo(eur("40.00"));
    }

    @Test
    void settle_books_the_payment_and_clears_the_hold_atomically() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        fund(customer, nostro, "1000.00");
        ReservationView hold = ledger.reserve(new ReserveFundsCommand(key(), customer.id(), eur("250.00"), null,
                "INV-2026-1187")).value();

        ReservationView settled = ledger.settle(new SettleReservationCommand(key(), hold.id(), nostro.id(),
                "To Harlow & Finch Engineering Ltd", null)).value();

        assertThat(settled.status()).isEqualTo(ReservationStatus.SETTLED);
        AccountView c = reload(customer);
        assertThat(c.booked()).isEqualTo(eur("750.00"));
        assertThat(c.reserved()).isEqualTo(eur("0"));
        assertThat(c.available()).isEqualTo(eur("750.00"));
        // Crediting our nostro = money left our account at the correspondent.
        assertThat(reload(nostro).booked()).isEqualTo(eur("750.00"));

        JournalEntryView settleEntry = ledger.getEntry(settled.settleEntryId());
        assertThat(settleEntry.entryType()).isEqualTo(EntryType.SETTLE);
        assertThat(ledger.getEntry(settled.releaseEntryId()).entryType()).isEqualTo(EntryType.RELEASE);

        assertError(() -> ledger.settle(new SettleReservationCommand(key(), hold.id(), nostro.id(), null, null)),
                LedgerError.RESERVATION_NOT_ACTIVE);
    }

    @Test
    void settle_into_another_currency_is_refused_with_AM03() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        AccountView usdNostro = openNostro("USD");
        fund(customer, nostro, "10.00");
        ReservationView hold = ledger.reserve(new ReserveFundsCommand(key(), customer.id(), eur("10.00"), null, null)).value();

        assertError(() -> ledger.settle(new SettleReservationCommand(key(), hold.id(), usdNostro.id(), null, null)),
                LedgerError.CURRENCY_NOT_ALLOWED);
        assertError(() -> ledger.reserve(new ReserveFundsCommand(key(), customer.id(), Money.of("1", "USD"), null, null)),
                LedgerError.CURRENCY_NOT_ALLOWED);
    }

    @Test
    void fee_entry_and_its_reversal() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        AccountView fees = openFeeIncome("EUR");
        fund(customer, nostro, "100.00");

        JournalEntryView fee = ledger.post(new PostEntryCommand(key(), EntryType.FEE, null, null,
                "Outward transfer fee (SHAR)",
                List.of(new PostingLine(customer.id(), Direction.D, eur("20.00")),
                        new PostingLine(fees.id(), Direction.C, eur("20.00"))))).value();
        assertThat(reload(customer).booked()).isEqualTo(eur("80.00"));
        assertThat(reload(fees).booked()).isEqualTo(eur("20.00"));

        JournalEntryView reversal = ledger.reverse(new ReverseEntryCommand(key(), fee.id(), "Fee waived")).value();
        assertThat(reversal.entryType()).isEqualTo(EntryType.REVERSAL);
        assertThat(reversal.reversesEntryId()).isEqualTo(fee.id());
        assertThat(reversal.valueDate()).isEqualTo(fee.valueDate());
        assertThat(reload(customer).booked()).isEqualTo(eur("100.00"));
        assertThat(reload(fees).booked()).isEqualTo(eur("0"));

        // The original is still there — corrections never erase history.
        assertThat(ledger.getEntry(fee.id()).postings()).hasSize(2);
        assertError(() -> ledger.reverse(new ReverseEntryCommand(key(), fee.id(), null)), LedgerError.ENTRY_ALREADY_REVERSED);
        assertError(() -> ledger.reverse(new ReverseEntryCommand(key(), reversal.id(), null)), LedgerError.ENTRY_NOT_REVERSIBLE);
    }

    @Test
    void direct_debit_beyond_available_is_refused_with_AM04_and_nothing_is_written() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        fund(customer, nostro, "50.00");
        long versionBefore = reload(customer).version();

        assertError(() -> ledger.post(new PostEntryCommand(key(), EntryType.SETTLE, null, null, null,
                List.of(new PostingLine(customer.id(), Direction.D, eur("50.01")),
                        new PostingLine(nostro.id(), Direction.C, eur("50.01"))))), LedgerError.INSUFFICIENT_FUNDS);

        assertThat(reload(customer).booked()).isEqualTo(eur("50.00"));
        assertThat(reload(customer).version()).isEqualTo(versionBefore);
    }

    @Test
    void blocked_account_can_receive_but_not_pay() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        fund(customer, nostro, "100.00");
        jdbc.sql("UPDATE ledger.account SET status = 'BLOCKED' WHERE id = :id").param("id", customer.id()).update();

        fund(customer, nostro, "1.00"); // credits still allowed
        assertError(() -> ledger.reserve(new ReserveFundsCommand(key(), customer.id(), eur("1.00"), null, null)),
                LedgerError.ACCOUNT_BLOCKED);
    }

    @Test
    void same_key_same_request_is_replayed_without_posting_twice() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        fund(customer, nostro, "100.00");
        String key = key();
        ReserveFundsCommand cmd = new ReserveFundsCommand(key, customer.id(), eur("30"), null, "hold");

        Idempotent<ReservationView> first = ledger.reserve(cmd);
        // "30" and "30.00" are the same amount: still the same request.
        Idempotent<ReservationView> second = ledger.reserve(
                new ReserveFundsCommand(key, customer.id(), eur("30.00"), null, "hold"));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.value().id()).isEqualTo(first.value().id());
        assertThat(reload(customer).reserved()).isEqualTo(eur("30.00"));
    }

    @Test
    void same_key_different_request_is_rejected() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        fund(customer, nostro, "100.00");
        String key = key();
        ledger.reserve(new ReserveFundsCommand(key, customer.id(), eur("30.00"), null, null));

        assertError(() -> ledger.reserve(new ReserveFundsCommand(key, customer.id(), eur("31.00"), null, null)),
                LedgerError.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void statement_shows_booked_lines_with_running_balance_and_active_holds() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        AccountView fees = openFeeIncome("EUR");
        fund(customer, nostro, "1000.00");
        ledger.post(new PostEntryCommand(key(), EntryType.FEE, null, null, "Fee",
                List.of(new PostingLine(customer.id(), Direction.D, eur("20.00")),
                        new PostingLine(fees.id(), Direction.C, eur("20.00")))));
        ledger.reserve(new ReserveFundsCommand(key(), customer.id(), eur("100.00"), null, "Warehoused payment"));

        StatementView st = ledger.statement(customer.id(), null);

        assertThat(st.timeZone()).isEqualTo("Asia/Kuala_Lumpur");
        assertThat(st.openingBooked()).isEqualTo(eur("0"));
        assertThat(st.lines()).hasSize(2); // the hold is not a booked line
        assertThat(st.lines().get(0).credit()).isEqualTo(eur("1000.00"));
        assertThat(st.lines().get(0).contraAccounts()).isEqualTo(nostro.accountNumber());
        assertThat(st.lines().get(1).debit()).isEqualTo(eur("20.00"));
        assertThat(st.lines().get(1).balance()).isEqualTo(eur("980.00"));
        assertThat(st.totalCredits()).isEqualTo(eur("1000.00"));
        assertThat(st.totalDebits()).isEqualTo(eur("20.00"));
        assertThat(st.closingBooked()).isEqualTo(eur("980.00"));
        assertThat(st.activeHolds()).singleElement()
                .satisfies(h -> assertThat(h.amount()).isEqualTo(eur("100.00")));

        StatementView yesterday = ledger.statement(customer.id(), st.date().minusDays(1));
        assertThat(yesterday.lines()).isEmpty();
        assertThat(yesterday.closingBooked()).isEqualTo(eur("0"));
    }

    @Test
    void jpy_has_no_decimals() {
        AccountView customer = openCustomer("JPY");
        AccountView nostro = openNostro("JPY");
        fund(customer, nostro, "150000");
        assertThat(reload(customer).booked().amount().toPlainString()).isEqualTo("150000");
    }
}
