package com.minipay.messaging.internal;

import com.minipay.common.id.Bic;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.StatementView;
import com.minipay.messaging.api.Charge;
import com.minipay.messaging.api.CoverTransfer;
import com.minipay.messaging.api.CreditNotification;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.Idempotent;
import com.minipay.messaging.api.InboundMessage;
import com.minipay.messaging.api.MessageDetail;
import com.minipay.messaging.api.MessageDirection;
import com.minipay.messaging.api.MessageSummary;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.MessagingError;
import com.minipay.messaging.api.MessagingException;
import com.minipay.messaging.api.PaymentReturn;
import com.minipay.messaging.api.StatusReport;
import com.minipay.messaging.api.Translation;
import com.minipay.messaging.internal.mt.Mt103Translator;
import com.minipay.messaging.internal.mx.Camt053Writer;
import com.minipay.messaging.internal.mx.Camt054Writer;
import com.minipay.messaging.internal.mx.Mx;
import com.minipay.messaging.internal.mx.MxReader;
import com.minipay.messaging.internal.mx.Pacs002Writer;
import com.minipay.messaging.internal.mx.Pacs004Writer;
import com.minipay.messaging.internal.mx.Pacs008Writer;
import com.minipay.messaging.internal.mx.Pacs009Writer;
import com.minipay.messaging.internal.mx.TransferReader;
import com.minipay.messaging.internal.persistence.MessageRepository;
import com.minipay.messaging.internal.persistence.MessageRepository.NewMessage;
import com.prowidesoftware.swift.model.mx.AbstractMX;
import com.prowidesoftware.swift.model.mx.MxCamt05400108;
import com.prowidesoftware.swift.model.mx.MxPacs00400109;
import com.prowidesoftware.swift.model.mx.MxPacs00800108;
import com.prowidesoftware.swift.model.mx.MxPacs00900108;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The message gateway (see {@link MessagingApi} and ADR-005).
 *
 * <h2>Two kinds of "same message"</h2>
 * <ul>
 *   <li><b>Outbound</b>, a business key ({@code pacs.008:<payment id>}) names what the message is for.
 *       A retried pipeline step asks again and gets the message it already built: same MsgId, same bytes.
 *       A message, once sent, is a fact; it is never rebuilt.</li>
 *   <li><b>Inbound</b>, the sender's MsgId identifies the message: banks never reuse one. A second
 *       arrival is a duplicate (a resend, or a replay). It is not stored twice, and the caller is told
 *       not to act on it.</li>
 * </ul>
 * Both are enforced by unique constraints, so two threads racing get the same answer.
 *
 * <h2>We check what we send</h2>
 * Every outbound MX message is read back by the same {@link MxReader} that checks inbound messages,
 * before it is stored. We never send what we would ourselves reject.
 */
@Service
public class MessagingService implements MessagingApi {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final MessageRepository repo;
    private final LedgerApi ledger;
    private final Clock clock;
    private final ZoneId bankZone;
    private final String ownBic;

    public MessagingService(MessageRepository repo, LedgerApi ledger, ObjectProvider<Clock> clock,
                            @Value("${minipay.bank.time-zone:UTC}") ZoneId bankZone,
                            @Value("${minipay.bank.bic}") String ownBic) {
        this.repo = repo;
        this.ledger = ledger;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.bankZone = bankZone;
        this.ownBic = Bic.parse(ownBic).value();
    }

    // ------------------------------------------------------------------ outbound

    @Override
    public MessageView issuePacs008(String businessKey, CreditTransfer t) {
        return issue(businessKey, MessageType.PACS_008, t.instructingAgentBic(),
                msgId -> Pacs008Writer.build(t, msgId, now()),
                (p, raw) -> new NewMessage(MessageDirection.OUTBOUND, MessageType.PACS_008, p.msgId(), p.from(), p.to(),
                        businessKey, t.uetr(), t.endToEndId(), t.instructionId(), null, t.creditorAccount(),
                        t.interbankAmount(), t.settlementDate(), null, null, List.of(), raw));
    }

