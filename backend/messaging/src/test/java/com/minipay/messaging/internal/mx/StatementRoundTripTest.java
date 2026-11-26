package com.minipay.messaging.internal.mx;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.StatementLine;
import com.minipay.ledger.api.StatementView;
import com.minipay.messaging.api.BankStatement;
import com.minipay.messaging.api.StatementTx;
import com.minipay.messaging.Fixtures;
import com.prowidesoftware.swift.model.mx.AbstractMX;
import com.prowidesoftware.swift.model.mx.MxCamt05300108;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A statement as the servicer writes it and as the account owner reads it (M8). */
class StatementRoundTripTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 26);
    static final Money ZERO = Money.of("0.00", "EUR");

    @Test
    void entries_carry_their_payments_references_and_a_batch_lists_every_payment_in_it() {
        AccountView acct = new AccountView(7, "RTGS-MNPBDEFF", AccountType.VOSTRO, "EUR", "T2 · Bank B", "MNPBDEFFXXX",
                AccountStatus.ACTIVE, false, Money.of("1000.00", "EUR"), ZERO, Money.of("1000.00", "EUR"), 1);
        StatementLine single = new StatementLine(11, Instant.parse("2026-11-26T09:00:00Z"), UUID.randomUUID(), EntryType.SETTLE,
                UUID.randomUUID(), DAY, "T2 pacs.008", "RTGS-MNPCGB2L", Money.of("5000.00", "EUR"), null, Money.of("-5000.00", "EUR"));
        StatementLine batch = new StatementLine(12, Instant.parse("2026-11-26T11:00:00Z"), UUID.randomUUID(), EntryType.SETTLE,
                null, DAY, "MiniPay Net cycle MPNET-20261126-01", "CB", null, Money.of("1000.00", "EUR"), Money.of("-4000.00", "EUR"));
        StatementView s = new StatementView(acct, DAY, "Europe/Berlin", Money.of("1000.00", "EUR"), Money.of("5000.00", "EUR"), 1,
                Money.of("1000.00", "EUR"), 1, Money.of("-3000.00", "EUR"), List.of(single, batch), List.of());
        String u1 = "6b1e93d0-4a27-4c58-9e10-d3f82a7c5b61", u2 = "a03c7e58-91d2-4f6b-b847-2e9d05c1f3a8", u3 = "d7f2b046-3c81-4e9a-a5d3-80b6e1f47c29";

        String raw = Mx.render(Camt053Writer.build(s, "MNCBDEFFXXX", "MNPBDEFFXXX", "MNCB20261126-053-000001", "RTGS-MNPBDEFF-20261126-1", 1,
                Fixtures.CREATED, ZoneId.of("Europe/Berlin"), line -> line == single
                        ? List.of(new StatementTx(u1, "E2E-1", "I1", Money.of("5000.00", "EUR"), false))
                        : List.of(new StatementTx(u2, "E2E-2", "I2", Money.of("2000.00", "EUR"), false),
                                new StatementTx(u3, "E2E-3", "I3", Money.of("3000.00", "EUR"), true))));
        assertThat(MxReader.read(raw, "MNPBDEFFXXX").errors()).isEmpty();

        BankStatement read = StatementReader.read((MxCamt05300108) AbstractMX.parse(raw), raw);
        assertThat(read.servicerBic()).isEqualTo("MNCBDEFFXXX");
        assertThat(read.accountNumber()).isEqualTo("RTGS-MNPBDEFF");
        assertThat(read.openingBooked()).isEqualTo(Money.of("1000.00", "EUR"));
        assertThat(read.closingBooked()).isEqualTo(Money.of("-3000.00", "EUR"));
        assertThat(read.entries()).hasSize(2);
        BankStatement.Entry e1 = read.entries().get(0);
        assertThat(e1.credit()).isFalse();
        assertThat(e1.txs()).singleElement().satisfies(t -> {
            assertThat(t.uetr()).isEqualTo(u1);
            assertThat(t.amount()).isEqualTo(Money.of("5000.00", "EUR"));
        });
        assertThat(e1.xml()).containsPattern("UETR>" + u1 + "<").doesNotContain(u2);
        BankStatement.Entry e2 = read.entries().get(1);
        assertThat(e2.credit()).isTrue();
        assertThat(e2.txs()).extracting(StatementTx::uetr, StatementTx::credit)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(u2, false), org.assertj.core.groups.Tuple.tuple(u3, true));
        assertThat(raw).containsPattern("NbOfTxs>2<");
    }
}
