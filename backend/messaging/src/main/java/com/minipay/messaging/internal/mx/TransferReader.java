package com.minipay.messaging.internal.mx;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.messaging.api.Charge;
import com.minipay.messaging.api.CoverTransfer;
import com.minipay.messaging.api.CreditNotification;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.PaymentReturn;
import com.minipay.messaging.api.SettlementMethod;
import com.prowidesoftware.swift.model.mx.MxCamt05400108;
import com.prowidesoftware.swift.model.mx.MxPacs00400109;
import com.prowidesoftware.swift.model.mx.MxPacs00800108;
import com.prowidesoftware.swift.model.mx.MxPacs00900108;
import com.prowidesoftware.swift.model.mx.dic.AccountNotification17;
import com.prowidesoftware.swift.model.mx.dic.BranchAndFinancialInstitutionIdentification6;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction36;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction37;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction39;
import com.prowidesoftware.swift.model.mx.dic.ReportEntry10;
import com.prowidesoftware.swift.model.mx.dic.SettlementInstruction7;
import com.prowidesoftware.swift.model.mx.dic.TransactionReferences6;
import java.util.ArrayList;
import java.util.List;

/** Reads the business content of a (checked) pacs.008, pacs.009 or camt.054 into API records. */
public final class TransferReader {

    private TransferReader() {
    }

    public static CreditTransfer pacs008(MxPacs00800108 mx) {
        var doc = mx.getFIToFICstmrCdtTrf();
        CreditTransferTransaction39 tx = doc.getCdtTrfTxInf().get(0);
        SettlementInstruction7 sttlm = doc.getGrpHdr().getSttlmInf();
        String ccy = tx.getIntrBkSttlmAmt().getCcy();
        List<Charge> charges = new ArrayList<>();
        if (tx.getChrgsInf() != null) {
            tx.getChrgsInf().forEach(c -> charges.add(new Charge(Mx.money(c.getAmt().getValue(), c.getAmt().getCcy()), Mx.bic(c.getAgt()))));
        }
        List<String> prev = new ArrayList<>();
        for (BranchAndFinancialInstitutionIdentification6 a : new BranchAndFinancialInstitutionIdentification6[] {
                tx.getPrvsInstgAgt1(), tx.getPrvsInstgAgt2(), tx.getPrvsInstgAgt3()}) {
            if (a != null) {
                prev.add(Mx.bic(a));
            }
        }
        return new CreditTransfer(
                tx.getPmtId().getUETR(), tx.getPmtId().getInstrId(), tx.getPmtId().getEndToEndId(),
                Mx.bic(tx.getInstgAgt()) != null ? Mx.bic(tx.getInstgAgt()) : mx.getAppHdr().from(),
                Mx.bic(tx.getInstdAgt()) != null ? Mx.bic(tx.getInstdAgt()) : mx.getAppHdr().to(),
                Mx.bic(tx.getDbtrAgt()), Mx.bic(tx.getCdtrAgt()),
                tx.getDbtr().getNm(), Mx.account(tx.getDbtrAcct()), tx.getCdtr().getNm(), Mx.account(tx.getCdtrAcct()),
                Mx.money(tx.getIntrBkSttlmAmt().getValue(), ccy),
                tx.getInstdAmt() != null ? Mx.money(tx.getInstdAmt().getValue(), tx.getInstdAmt().getCcy()) : null,
                charges, ChargeBearer.valueOf(tx.getChrgBr().name()), tx.getIntrBkSttlmDt(),
                SettlementMethod.valueOf(sttlm.getSttlmMtd().name()),
                sttlm.getInstgRmbrsmntAgt() != null ? Mx.bic(sttlm.getInstgRmbrsmntAgt()) : null,
                prev,
                tx.getRmtInf() != null && tx.getRmtInf().getUstrd() != null && !tx.getRmtInf().getUstrd().isEmpty()
                        ? String.join(" ", tx.getRmtInf().getUstrd()) : null);
    }

