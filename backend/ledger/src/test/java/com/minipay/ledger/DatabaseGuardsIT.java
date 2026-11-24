package com.minipay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.ledger.api.AccountView;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database enforces the ledger rules on its own, even if application code is bypassed.
 * These tests use raw SQL on purpose — they play the role of a buggy service or a manual "fix".
 */
class DatabaseGuardsIT extends LedgerIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private long anyPostingId(AccountView account) {
        return jdbc.sql("SELECT id FROM ledger.posting WHERE account_id = :a LIMIT 1")
                .param("a", account.id()).query(Long.class).single();
    }

    @Test
    void postings_cannot_be_updated_or_deleted() {
        AccountView customer = openCustomer("EUR");
        fund(customer, openNostro("EUR"), "10.00");
        long id = anyPostingId(customer);

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger.posting SET amount = 1000 WHERE id = :id").param("id", id).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger.posting WHERE id = :id").param("id", id).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void journal_entries_cannot_be_updated_deleted_or_truncated() {
        AccountView customer = openCustomer("EUR");
        fund(customer, openNostro("EUR"), "10.00");
        UUID entry = jdbc.sql("SELECT journal_entry_id FROM ledger.posting WHERE account_id = :a LIMIT 1")
                .param("a", customer.id()).query(UUID.class).single();

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger.journal_entry SET narrative = 'x' WHERE id = :id")
                .param("id", entry).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger.journal_entry WHERE id = :id")
                .param("id", entry).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE ledger.journal_entry CASCADE").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void an_unbalanced_entry_fails_at_commit() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        UUID entry = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertEntry(entry);
            insertPosting(entry, nostro.id(), "D", "100.00");
            insertPosting(entry, customer.id(), "C", "99.00");
            // Inside the transaction nothing has complained yet: the check is DEFERRED to commit.
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("unbalanced");

        assertThat(jdbc.sql("SELECT count(*) FROM ledger.journal_entry WHERE id = :id").param("id", entry)
                .query(Long.class).single()).isZero();
    }

    @Test
    void an_entry_without_postings_fails_at_commit() {
        UUID entry = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> insertEntry(entry)))
                .hasMessageContaining("at least 2");
    }

    @Test
    void a_balanced_entry_written_by_hand_is_accepted() {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        UUID entry = UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            insertEntry(entry);
            insertPosting(entry, nostro.id(), "D", "5.00");
            insertPosting(entry, customer.id(), "C", "5.00");
        });
        assertThat(jdbc.sql("SELECT count(*) FROM ledger.posting WHERE journal_entry_id = :id").param("id", entry)
                .query(Long.class).single()).isEqualTo(2);

        // The raw insert bypassed the balance update. The end-of-day integrity check exists to catch
        // exactly this: stored balances no longer equal the sum of postings.
        assertThat(ledger.checkIntegrity().balanceMismatches())
                .extracting(m -> m.accountId())
                .contains(customer.id(), nostro.id());

        // Repair (balances are derived data and may be corrected; postings never are) so that the
        // shared test database is consistent again for other tests.
        jdbc.sql("UPDATE ledger.account_balance SET booked = booked + 5 WHERE account_id IN (:ids)")
                .param("ids", java.util.List.of(customer.id(), nostro.id())).update();
        assertThat(ledger.checkIntegrity().balanceMismatches())
                .extracting(m -> m.accountId())
                .doesNotContain(customer.id(), nostro.id());
    }

    @Test
    void posting_currency_must_match_the_account() {
        AccountView customer = openCustomer("EUR");
        UUID entry = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            insertEntry(entry);
            jdbc.sql("""
                    INSERT INTO ledger.posting (journal_entry_id, account_id, direction, amount, currency, balance_type)
                    VALUES (:e, :a, 'D', 1, 'USD', 'BOOKED')
                    """).param("e", entry).param("a", customer.id()).update();
        })).hasMessageContaining("does not match");
    }

    @Test
    void posting_amount_must_be_positive() {
        AccountView customer = openCustomer("EUR");
        UUID entry = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            insertEntry(entry);
            insertPosting(entry, customer.id(), "D", "-1.00");
        })).hasMessageContaining("posting_amount_check");
    }

    private void insertEntry(UUID id) {
        jdbc.sql("""
                INSERT INTO ledger.journal_entry (id, idempotency_key, request_hash, entry_type, value_date)
                VALUES (:id, :key, repeat('0', 64), 'SETTLE', CURRENT_DATE)
                """).param("id", id).param("key", "raw-" + id).update();
    }

    private void insertPosting(UUID entry, long account, String direction, String amount) {
        jdbc.sql("""
                INSERT INTO ledger.posting (journal_entry_id, account_id, direction, amount, currency, balance_type)
                VALUES (:e, :a, :d, CAST(:amt AS NUMERIC), 'EUR', 'BOOKED')
                """).param("e", entry).param("a", account).param("d", direction).param("amt", amount).update();
    }
}
