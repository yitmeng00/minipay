package com.minipay.paymenthub.internal.processing;

import com.minipay.common.id.Bic;
import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.LedgerApi;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Decides which account we settle through and which bank we send the payment to next.
 *
 * <p>Interim rules until M3 brings effective-dated routing tables:
 * <ol>
 *   <li>The creditor's bank holds a <b>vostro</b> with us in this currency → credit that vostro and send
 *       straight to them. ("We pay them on our own books.")</li>
 *   <li>We hold a <b>nostro</b> at the creditor's bank in this currency → credit the nostro, send to them.</li>
 *   <li>Otherwise, our single <b>nostro</b> in this currency is our correspondent for it → credit it and
 *       send to that correspondent, who passes the payment on (serial method).</li>
 *   <li>No account in the currency → no route (ISO CNOR, "creditor bank is not registered").</li>
 * </ol>
 * BICs are compared on their first 8 characters: the branch does not change the institution.
 */
@Component
public class Router {

    public record Route(long settlementAccountId, String settlementAccountNumber, String nextAgentBic, String method) {
    }

    private final LedgerApi ledger;

    public Router(LedgerApi ledger) {
        this.ledger = ledger;
    }

    public Optional<Route> route(String creditorAgentBic, String currency) {
        String creditorBank = Bic.parse(creditorAgentBic).bic8();
        Optional<AccountView> vostro = usable(AccountType.VOSTRO, currency).stream()
                .filter(a -> Bic.parse(a.counterpartyBic()).bic8().equals(creditorBank)).findFirst();
        if (vostro.isPresent()) {
            return Optional.of(route(vostro.get(), "Credit to the creditor agent's vostro with us"));
        }
        List<AccountView> nostros = usable(AccountType.NOSTRO, currency);
        Optional<AccountView> direct = nostros.stream()
                .filter(a -> Bic.parse(a.counterpartyBic()).bic8().equals(creditorBank)).findFirst();
        if (direct.isPresent()) {
            return Optional.of(route(direct.get(), "Direct: our nostro at the creditor agent"));
        }
        if (nostros.size() == 1) {
            return Optional.of(route(nostros.get(0), "Serial via our " + currency + " correspondent"));
        }
        return Optional.empty();
    }

    private Route route(AccountView a, String method) {
        return new Route(a.id(), a.accountNumber(), a.counterpartyBic(), method);
    }

    private List<AccountView> usable(AccountType type, String currency) {
        return ledger.listAccounts(type).stream()
                .filter(a -> a.currency().equals(currency) && a.status() == AccountStatus.ACTIVE)
                .toList();
    }
}
