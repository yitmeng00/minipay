package com.minipay.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.OpenAccountCommand;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.Idempotent;
import com.minipay.messaging.api.InboundMessage;
import com.minipay.messaging.api.MessageDirection;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.MessagingError;
import com.minipay.messaging.api.MessagingException;
import com.minipay.messaging.api.SettlementMethod;
import com.minipay.messaging.api.StatusReport;
import com.minipay.messaging.api.TransactionStatus;
import com.minipay.messaging.api.Translation;
import com.prowidesoftware.swift.model.mx.AbstractMX;
import com.prowidesoftware.swift.model.mx.MxCamt05300108;
import com.prowidesoftware.swift.model.mx.dic.AccountStatement9;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The message store against real PostgreSQL. Messages are append-only, so tests never clean up:
 * each uses its own UETR / business key / accounts.
 */
@SpringBootTest(classes = MessagingTestApplication.class)
class MessageStoreIT {

    @Autowired
    MessagingApi messaging;

    @Autowired
    LedgerApi ledger;

    @Autowired
    JdbcClient jdbc;

    /** Bank B sends a payment on to Bank C: a pacs.008 from us. */
    static CreditTransfer outgoing(String uetr) {
        CreditTransfer f = Fixtures.transfer();
        return new CreditTransfer(uetr, f.instructionId(), f.endToEndId(), "MNPBDEFFXXX", "MNPCGB2LXXX", f.debtorAgentBic(),
                f.creditorAgentBic(), f.debtorName(), f.debtorAccount(), f.creditorName(), f.creditorAccount(),
                f.interbankAmount(), f.instructedAmount(), f.charges(), f.chargeBearer(), f.settlementDate(),
                SettlementMethod.INDA, null, List.of("MNPAMYKLXXX"), f.remittanceInformation());
    }

    static String uetr() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ outbound

    @Test
    void an_outbound_pacs008_is_stored_raw_with_its_parsed_fields_and_built_once_per_purpose() {
        String uetr = uetr();
        String key = "pacs.008:" + UUID.randomUUID();
        MessageView first = messaging.issuePacs008(key, outgoing(uetr));
        MessageView again = messaging.issuePacs008(key, outgoing(uetr));

        assertThat(again).isEqualTo(first); // a retried step gets the same message, same MsgId
        assertThat(first.direction()).isEqualTo(MessageDirection.OUTBOUND);
        assertThat(first.type()).isEqualTo(MessageType.PACS_008);
        assertThat(first.msgId()).matches("MNPB20261124-008-\\d{6}");
        assertThat(first.receiverBic()).isEqualTo("MNPCGB2LXXX");
        assertThat(first.uetr()).isEqualTo(uetr);
        assertThat(first.amount()).isEqualTo(Money.of("48250.00", "EUR"));
        String raw = messaging.get(first.id()).raw();
        assertThat(raw).contains("<pacs:UETR>" + uetr + "</pacs:UETR>");
        assertThat(first.sha256()).isEqualTo(com.minipay.messaging.internal.persistence.MessageRepository.sha256(raw));
        assertThat(messaging.forUetr(uetr)).containsExactly(first);
    }

