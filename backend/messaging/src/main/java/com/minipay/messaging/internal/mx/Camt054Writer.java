package com.minipay.messaging.internal.mx;

import com.minipay.messaging.api.CreditNotification;
import com.minipay.messaging.api.MessageType;
import com.prowidesoftware.swift.model.mx.MxCamt05400108;
import com.prowidesoftware.swift.model.mx.dic.AccountNotification17;
import com.prowidesoftware.swift.model.mx.dic.BankToCustomerDebitCreditNotificationV08;
import com.prowidesoftware.swift.model.mx.dic.BankTransactionCodeStructure4;
import com.prowidesoftware.swift.model.mx.dic.BankTransactionCodeStructure5;
import com.prowidesoftware.swift.model.mx.dic.BankTransactionCodeStructure6;
import com.prowidesoftware.swift.model.mx.dic.CashAccount39;
import com.prowidesoftware.swift.model.mx.dic.CreditDebitCode;
import com.prowidesoftware.swift.model.mx.dic.DateAndDateTime2Choice;
import com.prowidesoftware.swift.model.mx.dic.EntryDetails9;
import com.prowidesoftware.swift.model.mx.dic.EntryStatus1Choice;
import com.prowidesoftware.swift.model.mx.dic.EntryTransaction10;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader81;
import com.prowidesoftware.swift.model.mx.dic.ReportEntry10;
import com.prowidesoftware.swift.model.mx.dic.TransactionReferences6;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * camt.054.001.08: "your account with us was credited", one entry. In a cover payment the
 * correspondent sends it to the creditor's bank, with the UETR, so the creditor's bank can match it
 * to the pacs.008 it is holding and pay its customer.
 */
public final class Camt054Writer {

    private Camt054Writer() {
    }

    public static MxCamt05400108 build(CreditNotification n, String msgId, OffsetDateTime created, ZoneId zone) {
        ReportEntry10 entry = new ReportEntry10()
                .setAmt(Mx.amount(n.amount()))
                .setCdtDbtInd(CreditDebitCode.CRDT)
                .setSts(new EntryStatus1Choice().setCd("BOOK"))
                .setBookgDt(new DateAndDateTime2Choice().setDtTm(n.bookedAt().atZone(zone).toOffsetDateTime()))
                .setValDt(new DateAndDateTime2Choice().setDt(n.valueDate()))
                .setAcctSvcrRef(n.servicerReference())
                .setBkTxCd(new BankTransactionCodeStructure4().setDomn(new BankTransactionCodeStructure5().setCd("PMNT")
                        .setFmly(new BankTransactionCodeStructure6().setCd("RCDT").setSubFmlyCd("XBCT"))))
                .addNtryDtls(new EntryDetails9().addTxDtls(new EntryTransaction10().setRefs(new TransactionReferences6()
                        .setAcctSvcrRef(n.servicerReference())
                        .setInstrId(n.instructionId())
                        .setEndToEndId(n.endToEndId())
                        .setUETR(n.uetr()))));
        MxCamt05400108 mx = new MxCamt05400108();
        mx.setAppHdr(Mx.header(n.fromBic(), n.toBic(), msgId, MessageType.CAMT_054, created));
        mx.setBkToCstmrDbtCdtNtfctn(new BankToCustomerDebitCreditNotificationV08()
                .setGrpHdr(new GroupHeader81().setMsgId(msgId).setCreDtTm(created))
                .addNtfctn(new AccountNotification17()
                        .setId(msgId)
                        .setCreDtTm(created)
                        .setAcct(new CashAccount39().setId(Mx.account(n.account()).getId()).setCcy(n.amount().currencyCode())
                                .setSvcr(Mx.agent(n.fromBic())))
                        .addNtry(entry)));
        return mx;
    }
}
