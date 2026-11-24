package com.minipay.paymenthub.internal.processing;

import com.minipay.common.id.Bic;
import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.paymenthub.internal.domain.Payment;
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
 * IBAN structure and check digits, and whether the BIC actually exists and is reachable, are
 * reference-data checks added in M3.
 */
@Component
public class PaymentValidator {

    private static final Pattern ACCOUNT = Pattern.compile("^[A-Z0-9]{1,34}$");
    private static final Pattern IBAN_SHAPE = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$");

    private final LedgerApi ledger;
    private final String ownBic;

    public PaymentValidator(LedgerApi ledger, @Value("${minipay.bank.bic}") String ownBic) {
        this.ledger = ledger;
        this.ownBic = ownBic;
    }

    public Optional<Rejection> validate(Payment p, LocalDate businessDate) {
        Optional<AccountView> found = ledger.findAccountByNumber(p.debtorAccount());
        if (found.isEmpty()) {
            return reject("AC01", "Debtor account " + p.debtorAccount() + " does not exist");
        }
        AccountView debtor = found.get();
        if (debtor.type() != AccountType.CUSTOMER) {
            return reject("AG01", "Account " + p.debtorAccount() + " is a " + debtor.type() + " account, not a customer account");
        }
        if (debtor.status() == AccountStatus.CLOSED) {
            return reject("AC04", "Debtor account is closed");
        }
        if (debtor.status() == AccountStatus.BLOCKED) {
            return reject("AC06", "Debtor account is blocked");
        }
        if (!debtor.currency().equals(p.currency())) {
            return reject("AM03", "Debtor account is in " + debtor.currency() + ", payment is in " + p.currency());
        }
        if (!Bic.isValid(p.creditorAgentBic())) {
            return reject("RC01", "Creditor agent BIC " + p.creditorAgentBic() + " is not a valid BIC");
        }
        if (Bic.parse(p.creditorAgentBic()).bic8().equals(Bic.parse(ownBic).bic8())) {
            return reject("AG03", "Creditor agent is this bank: book transfers do not go through the payment network");
        }
        if (!ACCOUNT.matcher(p.creditorAccount()).matches()
                || (Character.isLetter(p.creditorAccount().charAt(0)) && !IBAN_SHAPE.matcher(p.creditorAccount()).matches())) {
            return reject("AC03", "Creditor account " + p.creditorAccount() + " is not a valid account number or IBAN");
        }
        if (p.requestedExecDate().isBefore(businessDate)) {
            return reject("DT01", "Requested execution date " + p.requestedExecDate() + " is before today's business date " + businessDate);
        }
        return Optional.empty();
    }

    private static Optional<Rejection> reject(String code, String detail) {
        return Optional.of(new Rejection(code, detail));
    }
}
