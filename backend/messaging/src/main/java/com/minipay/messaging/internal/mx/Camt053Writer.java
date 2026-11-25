package com.minipay.messaging.internal.mx;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.StatementLine;
import com.minipay.ledger.api.StatementView;
import com.minipay.messaging.api.MessageType;
import com.prowidesoftware.swift.model.mx.MxCamt05300108;
import com.prowidesoftware.swift.model.mx.dic.AccountStatement9;
import com.prowidesoftware.swift.model.mx.dic.BalanceType10Choice;
import com.prowidesoftware.swift.model.mx.dic.BalanceType13;
import com.prowidesoftware.swift.model.mx.dic.BankToCustomerStatementV08;
import com.prowidesoftware.swift.model.mx.dic.BankTransactionCodeStructure4;
import com.prowidesoftware.swift.model.mx.dic.BankTransactionCodeStructure5;
import com.prowidesoftware.swift.model.mx.dic.BankTransactionCodeStructure6;
import com.prowidesoftware.swift.model.mx.dic.CashAccount39;
import com.prowidesoftware.swift.model.mx.dic.CashBalance8;
import com.prowidesoftware.swift.model.mx.dic.CreditDebitCode;
import com.prowidesoftware.swift.model.mx.dic.DateAndDateTime2Choice;
import com.prowidesoftware.swift.model.mx.dic.DateTimePeriod1;
import com.prowidesoftware.swift.model.mx.dic.EntryDetails9;
import com.prowidesoftware.swift.model.mx.dic.EntryStatus1Choice;
import com.prowidesoftware.swift.model.mx.dic.EntryTransaction10;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader81;
import com.prowidesoftware.swift.model.mx.dic.NumberAndSumOfTransactions1;
import com.prowidesoftware.swift.model.mx.dic.PartyIdentification135;
import com.prowidesoftware.swift.model.mx.dic.ReportEntry10;
import com.prowidesoftware.swift.model.mx.dic.TotalTransactions6;
import com.prowidesoftware.swift.model.mx.dic.TransactionReferences6;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * camt.053.001.08: an end-of-day statement for an account we keep for someone else.
 *
 * <h2>Whose point of view?</h2>
 * The statement is written for the <em>account holder</em>. For the accounts we statement (a
 * customer's account, another bank's vostro) the holder's view and our ledger agree: they are
 * liabilities on our books, so a ledger credit is money paid in (CRDT) and a ledger debit is money
 * paid out (DBIT). A positive balance is a credit balance. The camt.053 for our own nostro comes
 * from the bank that keeps it, not from us — that is what reconciliation (M8) compares against.
 *
 * <h2>Bank transaction codes</h2>
 * Domain / family / sub-family from the ISO external code list:
 * PMNT/ICDT/XBCT (cross-border credit transfer sent), PMNT/RCDT/XBCT (received), PMNT/MDOP/CHRG
 * (charges). A reversal keeps the code of what it reverses, with RvslInd = true.
 */
public final class Camt053Writer {

    private Camt053Writer() {
    }