    @Override
    public MessageView issuePacs009(String businessKey, CoverTransfer c) {
        return issue(businessKey, MessageType.PACS_009, c.instructingAgentBic(),
                msgId -> Pacs009Writer.build(c, msgId, now()),
                (p, raw) -> new NewMessage(MessageDirection.OUTBOUND, MessageType.PACS_009, p.msgId(), p.from(), p.to(),
                        businessKey, c.uetr(), c.endToEndId(), c.instructionId(), null, null, c.amount(),
                        c.settlementDate(), null, null, List.of(), raw));
    }

    @Override
    public MessageView issuePacs002(String businessKey, StatusReport r) {
        return issue(businessKey, MessageType.PACS_002, r.fromBic(),
                msgId -> Pacs002Writer.build(r, msgId, now()),
                (p, raw) -> new NewMessage(MessageDirection.OUTBOUND, MessageType.PACS_002, p.msgId(), p.from(), p.to(),
                        businessKey, r.originalUetr(), r.originalEndToEndId(), r.originalInstructionId(), r.originalMsgId(),
                        null, null, null, r.status().name(), r.reasonCode(), List.of(), raw));
    }

    @Override
    public MessageView issuePacs004(String businessKey, PaymentReturn r) {
        return issue(businessKey, MessageType.PACS_004, r.fromBic(),
                msgId -> Pacs004Writer.build(r, msgId, now()),
                (p, raw) -> new NewMessage(MessageDirection.OUTBOUND, MessageType.PACS_004, p.msgId(), p.from(), p.to(),
                        businessKey, r.originalUetr(), r.originalEndToEndId(), r.originalInstructionId(), r.originalMsgId(),
                        null, r.returnedAmount(), r.settlementDate(), "RTND", r.reasonCode(), List.of(), raw));
    }

    @Override
    public MessageView issueCamt054(String businessKey, CreditNotification n) {
        return issue(businessKey, MessageType.CAMT_054, n.fromBic(),
                msgId -> Camt054Writer.build(n, msgId, now(), bankZone),
                (p, raw) -> new NewMessage(MessageDirection.OUTBOUND, MessageType.CAMT_054, p.msgId(), p.from(), p.to(),
                        businessKey, n.uetr(), n.endToEndId(), n.instructionId(), null, n.account(), n.amount(),
                        n.valueDate(), "CRDT", null, List.of(), raw));
    }

