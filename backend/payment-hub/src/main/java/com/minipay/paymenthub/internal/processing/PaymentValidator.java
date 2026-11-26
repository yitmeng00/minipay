package com.minipay.paymenthub.internal.processing;

import com.minipay.common.id.Bic;
import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.paymenthub.internal.domain.Payment;
import com.minipay.refdata.api.BicEntry;
import com.minipay.refdata.api.IbanCheck;
import com.minipay.refdata.api.RefDataApi;
import com.minipay.refdata.api.SchemeView;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Business validation (RECEIVED → VALIDATED or REJECTED).
 *
 * <p>Two kinds of "invalid" are deliberately treated differently:
 * <ul>
 *   <li><b>Malformed requests</b> (missing fields, text too long, unparseable amount) are refused at
 *       the API with HTTP 400 — no payment is created, as a schema-invalid file would be refused.</li>
 *   <li><b>Well-formed but unacceptable</b> payments (unknown account, wrong currency, bad BIC…) are
 *       created and then REJECTED with an ISO reason code, so the refusal is on record and can be
 *       reported back to the customer (pain.002 / pacs.002 RJCT).</li>
 * </ul>
 *
 * <p>From M3 the checks use reference data, each as of the payment's <b>execution date</b> (the day
 * the money will move, which for a warehoused payment is not today):
 * <ul>
 *   <li>the currency must have a settlement scheme, and the execution date is the requested date
 *       rolled forward to a business day of that scheme's calendar;</li>
 *   <li>the creditor agent must be in the BIC directory on that date;</li>
 *   <li>an IBAN must pass the IBAN registry's checks, and must belong to the creditor agent;
 *       a creditor in an IBAN country must be given an IBAN;</li>
 *   <li>there must be a route (rule + usable settlement account) on that date.</li>
 * </ul>
 */
@Component
public class PaymentValidator {

    private static final Pattern ACCOUNT = Pattern.compile("^[A-Z0-9]{1,34}$");
    private static final Pattern IBAN_LIKE = Pattern.compile("^[A-Z]{2}[0-9]{2}.*");

    /** Outcome of validation: a rejection, or the business day the payment will be executed on. */
    public record Validation(Optional<Rejection> rejection, LocalDate executionDate, String detail) {

        static Validation reject(String code, String detail) {
            return new Validation(Optional.of(new Rejection(code, detail)), null, detail);
        }
    }

    private final LedgerApi ledger;
    private final RefDataApi refdata;
    private final Router router;
    private final String ownBic;

    public PaymentValidator(LedgerApi ledger, RefDataApi refdata, Router router, @Value("${minipay.bank.bic}") String ownBic) {
        this.ledger = ledger;
        this.refdata = refdata;
        this.router = router;
        this.ownBic = ownBic;
    }