    public static MxCamt05300108 build(StatementView s, String servicerBic, String receiverBic, String msgId,
                                       String statementId, long sequence, OffsetDateTime created, ZoneId zone) {
        String ccy = s.account().currency();
        AccountStatement9 stmt = new AccountStatement9()
                .setId(statementId)
                .setElctrncSeqNb(BigDecimal.valueOf(sequence))
                .setCreDtTm(created)
                .setFrToDt(new DateTimePeriod1()
                        .setFrDtTm(s.date().atStartOfDay(zone).toOffsetDateTime())
                        .setToDtTm(s.date().plusDays(1).atStartOfDay(zone).minusNanos(1_000_000).toOffsetDateTime()))
                .setAcct(new CashAccount39()
                        .setId(Mx.account(s.account().accountNumber()).getId())
                        .setCcy(ccy)
                        .setOwnr(new PartyIdentification135().setNm(s.account().ownerName()))
                        .setSvcr(Mx.agent(servicerBic)))
                .addBal(balance("OPBD", s.openingBooked(), s))
                .addBal(balance("CLBD", s.closingBooked(), s))
                .setTxsSummry(new TotalTransactions6()
                        .setTtlCdtNtries(new NumberAndSumOfTransactions1()
                                .setNbOfNtries(Integer.toString(s.creditCount())).setSum(s.totalCredits().amount()))
                        .setTtlDbtNtries(new NumberAndSumOfTransactions1()
                                .setNbOfNtries(Integer.toString(s.debitCount())).setSum(s.totalDebits().amount())));
        for (StatementLine line : s.lines()) {
            stmt.addNtry(entry(line, zone));
        }

        MxCamt05300108 mx = new MxCamt05300108();
        mx.setAppHdr(Mx.header(servicerBic, receiverBic != null ? receiverBic : servicerBic, msgId, MessageType.CAMT_053, created));
        mx.setBkToCstmrStmt(new BankToCustomerStatementV08()
                .setGrpHdr(new GroupHeader81().setMsgId(msgId).setCreDtTm(created)
                        .setMsgRcpt(new PartyIdentification135().setNm(s.account().ownerName())))
                .addStmt(stmt));
        return mx;
    }

    private static CashBalance8 balance(String type, Money m, StatementView s) {
        return new CashBalance8()
                .setTp(new BalanceType13().setCdOrPrtry(new BalanceType10Choice().setCd(type)))
                .setAmt(Mx.amount(m.isNegative() ? m.negate() : m))
                .setCdtDbtInd(m.isNegative() ? CreditDebitCode.DBIT : CreditDebitCode.CRDT)
                .setDt(new DateAndDateTime2Choice().setDt(s.date()));
    }

    private static ReportEntry10 entry(StatementLine l, ZoneId zone) {
        boolean credit = l.credit() != null;
        Money amount = credit ? l.credit() : l.debit();
        // AcctSvcrRef is Max35Text: the journal entry id without hyphens (32 characters).
        String ref = l.journalEntryId().toString().replace("-", "");
        ReportEntry10 e = new ReportEntry10()
                .setNtryRef(Long.toString(l.postingId()))
                .setAmt(Mx.amount(amount))
                .setCdtDbtInd(credit ? CreditDebitCode.CRDT : CreditDebitCode.DBIT)
                .setSts(new EntryStatus1Choice().setCd("BOOK"))
                .setBookgDt(new DateAndDateTime2Choice().setDtTm(l.bookedAt().atZone(zone).toOffsetDateTime()))
                .setValDt(new DateAndDateTime2Choice().setDt(l.valueDate()))
                .setAcctSvcrRef(ref)
                .setBkTxCd(code(l.entryType(), credit))
                .addNtryDtls(new EntryDetails9().addTxDtls(new EntryTransaction10()
                        .setRefs(new TransactionReferences6().setAcctSvcrRef(ref))));
        if (l.entryType() == EntryType.REVERSAL) {
            e.setRvslInd(true);
        }
        if (l.narrative() != null) {
            e.setAddtlNtryInf(l.narrative().length() > 500 ? l.narrative().substring(0, 500) : l.narrative());
        }
        return e;
    }

    private static BankTransactionCodeStructure4 code(EntryType type, boolean credit) {
        String family;
        String subFamily;
        if (type == EntryType.FEE) {
            family = "MDOP";
            subFamily = "CHRG";
        } else {
            family = credit ? "RCDT" : "ICDT";
            subFamily = "XBCT";
        }
        return new BankTransactionCodeStructure4().setDomn(new BankTransactionCodeStructure5().setCd("PMNT")
                .setFmly(new BankTransactionCodeStructure6().setCd(family).setSubFmlyCd(subFamily)));
    }
}
