package com.minipay.messaging.internal.mx;

import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.MessageType;
import com.prowidesoftware.swift.model.mx.MxPacs00800108;
import com.prowidesoftware.swift.model.mx.dic.ChargeBearerType1Code;
import com.prowidesoftware.swift.model.mx.dic.Charges7;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction39;
import com.prowidesoftware.swift.model.mx.dic.FIToFICustomerCreditTransferV08;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader93;
import com.prowidesoftware.swift.model.mx.dic.PartyIdentification135;
import com.prowidesoftware.swift.model.mx.dic.PaymentIdentification7;
import com.prowidesoftware.swift.model.mx.dic.RemittanceInformation16;
import com.prowidesoftware.swift.model.mx.dic.SettlementInstruction7;
import com.prowidesoftware.swift.model.mx.dic.SettlementMethod1Code;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * pacs.008.001.08, one transaction per message (CBPR+ allows no batches).
 *
 * <pre>
 *   GrpHdr        MsgId, CreDtTm, NbOfTxs = 1, SttlmInf/SttlmMtd (INDA, INGA, COVE, CLRG)
 *   CdtTrfTxInf   PmtId (InstrId, EndToEndId, UETR), IntrBkSttlmAmt + Dt, InstdAmt, ChrgBr,
 *                 ChrgsInf (each bank's charges), PrvsInstgAgt1..3, InstgAgt, InstdAgt, Dbtr, DbtrAcct, DbtrAgt,
 *                 CdtrAgt, Cdtr, CdtrAcct, RmtInf/Ustrd
 * </pre>
 */
public final class Pacs008Writer {

    private Pacs008Writer() {
    }

    public static MxPacs00800108 build(CreditTransfer t, String msgId, OffsetDateTime created) {
        SettlementInstruction7 sttlm = new SettlementInstruction7().setSttlmMtd(SettlementMethod1Code.valueOf(t.settlementMethod().name()));
        if (t.coverAgentBic() != null) {
            // Cover method: the money travels separately, through this bank (InstgRmbrsmntAgt).
            sttlm.setInstgRmbrsmntAgt(Mx.agent(t.coverAgentBic()));
        }
        GroupHeader93 grpHdr = new GroupHeader93()
                .setMsgId(msgId)
                .setCreDtTm(created)
                .setNbOfTxs("1")
                .setSttlmInf(sttlm);

        CreditTransferTransaction39 tx = new CreditTransferTransaction39()
                .setPmtId(new PaymentIdentification7()
                        .setInstrId(t.instructionId())
                        .setEndToEndId(t.endToEndId())
                        .setUETR(t.uetr()))
                .setIntrBkSttlmAmt(Mx.active(t.interbankAmount()))
                .setIntrBkSttlmDt(t.settlementDate())
                .setChrgBr(ChargeBearerType1Code.valueOf(t.chargeBearer().name()))
                .setInstgAgt(Mx.agent(t.instructingAgentBic()))
                .setInstdAgt(Mx.agent(t.instructedAgentBic()))
                .setDbtr(new PartyIdentification135().setNm(t.debtorName()))
                .setDbtrAcct(Mx.account(t.debtorAccount()))
                .setDbtrAgt(Mx.agent(t.debtorAgentBic()))
                .setCdtrAgt(Mx.agent(t.creditorAgentBic()))
                .setCdtr(new PartyIdentification135().setNm(t.creditorName()))
                .setCdtrAcct(Mx.account(t.creditorAccount()));
        if (t.instructedAmount() != null) {
            tx.setInstdAmt(Mx.amount(t.instructedAmount()));
        }
        // Who took what, hop by hop: this is why IntrBkSttlmAmt can be less than InstdAmt.
        for (com.minipay.messaging.api.Charge c : t.charges()) {
            tx.addChrgsInf(new Charges7().setAmt(Mx.amount(c.amount())).setAgt(Mx.agent(c.agentBic())));
        }
        // Banks before the instructing agent, oldest first (PrvsInstgAgt1 is the first bank on the route).
        List<String> prev = t.previousAgents();
        if (prev.size() > 0) {
            tx.setPrvsInstgAgt1(Mx.agent(prev.get(0)));
        }
        if (prev.size() > 1) {
            tx.setPrvsInstgAgt2(Mx.agent(prev.get(1)));
        }
        if (prev.size() > 2) {
            tx.setPrvsInstgAgt3(Mx.agent(prev.get(2)));
        }
        if (t.remittanceInformation() != null && !t.remittanceInformation().isBlank()) {
            tx.setRmtInf(new RemittanceInformation16().addUstrd(t.remittanceInformation()));
        }

        MxPacs00800108 mx = new MxPacs00800108();
        mx.setAppHdr(Mx.header(t.instructingAgentBic(), t.instructedAgentBic(), msgId, MessageType.PACS_008, created));
        mx.setFIToFICstmrCdtTrf(new FIToFICustomerCreditTransferV08().setGrpHdr(grpHdr).addCdtTrfTxInf(tx));
        return mx;
    }
}