    public Validation validate(Payment p, LocalDate businessDate) {
        // --- Debtor side: our own books.
        Optional<AccountView> found = ledger.findAccountByNumber(p.debtorAccount());
        if (found.isEmpty()) {
            return Validation.reject("AC01", "Debtor account " + p.debtorAccount() + " does not exist");
        }
        AccountView debtor = found.get();
        if (debtor.type() != AccountType.CUSTOMER) {
            return Validation.reject("AG01", "Account " + p.debtorAccount() + " is a " + debtor.type() + " account, not a customer account");
        }
        if (debtor.status() == AccountStatus.CLOSED) {
            return Validation.reject("AC04", "Debtor account is closed");
        }
        if (debtor.status() == AccountStatus.BLOCKED) {
            return Validation.reject("AC06", "Debtor account is blocked");
        }
        if (!debtor.currency().equals(p.currency())) {
            return Validation.reject("AM03", "Debtor account is in " + debtor.currency() + ", payment is in " + p.currency());
        }

        // --- Creditor agent: format, not ourselves.
        if (!Bic.isValid(p.creditorAgentBic())) {
            return Validation.reject("RC01", "Creditor agent BIC " + p.creditorAgentBic() + " is not a valid BIC");
        }
        Bic agent = Bic.parse(p.creditorAgentBic());
        if (agent.bic8().equals(Bic.parse(ownBic).bic8())) {
            return Validation.reject("AG03", "Creditor agent is this bank: book transfers do not go through the payment network");
        }

        // --- When: the execution date, on the currency's calendar.
        if (p.requestedExecDate().isBefore(businessDate)) {
            return Validation.reject("DT01", "Requested execution date " + p.requestedExecDate() + " is before today's business date " + businessDate);
        }
        Optional<SchemeView> scheme = refdata.scheme(p.currency(), p.requestedExecDate());
        if (scheme.isEmpty()) {
            return Validation.reject("AM03", "We do not settle " + p.currency() + ": no settlement scheme in reference data");
        }
        // An instant payment executes on the requested day whatever the calendar says: instant schemes run 24/7/365.
        boolean instant = p.serviceLevel() == com.minipay.paymenthub.api.ServiceLevel.INST;
        LocalDate executionDate = instant ? p.requestedExecDate() : refdata.nextBusinessDay(p.currency(), p.requestedExecDate());

        // --- Creditor agent exists on that date.
        Optional<BicEntry> agentEntry = refdata.findBic(agent.value(), executionDate);
        if (agentEntry.isEmpty()) {
            return Validation.reject("RC01", "Creditor agent " + agent + " is not in the BIC directory on " + executionDate);
        }

        // --- Creditor account.
        Optional<Validation> account = checkCreditorAccount(p.creditorAccount(), agent, executionDate);
        if (account.isPresent()) {
            return account.get();
        }

        // --- A route on that date.
        Router.Outcome route = router.route(agent.value(), p.currency(), executionDate, p.serviceLevel());
        if (route.route().isEmpty()) {
            return Validation.reject("CNOR", "No " + (instant ? "instant " : p.serviceLevel() == com.minipay.paymenthub.api.ServiceLevel.HIGH
                    ? "urgent " : "") + "route on " + executionDate + ": " + route.problem());
        }

        String when = executionDate.equals(p.requestedExecDate())
                ? "execution date " + executionDate
                : "execution date " + executionDate + " (" + p.requestedExecDate() + " is not a "
                + scheme.get().schemeName() + " business day)";
        return new Validation(Optional.empty(), executionDate, "Debtor account, creditor agent " + agent + " ("
                + agentEntry.get().institutionName() + "), creditor account, route " + route.route().get().ruleId() + "; " + when);
    }

    private Optional<Validation> checkCreditorAccount(String account, Bic agent, LocalDate on) {
        if (IBAN_LIKE.matcher(account).matches()) {
            IbanCheck iban = refdata.checkIban(account, on);
            if (!iban.valid()) {
                return Optional.of(Validation.reject("AC03", "Creditor IBAN " + account + " is invalid · " + iban.firstFailure()));
            }
            // The IBAN names its bank. If it is not the creditor agent, one of the two is wrong — and
            // we cannot know which, so we refuse rather than send money to the wrong bank.
            if (iban.bic() != null && !Bic.parse(iban.bic()).bic8().equals(agent.bic8())) {
                return Optional.of(Validation.reject("AC03", "Creditor IBAN belongs to " + iban.bic()
                        + " (bank code " + iban.bankId() + "), but the creditor agent is " + agent));
            }
            return Optional.empty();
        }
        if (refdata.usesIban(agent.countryCode(), on)) {
            return Optional.of(Validation.reject("AC03", "Accounts in " + agent.countryCode()
                    + " are identified by IBAN; " + account + " is not one"));
        }
        if (!ACCOUNT.matcher(account).matches()) {
            return Optional.of(Validation.reject("AC03", "Creditor account " + account + " is not a valid account number"));
        }
        return Optional.empty();
    }
}
