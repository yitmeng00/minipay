package com.minipay.recon.internal;

import com.minipay.common.id.Bic;
import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.StatementLine;
import com.minipay.messaging.api.Idempotent;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.StatementTx;
import com.minipay.paymenthub.api.NetworkOutbox;
import com.minipay.paymenthub.api.PaymentHubApi;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.settlement.api.SettlementOperatorApi;
import com.minipay.settlement.api.TransferView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The servicer's side (ADR-009): an end-of-day camt.053 for every account we keep for another bank on the network.
 * For a bank that is each vostro; for the central bank, each participant's T2 and instant account.
 *
 * <p>Each entry names the payment(s) behind it (UETR, end-to-end id), which our ledger alone does not know: the hub
 * does for a bank's payments, the settlement systems for the central bank's transfers. A DNS cycle's net booking is
 * one entry listing every payment of the cycle that concerns the account holder (a batch booking).
 *
 * <p>Once per account and date: the statement is issued under the key {@code camt053:<account>:<date>} and put on
 * the network in the same transaction. A second publish for that date replays and sends nothing.
 */
@Component
class StatementPublisher {

    private static final Logger log = LoggerFactory.getLogger(StatementPublisher.class);

    private final LedgerApi ledger;
    private final MessagingApi messaging;
    private final NetworkOutbox network;
    private final PaymentHubApi hub;
    private final SettlementOperatorApi settlement;
    private final TransactionTemplate tx;

    StatementPublisher(LedgerApi ledger, MessagingApi messaging, NetworkOutbox network, PaymentHubApi hub,
                       SettlementOperatorApi settlement, TransactionTemplate tx) {
        this.ledger = ledger;
        this.messaging = messaging;
        this.network = network;
        this.hub = hub;
        this.settlement = settlement;
        this.tx = tx;
    }

    /** @return the accounts whose statement for that date was sent now (not before) */
    List<String> publish(LocalDate date) {
        List<String> sent = new ArrayList<>();
        for (AccountView a : ledger.listAccounts(AccountType.VOSTRO)) {
            if (a.status() == AccountStatus.CLOSED || !network.isParticipant(a.counterpartyBic())) {
                continue;
            }
            Boolean fresh = tx.execute(s -> {
                Idempotent<MessageView> m = messaging.issueCamt053("camt053:" + a.accountNumber() + ":" + date, a.id(), date,
                        line -> details(line, a.counterpartyBic()));
                if (!m.replayed()) {
                    network.deliver("statement", m.value().id(), m.value());
                }
                return !m.replayed();
            });
            if (Boolean.TRUE.equals(fresh)) {
                sent.add(a.accountNumber());
            }
        }
        if (!sent.isEmpty()) {
            log.info("camt.053 for {} sent: {}", date, sent);
        }
        return sent;
    }

    /** The payments behind one booking on the account, from the holder's point of view. */
    private List<StatementTx> details(StatementLine line, String holderBic) {
        if (settlement.isOperator()) {
            List<TransferView> ts = line.paymentId() != null
                    ? settlement.findTransfer(line.paymentId()).map(List::of).orElse(List.of())
                    : settlement.transfersSettledBy(line.journalEntryId());
            String holder8 = Bic.parse(holderBic).bic8();
            return ts.stream()
                    .filter(t -> t.payerBic().startsWith(holder8) || t.payeeBic().startsWith(holder8))
                    .map(t -> new StatementTx(t.uetr().toString(), t.endToEndId(), t.instructionId(), t.amount(),
                            t.payeeBic().startsWith(holder8))).toList();
        }
        if (line.paymentId() == null) {
            return List.of();
        }
        Optional<PaymentView> p;
        try {
            p = Optional.of(hub.get(line.paymentId().toString()).payment());
        } catch (RuntimeException e) {
            p = Optional.empty();
        }
        boolean credit = line.credit() != null;
        return p.map(x -> List.of(new StatementTx(x.uetr(), x.endToEndId(), x.instructionId(),
                credit ? line.credit() : line.debit(), credit))).orElse(List.of());
    }
}
