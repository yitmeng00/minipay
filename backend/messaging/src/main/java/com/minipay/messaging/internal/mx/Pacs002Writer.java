package com.minipay.messaging.internal.mx;

import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.StatusReport;
import com.prowidesoftware.swift.model.mx.MxPacs00200110;
import com.prowidesoftware.swift.model.mx.dic.FIToFIPaymentStatusReportV10;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader91;
import com.prowidesoftware.swift.model.mx.dic.OriginalGroupInformation29;
import com.prowidesoftware.swift.model.mx.dic.PaymentTransaction110;
import com.prowidesoftware.swift.model.mx.dic.StatusReason6Choice;
import com.prowidesoftware.swift.model.mx.dic.StatusReasonInformation12;
import java.time.OffsetDateTime;

/** pacs.002.001.10: the status of one original pacs.008 (ACCP, ACSP, ACSC or RJCT with a reason). */
public final class Pacs002Writer {

    private Pacs002Writer() {
    }

    public static MxPacs00200110 build(StatusReport r, String msgId, OffsetDateTime created) {
        PaymentTransaction110 tx = new PaymentTransaction110()
                .setOrgnlGrpInf(new OriginalGroupInformation29()
                        .setOrgnlMsgId(r.originalMsgId())
                        .setOrgnlMsgNmId(MessageType.PACS_008.identifier()))
                .setOrgnlInstrId(r.originalInstructionId())
                .setOrgnlEndToEndId(r.originalEndToEndId())
                .setOrgnlUETR(r.originalUetr())
                .setTxSts(r.status().name())
                .setInstgAgt(Mx.agent(r.fromBic()))
                .setInstdAgt(Mx.agent(r.toBic()));
        if (r.reasonCode() != null) {
            StatusReasonInformation12 rsn = new StatusReasonInformation12().setRsn(new StatusReason6Choice().setCd(r.reasonCode()));
            if (r.additionalInfo() != null) {
                rsn.addAddtlInf(r.additionalInfo());
            }
            tx.addStsRsnInf(rsn);
        }
        for (com.minipay.messaging.api.Charge c : r.charges()) {
            tx.addChrgsInf(new com.prowidesoftware.swift.model.mx.dic.Charges7().setAmt(Mx.amount(c.amount())).setAgt(Mx.agent(c.agentBic())));
        }
        MxPacs00200110 mx = new MxPacs00200110();
        mx.setAppHdr(Mx.header(r.fromBic(), r.toBic(), msgId, MessageType.PACS_002, created));
        mx.setFIToFIPmtStsRpt(new FIToFIPaymentStatusReportV10()
                .setGrpHdr(new GroupHeader91().setMsgId(msgId).setCreDtTm(created))
                .addTxInfAndSts(tx));
        return mx;
    }
}
