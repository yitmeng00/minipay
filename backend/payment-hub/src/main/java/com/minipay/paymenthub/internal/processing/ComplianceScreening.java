package com.minipay.paymenthub.internal.processing;

import com.minipay.common.money.Money;
import com.minipay.compliance.api.CaseVerdict;
import com.minipay.compliance.api.ComplianceApi;
import com.minipay.compliance.api.PartyRole;
import com.minipay.compliance.api.ScreenedParty;
import com.minipay.compliance.api.ScreeningOutcome;
import com.minipay.compliance.api.ScreeningRequest;
import com.minipay.paymenthub.api.PaymentDirection;
import com.minipay.paymenthub.api.PaymentKind;
import com.minipay.paymenthub.internal.domain.Payment;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hands the payment to compliance: every party name and every bank on the route that the message
 * carries. Each bank screens for itself, whatever the banks before it decided: a correspondent that
 * relied on the debtor's bank's screening would be breaking sanctions law in its own jurisdiction.
 *
 * <p>For a pacs.009 COV the parties screened are those of the underlying customer payment
 * ({@code UndrlygCstmrCdtTrf}). That is why CBPR+ makes the cover carry them: before 2009 a correspondent
 * moving cover money could not see whose money it was moving.
 */
@Component
public class ComplianceScreening implements ScreeningPort {

    private final ComplianceApi compliance;
    private final String ownBic;

    public ComplianceScreening(ComplianceApi compliance, @Value("${minipay.bank.bic}") String ownBic) {
        this.compliance = compliance;
        this.ownBic = com.minipay.common.id.Bic.parse(ownBic).value();
    }

    @Override
    public Result screen(Payment p, LocalDate asOf) {
        ScreeningOutcome o = compliance.screen(new ScreeningRequest(p.id(), p.uetr(), p.direction().name(), p.kind().name(),
                Money.of(p.amount(), p.currency()), p.debtorName(), p.creditorName(), route(p), parties(p), asOf));
        return new Result(o.hit(), o.detail());
    }

    @Override
    public CaseVerdict verdict(Payment p) {
        return compliance.verdict(p.id());
    }

    static List<ScreenedParty> parties(Payment p) {
        String prefix = p.kind() == PaymentKind.COVER ? "UndrlygCstmrCdtTrf/" : "";
        List<ScreenedParty> parties = new ArrayList<>();
        parties.add(new ScreenedParty(PartyRole.DEBTOR, prefix + "Dbtr/Nm", p.debtorName(), p.debtorAccount(), p.debtorAgentBic()));
        parties.add(new ScreenedParty(PartyRole.CREDITOR, prefix + "Cdtr/Nm", p.creditorName(), p.creditorAccount(), p.creditorAgentBic()));
        agent(parties, PartyRole.DEBTOR_AGENT, prefix + "DbtrAgt/FinInstnId/BICFI", p.debtorAgentBic());
        agent(parties, PartyRole.CREDITOR_AGENT, prefix + "CdtrAgt/FinInstnId/BICFI", p.creditorAgentBic());
        agent(parties, PartyRole.INSTRUCTING_AGENT, "InstgAgt/FinInstnId/BICFI", p.instructingAgentBic());
        agent(parties, PartyRole.INSTRUCTED_AGENT, "InstdAgt/FinInstnId/BICFI", p.instructedAgentBic());
        agent(parties, PartyRole.REIMBURSEMENT_AGENT, "InstgRmbrsmntAgt/FinInstnId/BICFI", p.coverAgentBic());
        return parties;
    }

    private static void agent(List<ScreenedParty> parties, PartyRole role, String field, String bic) {
        if (bic != null && !bic.isBlank()) {
            parties.add(new ScreenedParty(role, field, null, null, bic));
        }
    }

    /** The banks we know of at screening time, for the analyst. */
    private String route(Payment p) {
        Set<String> banks = new LinkedHashSet<>();
        if (p.direction() == PaymentDirection.INCOMING) {
            add(banks, p.debtorAgentBic());
            add(banks, p.instructingAgentBic());
            banks.add(ownBic);
        } else {
            banks.add(ownBic);
            add(banks, p.instructedAgentBic());
        }
        add(banks, p.creditorAgentBic());
        return String.join(" → ", banks);
    }

    private static void add(Set<String> banks, String bic) {
        if (bic != null && !bic.isBlank()) {
            banks.add(bic);
        }
    }
}
