package com.minipay.messaging.internal.mx;

import com.minipay.messaging.api.CoverTransfer;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.MessageType;
import com.prowidesoftware.swift.model.mx.MxPacs00900108;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction36;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction37;
import com.prowidesoftware.swift.model.mx.dic.FinancialInstitutionCreditTransferV08;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader93;
import com.prowidesoftware.swift.model.mx.dic.PartyIdentification135;
import com.prowidesoftware.swift.model.mx.dic.PaymentIdentification7;
import com.prowidesoftware.swift.model.mx.dic.RemittanceInformation16;
import com.prowidesoftware.swift.model.mx.dic.SettlementInstruction7;
import com.prowidesoftware.swift.model.mx.dic.SettlementMethod1Code;
import java.time.OffsetDateTime;

/**
 * pacs.009.001.08 COV: debit the debtor bank's account, credit the creditor bank's account, for the
 * underlying customer payment that is copied into UndrlygCstmrCdtTrf. Same UETR as the pacs.008, so
 * the two legs of a cover payment can be matched end to end.
 */
public final class Pacs009Writer {

    private Pacs009Writer() {
    }

    public static MxPacs00900108 build(CoverTransfer c, String msgId, OffsetDateTime created) {
        CreditTransfer u = c.underlying();
        CreditTransferTransaction37 underlying = new CreditTransferTransaction37()
                .setDbtr(new PartyIdentification135().setNm(u.debtorName()))
                .setDbtrAcct(Mx.account(u.debtorAccount()))
                .setDbtrAgt(Mx.agent(u.debtorAgentBic()))
                .setCdtrAgt(Mx.agent(u.creditorAgentBic()))
                .setCdtr(new PartyIdentification135().setNm(u.creditorName()))
                .setCdtrAcct(Mx.account(u.creditorAccount()));
        if (u.instructedAmount() != null) {
            underlying.setInstdAmt(Mx.amount(u.instructedAmount()));
        }
        if (u.remittanceInformation() != null && !u.remittanceInformation().isBlank()) {
            underlying.setRmtInf(new RemittanceInformation16().addUstrd(u.remittanceInformation()));
        }
        CreditTransferTransaction36 tx = new CreditTransferTransaction36()
                .setPmtId(new PaymentIdentification7().setInstrId(c.instructionId()).setEndToEndId(c.endToEndId()).setUETR(c.uetr()))
                .setIntrBkSttlmAmt(Mx.active(c.amount()))
                .setIntrBkSttlmDt(c.settlementDate())
                .setInstgAgt(Mx.agent(c.instructingAgentBic()))
                .setInstdAgt(Mx.agent(c.instructedAgentBic()))
                .setDbtr(Mx.agent(c.debtorBic()))
                .setCdtr(Mx.agent(c.creditorBic()))
                .setUndrlygCstmrCdtTrf(underlying);
        MxPacs00900108 mx = new MxPacs00900108();
        mx.setAppHdr(Mx.header(c.instructingAgentBic(), c.instructedAgentBic(), msgId, MessageType.PACS_009, created));
        mx.setFICdtTrf(new FinancialInstitutionCreditTransferV08()
                .setGrpHdr(new GroupHeader93().setMsgId(msgId).setCreDtTm(created).setNbOfTxs("1")
                        .setSttlmInf(new SettlementInstruction7().setSttlmMtd(SettlementMethod1Code.INDA)))
                .addCdtTrfTxInf(tx));
        return mx;
    }
}
