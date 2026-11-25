package com.minipay.paymenthub.internal.processing;

import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.refdata.api.RefDataApi;
import com.minipay.refdata.api.RouteResolution;
import com.minipay.refdata.api.RoutingMethod;
import com.minipay.refdata.api.RoutingRuleView;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Decides which account we settle on and which bank receives our message, using the effective-dated
 * routing rules in reference data (M3, ADR-004) as of the payment's <b>value date</b>.
 *
 * <p>Reference data names the settlement account by number; it does not read the ledger. The hub
 * resolves the number here and checks that the account can carry the payment: it must be ours (NOSTRO
 * or VOSTRO), ACTIVE, and in the payment's currency. A rule pointing at an unusable account is a data
 * error and gives no route (CNOR), rather than a booking on the wrong account.
 */
@Component
public class Router {

    /**
     * @param instructedAgentBic who receives our pacs.008 (InstdAgt)
     * @param nextHopBic         the correspondent for SERIAL and COVER routes, else null
     */
    public record Route(String ruleId, String releaseVersion, RoutingMethod method, long settlementAccountId,
                        String settlementAccountNumber, String instructedAgentBic, String nextHopBic) {

        public String describe() {
            return ruleId + " (" + releaseVersion + ", " + method + ")";
        }
    }

    /** A route, or why there is none. */
    public record Outcome(Optional<Route> route, String problem) {
    }

    private final RefDataApi refdata;
    private final LedgerApi ledger;

    public Router(RefDataApi refdata, LedgerApi ledger) {
        this.refdata = refdata;
        this.ledger = ledger;
    }

    public Outcome route(String creditorAgentBic, String currency, LocalDate valueDate) {
        RouteResolution resolution = refdata.route(creditorAgentBic, currency, valueDate);
        if (resolution.route().isEmpty()) {
            return new Outcome(Optional.empty(), resolution.problem());
        }
        RouteResolution.Route r = resolution.route().get();
        RoutingRuleView rule = r.rule();
        Optional<AccountView> account = ledger.findAccountByNumber(rule.settlementAccount());
        String unusable = account.isEmpty() ? "does not exist" : unusable(account.get(), currency);
        if (unusable != null) {
            return new Outcome(Optional.empty(), rule.ruleId() + " settles on account " + rule.settlementAccount()
                    + ", which " + unusable);
        }
        AccountView a = account.get();
        return new Outcome(Optional.of(new Route(rule.ruleId(), rule.releaseVersion(), rule.method(), a.id(),
                a.accountNumber(), r.instructedAgentBic(), rule.nextHopBic())), null);
    }

    private static String unusable(AccountView a, String currency) {
        if (a.type() != AccountType.NOSTRO && a.type() != AccountType.VOSTRO) {
            return "is a " + a.type() + " account, not a nostro or vostro";
        }
        if (!a.currency().equals(currency)) {
            return "is in " + a.currency();
        }
        if (a.status() != AccountStatus.ACTIVE) {
            return "is " + a.status();
        }
        return null;
    }
}