    /**
     * Builds, self-checks and stores an outbound message once per business key. A second call (a retried
     * step, or a worker that lost a race) gets the stored message: same MsgId, same bytes.
     */
    private MessageView issue(String businessKey, MessageType type, String senderBic,
                              java.util.function.Function<String, AbstractMX> build,
                              java.util.function.BiFunction<MxReader.Parsed, String, NewMessage> row) {
        Optional<MessageView> existing = repo.findByBusinessKey(businessKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        String raw = Mx.render(build.apply(newMsgId(senderBic, type)));
        MxReader.Parsed p = selfCheck(raw);
        try {
            return repo.insert(row.apply(p, raw));
        } catch (DuplicateKeyException e) {
            return repo.findByBusinessKey(businessKey).orElseThrow(() -> e); // a concurrent retry won the race
        }
    }

    @Override
    public String renderPacs008(CreditTransfer t) {
        String msgId = bic4(t.instructingAgentBic()) + YYYYMMDD.format(today()) + "-008-PREVIEW";
        return Mx.render(Pacs008Writer.build(t, msgId, now()));
    }

    @Override
    public String buildPacs008(CreditTransfer t) {
        return Mx.render(Pacs008Writer.build(t, newMsgId(t.instructingAgentBic(), MessageType.PACS_008), now()));
    }

    @Override
    public String buildPacs009(CoverTransfer c) {
        return Mx.render(Pacs009Writer.build(c, newMsgId(c.instructingAgentBic(), MessageType.PACS_009), now()));
    }

    @Override
    public String buildCamt054(CreditNotification n) {
        return Mx.render(Camt054Writer.build(n, newMsgId(n.fromBic(), MessageType.CAMT_054), now(), bankZone));
    }

    @Override
    public String buildPacs002(StatusReport r) {
        return Mx.render(Pacs002Writer.build(r, newMsgId(r.fromBic(), MessageType.PACS_002), now()));
    }

    @Override
    public String buildPacs004(PaymentReturn r) {
        return Mx.render(Pacs004Writer.build(r, newMsgId(r.fromBic(), MessageType.PACS_004), now()));
    }

    // ------------------------------------------------------------------ inbound

    @Override
    public InboundMessage receive(String raw) {
        MxReader.Parsed p = MxReader.read(raw, ownBic);
        if (p.msgId() != null && p.from() != null) {
            Optional<MessageView> seen = repo.findBySender(MessageDirection.INBOUND, p.from(), p.msgId());
            if (seen.isPresent()) {
                return inbound(seen.get(), true, p);
            }
        }
        boolean invalid = !p.errors().isEmpty();
        try {
            MessageView stored = repo.insert(new NewMessage(MessageDirection.INBOUND, p.type(), p.msgId(),
                    Bic.isValid(p.from()) ? p.from() : null, Bic.isValid(p.to()) ? p.to() : null, null,
                    p.uetr(), p.endToEndId(), p.instructionId(), p.originalMsgId(), p.account(), p.amount(),
                    p.settlementDate(), invalid ? "INVALID" : p.status(), p.reasonCode(), p.errors(), raw));
            return inbound(stored, false, p);
        } catch (DuplicateKeyException e) {
            return inbound(repo.findBySender(MessageDirection.INBOUND, p.from(), p.msgId()).orElseThrow(() -> e), true, p);
        }
    }

    private static InboundMessage inbound(MessageView m, boolean duplicate, MxReader.Parsed p) {
        return new InboundMessage(m, duplicate, p.uetr(), p.status(), p.reasonCode(),
                p.type() == MessageType.PACS_004 ? p.amount() : null, p.additionalInfo());
    }

    // ------------------------------------------------------------------ queries

    @Override
    public MessageDetail get(UUID id) {
        return repo.find(id).orElseThrow(() -> new MessagingException(MessagingError.NOT_FOUND, "Message " + id + " not found"));
    }

    @Override
    public MessageSummary inspect(String raw) {
        MxReader.Parsed p = MxReader.read(raw, null);
        List<Charge> charges = List.of();
        String method = null;
        String creditorAgent = null;
        if (p.errors().isEmpty() && p.type() != null) {
            AbstractMX mx = AbstractMX.parse(raw);
            switch (p.type()) {
                case PACS_008 -> {
                    CreditTransfer t = TransferReader.pacs008((MxPacs00800108) mx);
                    charges = t.charges();
                    method = t.settlementMethod().name();
                    creditorAgent = t.creditorAgentBic();
                }
                case PACS_004 -> charges = TransferReader.pacs004((MxPacs00400109) mx).charges();
                case PACS_002 -> charges = TransferReader.pacs002Charges((com.prowidesoftware.swift.model.mx.MxPacs00200110) mx);
                case PACS_009 -> method = "INDA";
                default -> {
                }
            }
        }
        return new MessageSummary(p.type(), p.msgId(), p.from(), p.to(), p.uetr(), p.endToEndId(), p.originalMsgId(),
                p.amount(), p.settlementDate(), method, creditorAgent, p.status(), p.reasonCode(), charges, p.errors());
    }

    @Override
    public CreditTransfer creditTransfer(UUID id) {
        return TransferReader.pacs008((MxPacs00800108) parse(id, MessageType.PACS_008));
    }

    @Override
    public CoverTransfer coverTransfer(UUID id) {
        return TransferReader.pacs009((MxPacs00900108) parse(id, MessageType.PACS_009));
    }

    @Override
    public PaymentReturn paymentReturn(UUID id) {
        return TransferReader.pacs004((MxPacs00400109) parse(id, MessageType.PACS_004));
    }

    @Override
    public CreditNotification creditNotification(UUID id) {
        return TransferReader.camt054((MxCamt05400108) parse(id, MessageType.CAMT_054));
    }

    private AbstractMX parse(UUID id, MessageType expected) {
        MessageDetail d = get(id);
        if (d.message().type() != expected || !d.message().errors().isEmpty()) {
            throw new MessagingException(MessagingError.INVALID_MESSAGE, "Message " + id + " is not a valid " + expected.identifier());
        }
        return AbstractMX.parse(d.raw());
    }

    @Override
    public List<MessageView> forUetr(String uetr) {
        try {
            return repo.forUetr(UUID.fromString(uetr));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    @Override
    public List<MessageView> recent(MessageType type, int limit) {
        return repo.recent(type, Math.min(Math.max(limit, 1), 500));
    }

    // ------------------------------------------------------------------ translation

    @Override
    public Translation toMt103(UUID id) {
        MessageDetail d = get(id);
        if (d.message().type() != MessageType.PACS_008 || !d.message().errors().isEmpty()) {
            throw new MessagingException(MessagingError.INVALID_MESSAGE, "Only a valid pacs.008 translates to MT103");
        }
        MxPacs00800108 mx = (MxPacs00800108) AbstractMX.parse(d.raw());
        Mt103Translator.ToMt t = Mt103Translator.toMt103(mx);
        return new Translation(MessageType.PACS_008, MessageType.MT103, t.fin(), t.fields());
    }

    @Override
    public Translation fromMt103(String fin) {
        Mt103Translator.FromMt t = Mt103Translator.fromMt103(fin);
        if (!t.errors().isEmpty()) {
            throw new MessagingException(MessagingError.INVALID_MESSAGE, "The MT103 cannot be translated", t.errors());
        }
        String xml = Mx.render(Pacs008Writer.build(t.transfer(), t.transfer().instructionId(), now()));
        return new Translation(MessageType.MT103, MessageType.PACS_008, xml, t.fields());
    }

    // ------------------------------------------------------------------ statements

    @Override
    @Transactional
    public Idempotent<MessageView> issueCamt053(String key, long accountId, LocalDate date) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        String hash = MessageRepository.sha256("camt053\u001F" + accountId + "\u001F" + date);
        repo.lockIdempotencyKey(key);
        Optional<MessageRepository.StoredKey> stored = repo.findIdempotencyKey(key);
        if (stored.isPresent()) {
            if (!stored.get().requestHash().equals(hash)) {
                throw new MessagingException(MessagingError.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key " + key + " was already used for a different request");
            }
            return new Idempotent<>(get(stored.get().messageId()).message(), true);
        }
        AccountView account = ledger.getAccount(accountId);
        if (account.type() != AccountType.CUSTOMER && account.type() != AccountType.VOSTRO) {
            throw new MessagingException(MessagingError.NOT_STATEMENTABLE, "We statement accounts we keep for others "
                    + "(customers, vostros). The statement of a " + account.type() + " account comes from the bank that keeps it.");
        }
        LocalDate day = date != null ? date : today();
        StatementView s = ledger.statement(accountId, day);
        long seq = repo.statementsIssued(account.accountNumber()) + 1;
        String msgId = newMsgId(ownBic, MessageType.CAMT_053);
        String statementId = account.accountNumber() + "-" + YYYYMMDD.format(day) + "-" + seq;
        if (statementId.length() > 35) {
            statementId = statementId.substring(statementId.length() - 35);
        }
        String receiver = account.type() == AccountType.VOSTRO && account.counterpartyBic() != null
                ? Bic.parse(account.counterpartyBic()).value() : null;
        String raw = Mx.render(Camt053Writer.build(s, ownBic, receiver, msgId, statementId, seq, now(), bankZone));
        selfCheck(raw);
        MessageView m = repo.insert(new NewMessage(MessageDirection.OUTBOUND, MessageType.CAMT_053, msgId, ownBic, receiver,
                null, null, null, null, null, account.accountNumber(), s.closingBooked(), day, null, null, List.of(), raw));
        repo.insertIdempotencyKey(key, hash, m.id());
        return new Idempotent<>(m, false);
    }

    // ------------------------------------------------------------------ helpers

    /** Our own output must pass the checks we apply to others. A failure here is a bug: fail loudly. */
    private static MxReader.Parsed selfCheck(String raw) {
        MxReader.Parsed p = MxReader.read(raw, null);
        if (!p.errors().isEmpty()) {
            throw new MessagingException(MessagingError.INVALID_MESSAGE, "Refusing to send an invalid message", p.errors());
        }
        return p;
    }

    /** "MNPA20261124-008-000912": sender's BIC4, business date, message type, sequence. Unique per sender. */
    private String newMsgId(String senderBic, MessageType type) {
        String code = type.identifier().substring(5, 8);
        return bic4(senderBic) + YYYYMMDD.format(today()) + "-" + code + "-" + String.format("%06d", repo.nextSequence() % 1_000_000);
    }

    private static String bic4(String bic) {
        return bic.substring(0, 4);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock.withZone(bankZone)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(bankZone));
    }
}
