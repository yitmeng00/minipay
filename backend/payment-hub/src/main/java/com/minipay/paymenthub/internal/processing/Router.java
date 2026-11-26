package com.minipay.paymenthub.internal.processing;

import com.minipay.paymenthub.api.ServiceLevel;
import com.minipay.refdata.api.ClearingSystemView;
import java.util.EnumSet;
import java.util.Set;

import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.messaging.api.SettlementMethod;
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
     * @param system             the clearing system the payment goes through (RTGS, DNS, INSTANT, ADR-008), else null
     */
    public record Route(String ruleId, String releaseVersion, RoutingMethod method, long settlementAccountId,
                        String settlementAccountNumber, AccountType settlementAccountType, String instructedAgentBic,
                        String nextHopBic, ClearingSystemView system) {

        /** Who our message is addressed to: the system's operator, or the instructed agent. */
        public String messageReceiverBic() {
            return system != null ? system.operatorBic() : instructedAgentBic;
        }

        public String describe() {
            return ruleId + " (" + releaseVersion + ", " + method + ")";
        }

        /**
         * pacs.008 SttlmMtd: on whose books the money moves. Our nostro at them → INDA (they debit
         * it); their vostro with us → INGA (we credit it); a cover payment → COVE; through a clearing
         * system (RTGS, DNS, instant) → CLRG.
         */
        public SettlementMethod settlementMethod() {
            return switch (method) {
                case COVER -> SettlementMethod.COVE;
                case RTGS, DNS, INSTANT -> SettlementMethod.CLRG;
                default -> settlementAccountType == AccountType.VOSTRO ? SettlementMethod.INGA : SettlementMethod.INDA;
            };
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
        return route(creditorAgentBic, currency, valueDate, ServiceLevel.NORM);
    }

    /**
     * The routes a service level may take (ADR-008): an instant payment only the instant scheme; an urgent one
     * anything but the ACH, whose cycles would hold it back; a normal one anything but the instant scheme.
     */
    public Outcome route(String creditorAgentBic, String currency, LocalDate valueDate, ServiceLevel level) {
        RouteResolution resolution = refdata.route(creditorAgentBic, currency, valueDate, allowed(level));
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
                a.accountNumber(), a.type(), r.instructedAgentBic(), rule.nextHopBic(), r.system())), null);
    }

    static Set<RoutingMethod> allowed(ServiceLevel level) {
        return switch (level == null ? ServiceLevel.NORM : level) {
            case INST -> EnumSet.of(RoutingMethod.INSTANT);
            case HIGH -> EnumSet.complementOf(EnumSet.of(RoutingMethod.INSTANT, RoutingMethod.DNS));
            case NORM -> EnumSet.complementOf(EnumSet.of(RoutingMethod.INSTANT));
        };
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
