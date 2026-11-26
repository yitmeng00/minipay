package com.minipay.messaging.internal.mx;

import com.minipay.common.money.Money;
import com.minipay.messaging.api.BankStatement;
import com.minipay.messaging.api.StatementTx;
import com.prowidesoftware.swift.model.mx.MxCamt05300108;
import com.prowidesoftware.swift.model.mx.dic.AccountStatement9;
import com.prowidesoftware.swift.model.mx.dic.CashBalance8;
import com.prowidesoftware.swift.model.mx.dic.CreditDebitCode;
import com.prowidesoftware.swift.model.mx.dic.EntryDetails9;
import com.prowidesoftware.swift.model.mx.dic.EntryTransaction10;
import com.prowidesoftware.swift.model.mx.dic.ReportEntry10;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a camt.053 for the account owner (M8): balances, entries, each entry's transactions and its own XML. */
public final class StatementReader {

    private static final Pattern NTRY = Pattern.compile("<([A-Za-z0-9_]+:)?Ntry>.*?</\\1?Ntry>", Pattern.DOTALL);

    private StatementReader() {
    }

    public static BankStatement read(MxCamt05300108 mx, String raw) {
        AccountStatement9 s = mx.getBkToCstmrStmt().getStmt().get(0);
        String ccy = s.getAcct().getCcy();
        String account = Mx.account(new com.prowidesoftware.swift.model.mx.dic.CashAccount38().setId(s.getAcct().getId()));
        Money opening = null;
        Money closing = null;
        for (CashBalance8 b : s.getBal()) {
            String code = b.getTp().getCdOrPrtry().getCd();
            Money m = signed(Money.of(b.getAmt().getValue(), b.getAmt().getCcy()), b.getCdtDbtInd());
            if ("OPBD".equals(code)) {
                opening = m;
            } else if ("CLBD".equals(code)) {
                closing = m;
            }
        }
        LocalDate date = s.getBal().isEmpty() ? null : s.getBal().get(0).getDt().getDt();
        List<String> xml = new ArrayList<>();
        Matcher m = NTRY.matcher(raw);
        while (m.find()) {
            xml.add(m.group());
        }
        List<BankStatement.Entry> entries = new ArrayList<>();
        for (int i = 0; i < s.getNtry().size(); i++) {
            entries.add(entry(s.getNtry().get(i), ccy, i < xml.size() ? xml.get(i) : null));
        }
        String servicer = mx.getAppHdr().from();
        return new BankStatement(servicer, account, ccy, s.getId(), date, opening, closing, entries);
    }

    private static BankStatement.Entry entry(ReportEntry10 e, String ccy, String xml) {
        boolean credit = e.getCdtDbtInd() == CreditDebitCode.CRDT;
        List<StatementTx> txs = new ArrayList<>();
        if (e.getNtryDtls() != null) {
            for (EntryDetails9 d : e.getNtryDtls()) {
                if (d.getTxDtls() == null) {
                    continue;
                }
                for (EntryTransaction10 t : d.getTxDtls()) {
                    var r = t.getRefs();
                    if (r == null || (r.getUETR() == null && r.getEndToEndId() == null)) {
                        continue;
                    }
                    Money amount = t.getAmt() != null ? Money.of(t.getAmt().getValue(), t.getAmt().getCcy())
                            : Money.of(e.getAmt().getValue(), e.getAmt().getCcy());
                    boolean txCredit = t.getCdtDbtInd() != null ? t.getCdtDbtInd() == CreditDebitCode.CRDT : credit;
                    txs.add(new StatementTx(r.getUETR(), r.getEndToEndId(), r.getInstrId(), amount, txCredit));
                }
            }
        }
        Instant booked = e.getBookgDt() == null ? null : e.getBookgDt().getDtTm() != null ? e.getBookgDt().getDtTm().toInstant() : null;
        LocalDate value = e.getValDt() == null ? null : e.getValDt().getDt();
        String code = e.getBkTxCd() == null || e.getBkTxCd().getDomn() == null ? null
                : e.getBkTxCd().getDomn().getCd() + "/" + e.getBkTxCd().getDomn().getFmly().getCd() + "/"
                + e.getBkTxCd().getDomn().getFmly().getSubFmlyCd();
        return new BankStatement.Entry(e.getNtryRef(), Money.of(e.getAmt().getValue(), ccy), credit, booked, value,
                e.getAcctSvcrRef(), Boolean.TRUE.equals(e.isRvslInd()), e.getAddtlNtryInf(), code, txs, xml);
    }

    private static Money signed(Money m, CreditDebitCode c) {
        return c == CreditDebitCode.DBIT ? m.negate() : m;
    }
}
