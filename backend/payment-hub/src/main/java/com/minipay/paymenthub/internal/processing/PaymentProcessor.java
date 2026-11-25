package com.minipay.paymenthub.internal.processing;

import com.minipay.common.money.CurrencyUnits;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.LedgerException;
import com.minipay.ledger.api.ReleaseReservationCommand;
import com.minipay.ledger.api.ReservationView;
import com.minipay.ledger.api.ReserveFundsCommand;
import com.minipay.ledger.api.SettleReservationCommand;
import com.minipay.ledger.api.SettlementLeg;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.internal.config.PaymentHubProperties;
import com.minipay.paymenthub.internal.domain.Payment;
import com.minipay.paymenthub.internal.persistence.PaymentRepository;
import com.minipay.paymenthub.internal.processing.PaymentTransitions.Change;
import com.minipay.refdata.api.RefDataApi;
import com.minipay.refdata.api.RefDataException;
import com.minipay.refdata.api.RoutingMethod;
import com.minipay.refdata.api.ValueDating;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Drives an outgoing payment through RECEIVED → VALIDATED → SCREENED → FUNDS_RESERVED → SENT.
 *
 * <h2>One step = (side effect, then status change)</h2>
 * Each step first does its work <em>outside</em> any payment transaction — for example asking the
 * ledger to reserve funds — and then records the outcome with {@link PaymentTransitions} in a short
 * transaction of its own. The ledger call and the status change are therefore NOT atomic. That is
 * deliberate (it is how a hub talks to a separate core-banking ledger) and safe because every ledger
 * call carries an idempotency key derived from the payment id and the step:
 *
 * <pre>  pay:{paymentId}:reserve   pay:{paymentId}:settle   pay:{paymentId}:release   pay:{paymentId}:return</pre>
 *
 * If the process dies after the ledger committed but before the status change, the payment is left
 * in the earlier status; the recovery sweeper runs the step again, the ledger replays the stored
 * result instead of posting twice, and the status change is recorded. Two workers racing on the same
 * payment are harmless for the same reason; the loser's status change is discarded by the
 * expected-status check / optimistic lock.
 */