    public static CoverTransfer pacs009(MxPacs00900108 mx) {
        CreditTransferTransaction36 tx = mx.getFICdtTrf().getCdtTrfTxInf().get(0);
        Money amount = Mx.money(tx.getIntrBkSttlmAmt().getValue(), tx.getIntrBkSttlmAmt().getCcy());
        CreditTransferTransaction37 u = tx.getUndrlygCstmrCdtTrf();
        CreditTransfer underlying = u == null ? null : new CreditTransfer(
                tx.getPmtId().getUETR(), tx.getPmtId().getInstrId(), tx.getPmtId().getEndToEndId(),
                Mx.bic(tx.getInstgAgt()), Mx.bic(tx.getInstdAgt()), Mx.bic(u.getDbtrAgt()), Mx.bic(u.getCdtrAgt()),
                u.getDbtr() != null ? u.getDbtr().getNm() : null, Mx.account(u.getDbtrAcct()),
                u.getCdtr() != null ? u.getCdtr().getNm() : null, Mx.account(u.getCdtrAcct()),
                amount, u.getInstdAmt() != null ? Mx.money(u.getInstdAmt().getValue(), u.getInstdAmt().getCcy()) : null,
                List.of(), ChargeBearer.SHAR, tx.getIntrBkSttlmDt(), SettlementMethod.COVE, null, List.of(),
                u.getRmtInf() != null && u.getRmtInf().getUstrd() != null && !u.getRmtInf().getUstrd().isEmpty()
                        ? String.join(" ", u.getRmtInf().getUstrd()) : null);
        return new CoverTransfer(tx.getPmtId().getUETR(), tx.getPmtId().getInstrId(), tx.getPmtId().getEndToEndId(),
                Mx.bic(tx.getInstgAgt()), Mx.bic(tx.getInstdAgt()), Mx.bic(tx.getDbtr()), Mx.bic(tx.getCdtr()),
                amount, tx.getIntrBkSttlmDt(), underlying);
    }

    public static PaymentReturn pacs004(MxPacs00400109 mx) {
        var tx = mx.getPmtRtr().getTxInf().get(0);
        List<Charge> charges = new ArrayList<>();
        if (tx.getChrgsInf() != null) {
            tx.getChrgsInf().forEach(c -> charges.add(new Charge(Mx.money(c.getAmt().getValue(), c.getAmt().getCcy()), Mx.bic(c.getAgt()))));
        }
        var reason = tx.getRtrRsnInf() == null || tx.getRtrRsnInf().isEmpty() ? null : tx.getRtrRsnInf().get(0);
        return new PaymentReturn(mx.getAppHdr().from(), mx.getAppHdr().to(), tx.getRtrId(),
                tx.getOrgnlGrpInf() != null ? tx.getOrgnlGrpInf().getOrgnlMsgId() : null, tx.getOrgnlInstrId(),
                tx.getOrgnlEndToEndId(), tx.getOrgnlUETR(),
                tx.getOrgnlIntrBkSttlmAmt() == null ? null : Mx.money(tx.getOrgnlIntrBkSttlmAmt().getValue(), tx.getOrgnlIntrBkSttlmAmt().getCcy()),
                Mx.money(tx.getRtrdIntrBkSttlmAmt().getValue(), tx.getRtrdIntrBkSttlmAmt().getCcy()), tx.getIntrBkSttlmDt(),
                charges, reason == null || reason.getRsn() == null ? null : reason.getRsn().getCd(),
                reason == null || reason.getAddtlInf() == null || reason.getAddtlInf().isEmpty() ? null : String.join(" ", reason.getAddtlInf()));
    }

    public static List<Charge> pacs002Charges(com.prowidesoftware.swift.model.mx.MxPacs00200110 mx) {
        var tx = mx.getFIToFIPmtStsRpt().getTxInfAndSts().get(0);
        List<Charge> charges = new ArrayList<>();
        if (tx.getChrgsInf() != null) {
            tx.getChrgsInf().forEach(c -> charges.add(new Charge(Mx.money(c.getAmt().getValue(), c.getAmt().getCcy()), Mx.bic(c.getAgt()))));
        }
        return charges;
    }

    public static CreditNotification camt054(MxCamt05400108 mx) {
        AccountNotification17 n = mx.getBkToCstmrDbtCdtNtfctn().getNtfctn().get(0);
        ReportEntry10 e = n.getNtry().get(0);
        TransactionReferences6 refs = e.getNtryDtls() != null && !e.getNtryDtls().isEmpty()
                && e.getNtryDtls().get(0).getTxDtls() != null && !e.getNtryDtls().get(0).getTxDtls().isEmpty()
                ? e.getNtryDtls().get(0).getTxDtls().get(0).getRefs() : null;
        String account = n.getAcct().getId().getIBAN() != null ? n.getAcct().getId().getIBAN()
                : n.getAcct().getId().getOthr() != null ? n.getAcct().getId().getOthr().getId() : null;
        return new CreditNotification(mx.getAppHdr().from(), mx.getAppHdr().to(), account,
                Mx.money(e.getAmt().getValue(), e.getAmt().getCcy()),
                e.getValDt() != null ? e.getValDt().getDt() : null,
                e.getBookgDt() != null && e.getBookgDt().getDtTm() != null ? e.getBookgDt().getDtTm().toInstant() : null,
                refs != null ? refs.getUETR() : null, refs != null ? refs.getEndToEndId() : null,
                refs != null ? refs.getInstrId() : null, e.getAcctSvcrRef());
    }
}
