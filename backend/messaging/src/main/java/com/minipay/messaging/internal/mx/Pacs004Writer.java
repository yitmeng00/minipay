package com.minipay.messaging.internal.mx;

import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.PaymentReturn;
import com.prowidesoftware.swift.model.mx.MxPacs00400109;
import com.prowidesoftware.swift.model.mx.dic.ChargeBearerType1Code;
import com.prowidesoftware.swift.model.mx.dic.Charges7;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader90;
import com.prowidesoftware.swift.model.mx.dic.OriginalGroupInformation29;
import com.prowidesoftware.swift.model.mx.dic.PaymentReturnReason6;
import com.prowidesoftware.swift.model.mx.dic.PaymentReturnV09;
import com.prowidesoftware.swift.model.mx.dic.PaymentTransaction112;
import com.prowidesoftware.swift.model.mx.dic.ReturnReason5Choice;
import com.prowidesoftware.swift.model.mx.dic.SettlementInstruction7;
import com.prowidesoftware.swift.model.mx.dic.SettlementMethod1Code;
import java.time.OffsetDateTime;

/**
 * pacs.004.001.09: returning one original payment. The returned amount can be less than the
 * original; each bank's deduction is listed in ChrgsInf, and the charge bearer of a return is CRED
 * (the charges come out of what is returned).
 */
public final class Pacs004Writer {

    private Pacs004Writer() {
    }

    public static MxPacs00400109 build(PaymentReturn r, String msgId, OffsetDateTime created) {
        PaymentTransaction112 tx = new PaymentTransaction112()
                .setRtrId(r.returnId())
                .setOrgnlGrpInf(new OriginalGroupInformation29()
                        .setOrgnlMsgId(r.originalMsgId())
                        .setOrgnlMsgNmId(MessageType.PACS_008.identifier()))
                .setOrgnlInstrId(r.originalInstructionId())
                .setOrgnlEndToEndId(r.originalEndToEndId())
                .setOrgnlUETR(r.originalUetr())
                .setOrgnlIntrBkSttlmAmt(Mx.amount(r.originalInterbankAmount()))
                .setRtrdIntrBkSttlmAmt(Mx.active(r.returnedAmount()))
                .setIntrBkSttlmDt(r.settlementDate())
                .setChrgBr(ChargeBearerType1Code.CRED)
                .setInstgAgt(Mx.agent(r.fromBic()))
                .setInstdAgt(Mx.agent(r.toBic()));
        for (PaymentReturn.Charge c : r.charges()) {
            tx.addChrgsInf(new Charges7().setAmt(Mx.amount(c.amount())).setAgt(Mx.agent(c.agentBic())));
        }
        PaymentReturnReason6 reason = new PaymentReturnReason6().setRsn(new ReturnReason5Choice().setCd(r.reasonCode()));
        if (r.additionalInfo() != null) {
            reason.addAddtlInf(r.additionalInfo());
        }
        tx.addRtrRsnInf(reason);

        MxPacs00400109 mx = new MxPacs00400109();
        mx.setAppHdr(Mx.header(r.fromBic(), r.toBic(), msgId, MessageType.PACS_004, created));
        mx.setPmtRtr(new PaymentReturnV09()
                .setGrpHdr(new GroupHeader90().setMsgId(msgId).setCreDtTm(created).setNbOfTxs("1")
                        .setSttlmInf(new SettlementInstruction7().setSttlmMtd(SettlementMethod1Code.INDA)))
                .addTxInf(tx));
        return mx;
    }
}