    @Test
    void racing_workers_issue_exactly_one_message_for_one_purpose() throws Exception {
        String uetr = uetr();
        String key = "pacs.008:" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MessageView>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return messaging.issuePacs008(key, outgoing(uetr));
                }));
            }
            go.countDown();
            List<MessageView> results = new ArrayList<>();
            for (Future<MessageView> f : runs) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).extracting(MessageView::id).containsOnly(results.get(0).id());
        } finally {
            pool.shutdown();
        }
        assertThat(messaging.forUetr(uetr)).hasSize(1);
    }

    @Test
    void stored_messages_cannot_be_changed_or_faked() {
        MessageView m = messaging.issuePacs008("pacs.008:" + UUID.randomUUID(), outgoing(uetr()));
        assertThatThrownBy(() -> jdbc.sql("UPDATE messaging.message SET raw = raw || ' ' WHERE id = :id").param("id", m.id()).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM messaging.message WHERE id = :id").param("id", m.id()).update())
                .hasMessageContaining("append-only");
        // A row whose hash does not match its raw text is refused by the database.
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO messaging.message (id, direction, message_type, raw, raw_sha256)
                VALUES (gen_random_uuid(), 'INBOUND', 'pacs.002.001.10', '<x/>', repeat('0', 64))
                """).update()).isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------ inbound

    /** Bank C acknowledges the payment we (Bank B) sent it. */
    String statusFromBankC(String uetr, TransactionStatus status, String reason) {
        return messaging.buildPacs002(new StatusReport("MNPCGB2LXXX", "MNPBDEFFXXX", "MNPB20261124-008-000001",
                "MNPA261124000912", "INV-HF-2026-1187", uetr, status, reason, null));
    }

    @Test
    void an_inbound_message_is_stored_and_its_resend_is_recognised_as_a_duplicate() {
        String uetr = uetr();
        String raw = statusFromBankC(uetr, TransactionStatus.ACCP, null);

        InboundMessage first = messaging.receive(raw);
        InboundMessage resent = messaging.receive(raw);

        assertThat(first.valid()).isTrue();
        assertThat(first.duplicate()).isFalse();
        assertThat(first.status()).isEqualTo("ACCP");
        assertThat(first.originalUetr()).isEqualTo(uetr);
        assertThat(first.message().senderBic()).isEqualTo("MNPCGB2LXXX");
        assertThat(resent.duplicate()).isTrue();
        assertThat(resent.message().id()).isEqualTo(first.message().id());
        assertThat(messaging.forUetr(uetr)).hasSize(1);
        assertThat(messaging.get(first.message().id()).raw()).isEqualTo(raw); // byte for byte
    }

    @Test
    void an_invalid_message_is_kept_as_evidence_with_its_errors() {
        String uetr = uetr();
        // Addressed to Bank A, not to us, and a rejection without a reason.
        String raw = messaging.buildPacs002(new StatusReport("MNPCGB2LXXX", "MNPAMYKLXXX", "M1", "I1", "E1", uetr,
                TransactionStatus.RJCT, null, null));
        InboundMessage m = messaging.receive(raw);
        assertThat(m.valid()).isFalse();
        assertThat(m.message().status()).isEqualTo("INVALID");
        assertThat(m.message().errors()).hasSize(2)
                .anyMatch(e -> e.startsWith("Addressed to MNPAMYKLXXX"))
                .anyMatch(e -> e.startsWith("StsRsnInf/Rsn/Cd"));
        assertThat(messaging.get(m.message().id()).raw()).isEqualTo(raw);
    }

    @Test
    void unreadable_input_is_kept_too() {
        InboundMessage m = messaging.receive("not xml at all " + UUID.randomUUID());
        assertThat(m.valid()).isFalse();
        assertThat(m.message().type()).isNull();
        assertThat(messaging.get(m.message().id()).message().errors()).isNotEmpty();
    }

    // ------------------------------------------------------------------ translation

    @Test
    void a_stored_pacs008_translates_to_mt103_and_back() {
        MessageView m = messaging.issuePacs008("pacs.008:" + UUID.randomUUID(), outgoing(uetr()));
        Translation mt = messaging.toMt103(m.id());
        assertThat(mt.target()).contains(":32A:261124EUR48250,00");
        assertThat(mt.lost()).isPositive();
        Translation mx = messaging.fromMt103(mt.target());
        assertThat(mx.target()).contains("<pacs:UETR>" + m.uetr() + "</pacs:UETR>").contains("NOTPROVIDED");
        assertThatThrownBy(() -> messaging.fromMt103("{1:F01MNPAMYKLXXXX0000000000}{2:I103MNPBDEFFXXXXN}{4:\n:20:X\n-}"))
                .isInstanceOf(MessagingException.class);
    }

    // ------------------------------------------------------------------ camt.053

    @Test
    void a_vostro_statement_is_a_camt053_to_the_bank_that_owns_the_account() {
        AccountView t2 = open(AccountType.NOSTRO, "MNCBDEFFXXX", true);
        AccountView vostro = open(AccountType.VOSTRO, "MNPAMYKLXXX", false);
        post(t2, vostro, "1000.00");        // Bank A's account is credited 1,000.00
        post(vostro, t2, "250.00");         // ... and debited 250.00

        // Postings carry the database's real booking time, while this test's clock is fixed: ask for
        // the statement of the day they were actually booked (Frankfurt time).
        java.time.LocalDate booked = jdbc.sql("SELECT (now() AT TIME ZONE 'Europe/Berlin')::date").query(java.time.LocalDate.class).single();
        Idempotent<MessageView> issued = messaging.issueCamt053("stmt-" + UUID.randomUUID(), vostro.id(), booked);
        MessageView m = issued.value();
        assertThat(m.type()).isEqualTo(MessageType.CAMT_053);
        assertThat(m.receiverBic()).isEqualTo("MNPAMYKLXXX");
        assertThat(m.account()).isEqualTo(vostro.accountNumber());
        assertThat(m.amount()).isEqualTo(Money.of("750.00", "EUR")); // closing booked balance

        MxCamt05300108 mx = (MxCamt05300108) AbstractMX.parse(messaging.get(m.id()).raw());
        AccountStatement9 s = mx.getBkToCstmrStmt().getStmt().get(0);
        assertThat(s.getNtry()).hasSize(2);
        assertThat(s.getNtry().get(0).getCdtDbtInd().name()).isEqualTo("CRDT");
        assertThat(s.getNtry().get(1).getCdtDbtInd().name()).isEqualTo("DBIT");
        assertThat(s.getBal()).extracting(b -> b.getTp().getCdOrPrtry().getCd()).containsExactly("OPBD", "CLBD");
        assertThat(s.getElctrncSeqNb()).isEqualByComparingTo("1");
        // The next statement for the account carries the next sequence number.
        MessageView second = messaging.issueCamt053("stmt-" + UUID.randomUUID(), vostro.id(), booked).value();
        MxCamt05300108 mx2 = (MxCamt05300108) AbstractMX.parse(messaging.get(second.id()).raw());
        assertThat(mx2.getBkToCstmrStmt().getStmt().get(0).getElctrncSeqNb()).isEqualByComparingTo("2");
    }

    @Test
    void statement_requests_are_idempotent_and_only_for_accounts_we_keep_for_others() {
        AccountView t2 = open(AccountType.NOSTRO, "MNCBDEFFXXX", true);
        AccountView customer = open(AccountType.CUSTOMER, null, false);
        String key = "stmt-" + UUID.randomUUID();
        Idempotent<MessageView> first = messaging.issueCamt053(key, customer.id(), null);
        Idempotent<MessageView> again = messaging.issueCamt053(key, customer.id(), null);
        assertThat(again.replayed()).isTrue();
        assertThat(again.value()).isEqualTo(first.value());
        assertThat(first.value().receiverBic()).isNull(); // to the customer's channel, not over the network
        assertThatThrownBy(() -> messaging.issueCamt053(key, t2.id(), null))
                .extracting("error").isEqualTo(MessagingError.IDEMPOTENCY_KEY_REUSED);
        assertThatThrownBy(() -> messaging.issueCamt053("stmt-" + UUID.randomUUID(), t2.id(), null))
                .extracting("error").isEqualTo(MessagingError.NOT_STATEMENTABLE);
    }

    // ------------------------------------------------------------------ helpers

    private AccountView open(AccountType type, String bic, boolean overdraft) {
        String number = "M" + Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE), 36).toUpperCase();
        return ledger.openAccount(new OpenAccountCommand(number, type, "EUR", type + " test account", bic, overdraft));
    }

    /** Moves money: debit {@code from}, credit {@code to}. */
    private void post(AccountView from, AccountView to, String amount) {
        Money m = Money.of(amount, "EUR");
        ledger.post(new PostEntryCommand("t-" + UUID.randomUUID(), EntryType.SETTLE, null, null, "Test transfer",
                List.of(new PostingLine(from.id(), Direction.D, m), new PostingLine(to.id(), Direction.C, m))));
    }
}
