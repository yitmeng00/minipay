package com.minipay.messaging.internal.mx;

import com.minipay.common.id.Iban;
import com.minipay.common.money.Money;
import com.minipay.messaging.api.MessageType;
import com.prowidesoftware.swift.model.MxId;
import com.prowidesoftware.swift.model.mx.AbstractMX;
import com.prowidesoftware.swift.model.mx.AppHdrFactory;
import com.prowidesoftware.swift.model.mx.BusinessAppHdrV02;
import com.prowidesoftware.swift.model.mx.EnvelopeType;
import com.prowidesoftware.swift.model.mx.MxWriteConfiguration;
import com.prowidesoftware.swift.model.mx.dic.AccountIdentification4Choice;
import com.prowidesoftware.swift.model.mx.dic.ActiveCurrencyAndAmount;
import com.prowidesoftware.swift.model.mx.dic.ActiveOrHistoricCurrencyAndAmount;
import com.prowidesoftware.swift.model.mx.dic.BranchAndFinancialInstitutionIdentification6;
import com.prowidesoftware.swift.model.mx.dic.CashAccount38;
import com.prowidesoftware.swift.model.mx.dic.FinancialInstitutionIdentification18;
import com.prowidesoftware.swift.model.mx.dic.GenericAccountIdentification1;
import java.time.OffsetDateTime;

/**
 * Building blocks shared by the MX writers.
 *
 * <p>Every MX message is a <b>business message</b>: a Business Application Header (head.001.001.02, the
 * CBPR+ version) that says who sends what to whom, plus the Document. It is written inside the ISO
 * business message envelope ({@code nvlp.001.001.02}), so one stored text holds both parts.
 */
public final class Mx {

    /** CBPR+ business service named in every AppHdr. */
    public static final String CBPR_PLUS = "swift.cbprplus.02";

    private Mx() {
    }

    public static BusinessAppHdrV02 header(String from, String to, String msgId, MessageType type, OffsetDateTime created) {
        BusinessAppHdrV02 h = AppHdrFactory.createBusinessAppHdrV02(from, to, msgId, new MxId(type.identifier()));
        h.setBizSvc(CBPR_PLUS);
        h.setCreDt(created);
        return h;
    }

    public static String render(AbstractMX mx) {
        MxWriteConfiguration c = new MxWriteConfiguration();
        c.envelopeType = EnvelopeType.BME_V2;
        c.indent = "  ";
        return mx.message(c);
    }

    public static BranchAndFinancialInstitutionIdentification6 agent(String bic) {
        return new BranchAndFinancialInstitutionIdentification6()
                .setFinInstnId(new FinancialInstitutionIdentification18().setBICFI(bic));
    }

    public static String bic(BranchAndFinancialInstitutionIdentification6 agent) {
        return agent == null || agent.getFinInstnId() == null ? null : agent.getFinInstnId().getBICFI();
    }

    /** An IBAN goes in Id/IBAN; anything else (e.g. a Malaysian account number) in Id/Othr/Id. */
    public static CashAccount38 account(String number) {
        AccountIdentification4Choice id = new AccountIdentification4Choice();
        if (Iban.hasValidCheckDigits(number)) {
            id.setIBAN(number);
        } else {
            id.setOthr(new GenericAccountIdentification1().setId(number));
        }
        return new CashAccount38().setId(id);
    }

    public static String account(CashAccount38 acct) {
        if (acct == null || acct.getId() == null) {
            return null;
        }
        return acct.getId().getIBAN() != null ? acct.getId().getIBAN()
                : acct.getId().getOthr() != null ? acct.getId().getOthr().getId() : null;
    }

    public static ActiveCurrencyAndAmount active(Money m) {
        return new ActiveCurrencyAndAmount().setCcy(m.currencyCode()).setValue(m.amount());
    }

    public static ActiveOrHistoricCurrencyAndAmount amount(Money m) {
        return new ActiveOrHistoricCurrencyAndAmount().setCcy(m.currencyCode()).setValue(m.amount());
    }

    /** An amount from a message, or null if absent or not in a currency/scale we accept (reported by the checks). */
    public static Money money(java.math.BigDecimal value, String ccy) {
        try {
            return value == null || ccy == null ? null : Money.of(value, ccy);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
