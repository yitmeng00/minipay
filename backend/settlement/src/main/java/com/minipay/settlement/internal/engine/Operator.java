package com.minipay.settlement.internal.engine;

import com.minipay.common.id.Bic;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.StatusReport;
import com.minipay.messaging.api.TransactionStatus;
import com.minipay.paymenthub.api.NetworkOutbox;
import com.minipay.refdata.api.ClearingSystemView;
import com.minipay.refdata.api.RefDataApi;
import com.minipay.refdata.api.RoutingMethod;
import com.minipay.settlement.api.TransferStatus;
import com.minipay.settlement.api.TransferView;
import com.minipay.settlement.internal.config.SettlementProperties;
import com.minipay.settlement.internal.persistence.SettlementRepository;
import com.minipay.settlement.internal.persistence.SettlementRepository.ParticipantRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What every engine needs: the central bank's books (participants' accounts on the operator's ledger), the
 * systems' reference data, the clock, and the way out to the network.
 *
 * <p>Unlike the payment hub, which calls a ledger that stands for a separate core-banking system, the
 * operator's ledger <em>is</em> the system's own books: a settlement, its status and the messages that announce
 * it are one transaction. Either the money moved and everybody is told, or neither (ADR-008 §5).
 */
@Component
public class Operator {

    final SettlementRepository repo;
    final LedgerApi ledger;
    final RefDataApi refdata;
    final MessagingApi messaging;
    final NetworkOutbox network;
    final SettlementProperties props;
    final Clock clock;
    final String ownBic;

    Operator(SettlementRepository repo, LedgerApi ledger, RefDataApi refdata, MessagingApi messaging, NetworkOutbox network,
             SettlementProperties props, ObjectProvider<Clock> clock, @Value("${minipay.bank.bic}") String ownBic) {
        this.repo = repo;
        this.ledger = ledger;
        this.refdata = refdata;
        this.messaging = messaging;
        this.network = network;
        this.props = props;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.ownBic = Bic.parse(ownBic).value();
    }

    public com.minipay.messaging.api.MessagingApi messaging() {
        return messaging;
    }

    public LedgerApi ledger() {
        return ledger;
    }

    public RefDataApi refdata() {
        return refdata;
    }

    public Instant now() {
        return clock.instant();
    }

    /** The system's business date: today in its own time zone. */
    public LocalDate today(ClearingSystemView system) {
        return LocalDate.now(clock.withZone(ZoneId.of(system.timeZone())));
    }

    public ClearingSystemView system(RoutingMethod kind, String currency) {
        return refdata.clearingSystem(kind, currency, LocalDate.now(clock.withZone(ZoneId.of("UTC"))))
                .orElseThrow(() -> new IllegalStateException("No " + kind + " system for " + currency));
    }

    public ClearingSystemView system(String code) {
        return refdata.clearingSystem(code, LocalDate.now(clock.withZone(ZoneId.of("UTC"))))
                .orElseThrow(() -> new IllegalStateException("No clearing system " + code));
    }

    public Optional<ParticipantRow> participant(String bic) {
        if (bic == null || !Bic.isValid(bic)) {
            return Optional.empty();
        }
        String bic8 = Bic.parse(bic).bic8();
        return repo.participants().stream().filter(p -> p.bic().startsWith(bic8)).findFirst();
    }

    public AccountView rtgsAccount(String bic) {
        return account(SettlementProperties.rtgsAccount(bic));
    }

    public AccountView instantAccount(String bic) {
        return account(SettlementProperties.instantAccount(bic));
    }

    private AccountView account(String number) {
        return ledger.findAccountByNumber(number).orElseThrow(() -> new IllegalStateException(
                "The operator has no account " + number + " (minipay.seed.accounts of the operator profile)"));
    }

    // ------------------------------------------------------------------ history and messages

    public void record(TransferView t, TransferStatus to, String detail, String actor) {
        repo.event(t.id(), t.status() == to ? null : t.status(), to, detail, actor);
    }

    /**
     * V-shape: a settled payment goes on to the payee as the payer sent it. Same Document, byte for byte, under
     * our AppHdr (Mx.readdressed). Must run in the settlement transaction.
     */
    public MessageView forward(TransferView t) {
        MessageView m = messaging.relay("op:fwd:" + t.id(), t.messageId(), t.payeeBic());
        network.deliver("transfer", t.id(), m);
        return m;
    }

    /** A pacs.002 about the transfer's payment, to {@code to} (the payer, or for instant also the payee). */
    public MessageView status(TransferView t, String to, TransactionStatus status, String reason, String info) {
        MessageView m = messaging.issuePacs002("op:pacs.002:" + status.name().toLowerCase() + ":" + Bic.parse(to).bic8() + ":" + t.id(),
                new StatusReport(ownBic, Bic.parse(to).value(), t.msgId(), t.instructionId(), t.endToEndId(),
                        t.uetr().toString(), status, reason, info == null ? null : info.length() > 105 ? info.substring(0, 105) : info));
        network.deliver("transfer", t.id(), m);
        return m;
    }

    public boolean simulated(String bic) {
        return participant(bic).map(ParticipantRow::simulated).orElse(false);
    }
}