@Component
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

    private final PaymentRepository payments;
    private final PaymentTransitions transitions;
    private final PaymentValidator validator;
    private final ScreeningPort screening;
    private final Router router;
    private final LedgerApi ledger;
    private final RefDataApi refdata;
    private final PaymentHubProperties props;
    private final Clock clock;
    private final ZoneId bankZone;

    public PaymentProcessor(PaymentRepository payments, PaymentTransitions transitions, PaymentValidator validator,
                            ScreeningPort screening, Router router, LedgerApi ledger, RefDataApi refdata, PaymentHubProperties props,
                            ObjectProvider<Clock> clock, @Value("${minipay.bank.time-zone:UTC}") ZoneId bankZone) {
        this.payments = payments;
        this.transitions = transitions;
        this.validator = validator;
        this.screening = screening;
        this.router = router;
        this.ledger = ledger;
        this.refdata = refdata;
        this.props = props;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.bankZone = bankZone;
    }

    /** Advances the payment as far as it can go now. Safe to call repeatedly and concurrently. */
    public void process(UUID paymentId) {
        for (int guard = 0; guard < 10; guard++) {
            Payment p = payments.findById(paymentId).orElseThrow();
            boolean progressed = switch (p.status()) {
                case RECEIVED -> validate(p);
                case VALIDATED -> screen(p);
                case SCREENED -> reserveFunds(p);
                case FUNDS_RESERVED -> send(p);
                default -> false; // waiting for the network, a compliance decision, or final
            };
            if (!progressed) {
                return;
            }
        }
    }

    public LocalDate businessDate() {
        return LocalDate.now(clock.withZone(bankZone));
    }

    // ------------------------------------------------------------------ steps

    private boolean validate(Payment p) {
        PaymentValidator.Validation v = validator.validate(p, businessDate());
        String debtorName = ledger.findAccountByNumber(p.debtorAccount()).map(AccountView::ownerName).orElse(p.debtorName());
        return transitions.transition(p.id(), PaymentStatus.RECEIVED, payment -> {
            payment.debtorName(debtorName);
            if (v.rejection().isPresent()) {
                payment.reject(v.rejection().get().reasonCode(), v.rejection().get().detail());
                return new Change(PaymentStatus.REJECTED, v.rejection().get().reasonCode(), v.rejection().get().detail(), "svc/validator");
            }
            payment.executeOn(v.executionDate());
            return new Change(PaymentStatus.VALIDATED, null, v.detail(), "svc/validator");
        });
    }

    private boolean screen(Payment p) {
        ScreeningPort.Result result = screening.screen(p);
        return transitions.transition(p.id(), PaymentStatus.VALIDATED, payment ->
                new Change(PaymentStatus.SCREENED, null, result.detail(), "svc/screening"))
                && (!result.hit() || transitions.transition(p.id(), PaymentStatus.SCREENED, payment ->
                new Change(PaymentStatus.HELD_COMPLIANCE, null, result.detail(), "svc/screening")));
    }

    private boolean reserveFunds(Payment p) {
        Money amount = Money.of(p.amount(), p.currency());
        Money fee = feeFor(p.currency());
        Charges charges = Charges.of(p.chargeBearer(), amount, fee);
        if (!charges.interbankSettlementAmount().isPositive()) {
            return reject(p, PaymentStatus.SCREENED, "AM12",
                    "Amount " + amount + " does not cover our fee " + fee + " under charge bearer CRED", "svc/ledger");
        }
        AccountView debtor = ledger.findAccountByNumber(p.debtorAccount()).orElseThrow();
        ReservationView hold;
        try {
            hold = ledger.reserve(new ReserveFundsCommand(key(p, "reserve"), debtor.id(), charges.hold(), p.id(),
                    "Payment " + p.endToEndId() + " to " + p.creditorName())).value();
        } catch (LedgerException e) {
            return reject(p, PaymentStatus.SCREENED, iso(e), e.getMessage(), "svc/ledger");
        }
        String detail = "Hold " + charges.hold() + " on " + p.debtorAccount()
                + (fee.isZero() ? "" : p.chargeBearer() == com.minipay.common.iso.ChargeBearer.CRED
                ? " (fee " + fee + " deducted from amount, CRED)" : " (amount + fee " + fee + ", " + p.chargeBearer() + ")");
        return transitions.transition(p.id(), PaymentStatus.SCREENED, payment -> {
            payment.fundsReserved(hold.id(), fee.amount(), charges.interbankSettlementAmount().amount());
            return new Change(PaymentStatus.FUNDS_RESERVED, null, detail, "svc/ledger");
        });
    }

    /**
     * Books the payment and hands it to the network.
     *
     * <ol>
     *   <li><b>Warehousing:</b> a payment whose execution date is after today's business date stays
     *       FUNDS_RESERVED until that date.</li>
     *   <li><b>Value date:</b> the first business day of the currency on or after the execution date
     *       whose cut-off has not passed. A payment that missed today's cut-off settles tomorrow.</li>
     *   <li><b>Route:</b> the routing rule valid on the value date, which can differ from the rule that
     *       was valid when the payment was validated.</li>
     * </ol>
     *
     * <p>The value date is part of the ledger request, so a re-run must use the same one. Otherwise
     * a crash just before a cut-off would make the retry ask for a different value date under the
     * same idempotency key, and the ledger would refuse it instead of replaying. So if the ledger
     * already holds our SETTLE entry, its value date is the decision and is reused.
     */
    private boolean send(Payment p) {
        LocalDate today = businessDate();
        if (p.executionDate().isAfter(today)) {
            return false;
        }
        Optional<LocalDate> alreadyBooked = ledger.entriesForPayment(p.id()).stream()
                .filter(e -> e.entryType() == EntryType.SETTLE && e.reversesEntryId() == null)
                .map(JournalEntryView::valueDate).findFirst();
        LocalDate valueDate;
        String rolled = "";
        if (alreadyBooked.isPresent()) {
            valueDate = alreadyBooked.get();
        } else {
            ValueDating dating;
            try {
                dating = refdata.valueDate(p.currency(), p.executionDate(), clock.instant());
            } catch (RefDataException e) {
                return releaseAndReject(p, "AM03", e.getMessage());
            }
            valueDate = dating.valueDate();
            if (dating.rolled()) {
                rolled = " (" + dating.reasons().get(dating.reasons().size() - 1) + ")";
            }
        }

        Router.Outcome outcome = router.route(p.creditorAgentBic(), p.currency(), valueDate);
        if (outcome.route().isEmpty()) {
            return releaseAndReject(p, "CNOR", "No route on value date " + valueDate + ": " + outcome.problem());
        }
        Router.Route r = outcome.route().get();
        Money interbank = Money.of(p.interbankSettlementAmount(), p.currency());
        Money fee = Money.of(p.chargesAmount(), p.currency());
        List<SettlementLeg> legs = new ArrayList<>();
        legs.add(new SettlementLeg(r.settlementAccountId(), interbank, EntryType.SETTLE,
                "To " + p.creditorName() + " · " + p.endToEndId()));
        if (fee.isPositive()) {
            legs.add(new SettlementLeg(feeIncomeAccount(p.currency()).orElseThrow().id(), fee, EntryType.FEE,
                    "Outward transfer fee (" + p.chargeBearer() + ")"));
        }
        try {
            ledger.settle(new SettleReservationCommand(key(p, "settle"), p.reservationId(), legs, valueDate));
        } catch (LedgerException e) {
            // e.g. AM04 on our nostro: not enough liquidity at the correspondent to send it.
            return releaseAndReject(p, iso(e), e.getMessage());
        }
        afterSettle.run();
        String detail = "Value date " + valueDate + rolled + "; booked to " + r.settlementAccountNumber()
                + "; routed by " + r.describe() + " → " + r.instructedAgentBic()
                + (r.method() == RoutingMethod.COVER ? ", cover via " + r.nextHopBic() : "");
        return transitions.transition(p.id(), PaymentStatus.FUNDS_RESERVED, payment -> {
            payment.routed(r.instructedAgentBic(), r.settlementAccountId(), valueDate, r.ruleId());
            return new Change(PaymentStatus.SENT, null, detail, "svc/gateway");
        });
    }

    /** Test seam: runs right after the ledger booked the payment, before SENT is recorded. */
    private volatile Runnable afterSettle = () -> {
    };

    void afterSettle(Runnable hook) {
        this.afterSettle = hook == null ? () -> {
        } : hook;
    }

    // ------------------------------------------------------------------ helpers

    private boolean releaseAndReject(Payment p, String code, String detail) {
        ledger.release(new ReleaseReservationCommand(key(p, "release"), p.reservationId(), "Payment rejected: " + code));
        return reject(p, PaymentStatus.FUNDS_RESERVED, code, detail + " · hold released", "svc/gateway");
    }

    private boolean reject(Payment p, PaymentStatus expected, String code, String detail, String actor) {
        log.info("Payment {} rejected {}: {}", p.uetr(), code, detail);
        return transitions.transition(p.id(), expected, payment -> {
            payment.reject(code, detail);
            return new Change(PaymentStatus.REJECTED, code, detail, actor);
        });
    }

    /** Our fee, if a tariff is configured AND we have a fee income account to book it to. */
    private Money feeFor(String currency) {
        BigDecimal configured = props.outwardFees().get(currency);
        if (configured == null || configured.signum() == 0 || feeIncomeAccount(currency).isEmpty()) {
            return Money.zero(currency);
        }
        return Money.of(configured, CurrencyUnits.currency(currency));
    }

    private Optional<AccountView> feeIncomeAccount(String currency) {
        return ledger.listAccounts(AccountType.FEE_INCOME).stream()
                .filter(a -> a.currency().equals(currency) && a.status() == AccountStatus.ACTIVE)
                .findFirst();
    }

    static String key(Payment p, String step) {
        return "pay:" + p.id() + ":" + step;
    }

    private static String iso(LedgerException e) {
        return e.error().isoReasonCode() != null ? e.error().isoReasonCode() : "NARR";
    }
}
