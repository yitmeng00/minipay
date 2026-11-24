package com.minipay.ledger.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.BalanceType;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.LedgerError;
import com.minipay.ledger.api.LedgerException;
import com.minipay.ledger.api.AccountStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalEntryDraftTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    private static JournalEntryDraft draft(EntryType type, UUID reverses, NewPosting... postings) {
        return new JournalEntryDraft(UUID.randomUUID(), type, "k", "h", null, reverses, TODAY, null, List.of(postings));
    }

    private static NewPosting p(long account, Direction d, String amount, String ccy, BalanceType bt) {
        return new NewPosting(account, d, Money.of(amount, ccy), bt);
    }

    @Test
    void balanced_entry_is_accepted() {
        assertThatCode(() -> draft(EntryType.SETTLE, null,
                p(1, Direction.D, "100.00", "EUR", BalanceType.BOOKED),
                p(2, Direction.C, "99.00", "EUR", BalanceType.BOOKED),
                p(3, Direction.C, "1.00", "EUR", BalanceType.BOOKED)))
                .doesNotThrowAnyException();
    }

    @Test
    void unbalanced_entry_is_rejected() {
        assertThatThrownBy(() -> draft(EntryType.SETTLE, null,
                p(1, Direction.D, "100.00", "EUR", BalanceType.BOOKED),
                p(2, Direction.C, "99.99", "EUR", BalanceType.BOOKED)))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("debits - credits = 0.01")
                .extracting(e -> ((LedgerException) e).error()).isEqualTo(LedgerError.UNBALANCED_ENTRY);
    }

    @Test
    void entries_balance_per_currency_not_in_total() {
        // EUR 100 debit against USD 100 credit "adds up" numerically but is NOT balanced:
        // you cannot net different currencies without an explicit FX step.
        assertThatThrownBy(() -> draft(EntryType.SETTLE, null,
                p(1, Direction.D, "100.00", "EUR", BalanceType.BOOKED),
                p(2, Direction.C, "100.00", "USD", BalanceType.BOOKED)))
                .hasMessageContaining("EUR BOOKED")
                .hasMessageContaining("USD BOOKED");
    }

    @Test
    void a_single_posting_is_not_double_entry() {
        assertThatThrownBy(() -> draft(EntryType.SETTLE, null, p(1, Direction.D, "1.00", "EUR", BalanceType.BOOKED)))
                .hasMessageContaining("at least two postings");
    }

    @Test
    void reserve_entries_must_not_touch_booked_balances() {
        assertThatThrownBy(() -> draft(EntryType.RESERVE, null,
                p(1, Direction.D, "1.00", "EUR", BalanceType.BOOKED),
                p(2, Direction.C, "1.00", "EUR", BalanceType.BOOKED)))
                .hasMessageContaining("RESERVED");
    }

    @Test
    void only_reversals_reference_a_reversed_entry() {
        assertThatThrownBy(() -> draft(EntryType.REVERSAL, null,
                p(1, Direction.D, "1.00", "EUR", BalanceType.BOOKED),
                p(2, Direction.C, "1.00", "EUR", BalanceType.BOOKED)))
                .hasMessageContaining("REVERSAL");
        assertThatThrownBy(() -> draft(EntryType.SETTLE, UUID.randomUUID(),
                p(1, Direction.D, "1.00", "EUR", BalanceType.BOOKED),
                p(2, Direction.C, "1.00", "EUR", BalanceType.BOOKED)))
                .hasMessageContaining("REVERSAL");
    }

    @Test
    void posting_amounts_must_be_positive() {
        assertThatThrownBy(() -> p(1, Direction.D, "0.00", "EUR", BalanceType.BOOKED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p(1, Direction.D, "-5.00", "EUR", BalanceType.BOOKED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void debit_lowers_a_customer_but_raises_a_nostro() {
        Money hundred = Money.of("100.00", "EUR");
        AccountBalance customer = new AccountBalance(1, "C", AccountType.CUSTOMER, AccountStatus.ACTIVE, false,
                Money.of("500.00", "EUR"), Money.zero("EUR"), 0);
        AccountBalance nostro = new AccountBalance(2, "N", AccountType.NOSTRO, AccountStatus.ACTIVE, false,
                Money.of("500.00", "EUR"), Money.zero("EUR"), 0);

        customer.apply(new NewPosting(1, Direction.D, hundred, BalanceType.BOOKED));
        nostro.apply(new NewPosting(2, Direction.D, hundred, BalanceType.BOOKED));

        assertThat(customer.booked()).isEqualTo(Money.of("400.00", "EUR")); // liability: we owe less
        assertThat(nostro.booked()).isEqualTo(Money.of("600.00", "EUR"));   // asset: we hold more
    }

    @Test
    void a_hold_reduces_available_but_not_booked() {
        AccountBalance customer = new AccountBalance(1, "C", AccountType.CUSTOMER, AccountStatus.ACTIVE, false,
                Money.of("500.00", "EUR"), Money.zero("EUR"), 0);
        customer.apply(new NewPosting(1, Direction.D, Money.of("120.00", "EUR"), BalanceType.RESERVED));

        assertThat(customer.booked()).isEqualTo(Money.of("500.00", "EUR"));
        assertThat(customer.reserved()).isEqualTo(Money.of("120.00", "EUR"));
        assertThat(customer.available()).isEqualTo(Money.of("380.00", "EUR"));

        customer.apply(new NewPosting(1, Direction.C, Money.of("120.00", "EUR"), BalanceType.RESERVED));
        assertThat(customer.available()).isEqualTo(Money.of("500.00", "EUR"));
    }
}
