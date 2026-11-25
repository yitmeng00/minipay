package com.minipay.messaging.internal.mx;

import com.minipay.common.id.Bic;
import com.minipay.common.id.Uetr;
import com.minipay.common.money.Money;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.TransactionStatus;
import com.prowidesoftware.swift.model.mx.AbstractMX;
import com.prowidesoftware.swift.model.mx.AppHdr;
import com.prowidesoftware.swift.model.mx.MxCamt05300108;
import com.prowidesoftware.swift.model.mx.MxPacs00200110;
import com.prowidesoftware.swift.model.mx.MxPacs00400109;
import com.prowidesoftware.swift.model.mx.MxPacs00800108;
import com.prowidesoftware.swift.model.mx.dic.AccountStatement9;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction39;
import com.prowidesoftware.swift.model.mx.dic.PaymentTransaction110;
import com.prowidesoftware.swift.model.mx.dic.PaymentTransaction112;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads an MX business message (AppHdr + Document) and checks it.
 *
 * <p>The checks are a CBPR+-inspired subset that the simulator can enforce without the published
 * XSDs and usage guidelines (ADR-005 §4):
 * <ul>
 *   <li>a Business Application Header, with valid BICs, naming the same message type and MsgId as the Document;</li>
 *   <li>one transaction per message (CBPR+ allows no batches);</li>
 *   <li>a UETR in the canonical lower-case UUIDv4 form, and an InstrId of at most 16 characters;</li>
 *   <li>positive amounts with no more decimals than the currency has;</li>
 *   <li>status codes and return reasons that are real ISO codes in shape (RJCT and returns need a reason).</li>
 * </ul>
 * Every failed check is collected, not just the first, so the operator sees everything wrong at once.
 */
public final class MxReader {

    /** What was read. Fields are null when the message did not carry them (or could not be read). */
    public record Parsed(
            MessageType type,
            String msgId,
            String from,
            String to,
            String uetr,
            String endToEndId,
            String instructionId,
            String originalMsgId,
            String account,
            Money amount,
            LocalDate settlementDate,
            String status,
            String reasonCode,
            String additionalInfo,
            List<String> errors) {
    }

    private MxReader() {
    }

    /**
     * @param expectedReceiver for inbound messages, our own BIC: a message addressed to someone else is
     *                         an error. Null to skip that check (e.g. when checking our own output).
     */
    public static Parsed read(String raw, String expectedReceiver) {
        AbstractMX mx;
        try {
            mx = AbstractMX.parse(raw);
        } catch (RuntimeException e) {
            mx = null;
        }
        if (mx == null) {
            return failed("Not a readable ISO 20022 business message (AppHdr + Document)");
        }
        MessageType type;
        try {
            type = MessageType.fromIdentifier(mx.getMxId().id());
        } catch (RuntimeException e) {
            return failed("Unsupported message type " + mx.getMxId().id()
                    + " (supported: pacs.008.001.08, pacs.002.001.10, pacs.004.001.09, camt.053.001.08)");
        }
        Builder b = new Builder(type);
        AppHdr h = mx.getAppHdr();
        if (h == null) {
            b.error("Business Application Header (AppHdr) is missing");
        } else {
            b.from = h.from();
            b.to = h.to();
            checkBic(b, "AppHdr/Fr", b.from);
            checkBic(b, "AppHdr/To", b.to);
            if (h.messageName() != null && !h.messageName().equals(type.identifier())) {
                b.error("AppHdr/MsgDefIdr " + h.messageName() + " does not match the Document " + type.identifier());
            }
            if (expectedReceiver != null && b.to != null && Bic.isValid(b.to)
                    && !Bic.parse(b.to).bic8().equals(Bic.parse(expectedReceiver).bic8())) {
                b.error("Addressed to " + b.to + ", not to us (" + expectedReceiver + ")");
            }
        }
        switch (type) {
            case PACS_008 -> readPacs008((MxPacs00800108) mx, b);
            case PACS_002 -> readPacs002((MxPacs00200110) mx, b);
            case PACS_004 -> readPacs004((MxPacs00400109) mx, b);
            case CAMT_053 -> readCamt053((MxCamt05300108) mx, b);
            default -> b.error("Not an MX message");
        }
        if (b.msgId == null || b.msgId.isBlank() || b.msgId.length() > 35) {
            b.error("GrpHdr/MsgId must be 1–35 characters");
        } else if (h != null && h.reference() != null && !h.reference().equals(b.msgId)) {
            b.error("AppHdr/BizMsgIdr " + h.reference() + " differs from GrpHdr/MsgId " + b.msgId);
        }
        return b.build();
    }

    // ------------------------------------------------------------------ per message type

    private static void readPacs008(MxPacs00800108 mx, Builder b) {
        var doc = mx.getFIToFICstmrCdtTrf();
        if (doc == null || doc.getGrpHdr() == null) {
            b.error("FIToFICstmrCdtTrf/GrpHdr is missing");
            return;
        }
        b.msgId = doc.getGrpHdr().getMsgId();
        if (doc.getCdtTrfTxInf() == null || doc.getCdtTrfTxInf().size() != 1) {
            b.error("Exactly one CdtTrfTxInf is required (CBPR+ does not allow batches)");
            return;
        }
        CreditTransferTransaction39 tx = doc.getCdtTrfTxInf().get(0);
        if (tx.getPmtId() != null) {
            b.uetr = tx.getPmtId().getUETR();
            b.endToEndId = tx.getPmtId().getEndToEndId();
            b.instructionId = tx.getPmtId().getInstrId();
        }
        checkUetr(b, "PmtId/UETR", b.uetr);
        if (b.instructionId != null && b.instructionId.length() > 16) {
            b.error("PmtId/InstrId must be at most 16 characters (CBPR+, so that it fits MT103 :20:)");
        }
        if (tx.getIntrBkSttlmAmt() != null) {
            b.amount = checkAmount(b, "IntrBkSttlmAmt", tx.getIntrBkSttlmAmt().getValue(), tx.getIntrBkSttlmAmt().getCcy());
        } else {
            b.error("IntrBkSttlmAmt is missing");
        }
        b.settlementDate = tx.getIntrBkSttlmDt();
        if (b.settlementDate == null) {
            b.error("IntrBkSttlmDt is missing");
        }
        if (tx.getChrgBr() == null) {
            b.error("ChrgBr is missing");
        }
        if (tx.getDbtr() == null || tx.getDbtr().getNm() == null) {
            b.error("Dbtr/Nm is missing");
        }
        if (tx.getCdtr() == null || tx.getCdtr().getNm() == null) {
            b.error("Cdtr/Nm is missing");
        }
        checkBic(b, "DbtrAgt", Mx.bic(tx.getDbtrAgt()));
        checkBic(b, "CdtrAgt", Mx.bic(tx.getCdtrAgt()));
        b.account = Mx.account(tx.getCdtrAcct());
    }

    private static void readPacs002(MxPacs00200110 mx, Builder b) {
        var doc = mx.getFIToFIPmtStsRpt();
        if (doc == null || doc.getGrpHdr() == null) {
            b.error("FIToFIPmtStsRpt/GrpHdr is missing");
            return;
        }
        b.msgId = doc.getGrpHdr().getMsgId();
        if (doc.getTxInfAndSts() == null || doc.getTxInfAndSts().size() != 1) {
            b.error("Exactly one TxInfAndSts is required");
            return;
        }
        PaymentTransaction110 tx = doc.getTxInfAndSts().get(0);
        b.uetr = tx.getOrgnlUETR();
        b.endToEndId = tx.getOrgnlEndToEndId();
        b.instructionId = tx.getOrgnlInstrId();
        b.originalMsgId = tx.getOrgnlGrpInf() != null ? tx.getOrgnlGrpInf().getOrgnlMsgId() : null;
        checkUetr(b, "OrgnlUETR", b.uetr);
        b.status = tx.getTxSts();
        if (b.status == null || Arrays.stream(TransactionStatus.values()).noneMatch(s -> s.name().equals(b.status))) {
            b.error("TxSts " + b.status + " is not one of " + Arrays.toString(TransactionStatus.values()));
        }
        if (tx.getStsRsnInf() != null && !tx.getStsRsnInf().isEmpty() && tx.getStsRsnInf().get(0).getRsn() != null) {
            b.reasonCode = tx.getStsRsnInf().get(0).getRsn().getCd();
            List<String> info = tx.getStsRsnInf().get(0).getAddtlInf();
            b.additionalInfo = info == null || info.isEmpty() ? null : String.join(" ", info);
        }
        if ("RJCT".equals(b.status)) {
            checkReason(b, "StsRsnInf/Rsn/Cd", b.reasonCode);
        }
    }

    private static void readPacs004(MxPacs00400109 mx, Builder b) {
        var doc = mx.getPmtRtr();
        if (doc == null || doc.getGrpHdr() == null) {
            b.error("PmtRtr/GrpHdr is missing");
            return;
        }
        b.msgId = doc.getGrpHdr().getMsgId();
        if (doc.getTxInf() == null || doc.getTxInf().size() != 1) {
            b.error("Exactly one TxInf is required");
            return;
        }
        PaymentTransaction112 tx = doc.getTxInf().get(0);
        b.uetr = tx.getOrgnlUETR();
        b.endToEndId = tx.getOrgnlEndToEndId();
        b.instructionId = tx.getOrgnlInstrId();
        b.originalMsgId = tx.getOrgnlGrpInf() != null ? tx.getOrgnlGrpInf().getOrgnlMsgId() : null;
        b.status = "RTND";
        checkUetr(b, "OrgnlUETR", b.uetr);
        if (tx.getRtrdIntrBkSttlmAmt() == null) {
            b.error("RtrdIntrBkSttlmAmt is missing");
        } else {
            b.amount = checkAmount(b, "RtrdIntrBkSttlmAmt", tx.getRtrdIntrBkSttlmAmt().getValue(), tx.getRtrdIntrBkSttlmAmt().getCcy());
        }
        if (tx.getOrgnlIntrBkSttlmAmt() != null && b.amount != null) {
            Money original = Mx.money(tx.getOrgnlIntrBkSttlmAmt().getValue(), tx.getOrgnlIntrBkSttlmAmt().getCcy());
            if (original != null && original.currencyCode().equals(b.amount.currencyCode()) && b.amount.isGreaterThan(original)) {
                b.error("Returned amount " + b.amount + " exceeds the original " + original);
            }
        }
        b.settlementDate = tx.getIntrBkSttlmDt();
        if (tx.getRtrRsnInf() != null && !tx.getRtrRsnInf().isEmpty() && tx.getRtrRsnInf().get(0).getRsn() != null) {
            b.reasonCode = tx.getRtrRsnInf().get(0).getRsn().getCd();
            List<String> info = tx.getRtrRsnInf().get(0).getAddtlInf();
            b.additionalInfo = info == null || info.isEmpty() ? null : String.join(" ", info);
        }
        checkReason(b, "RtrRsnInf/Rsn/Cd", b.reasonCode);
    }

    private static void readCamt053(MxCamt05300108 mx, Builder b) {
        var doc = mx.getBkToCstmrStmt();
        if (doc == null || doc.getGrpHdr() == null) {
            b.error("BkToCstmrStmt/GrpHdr is missing");
            return;
        }
        b.msgId = doc.getGrpHdr().getMsgId();
        if (doc.getStmt() == null || doc.getStmt().size() != 1) {
            b.error("Exactly one Stmt is expected");
            return;
        }
        AccountStatement9 s = doc.getStmt().get(0);
        if (s.getAcct() == null || s.getAcct().getId() == null) {
            b.error("Stmt/Acct/Id is missing");
        } else {
            b.account = s.getAcct().getId().getIBAN() != null ? s.getAcct().getId().getIBAN()
                    : s.getAcct().getId().getOthr() != null ? s.getAcct().getId().getOthr().getId() : null;
        }
        if (s.getBal() != null) {
            s.getBal().stream()
                    .filter(bal -> bal.getTp() != null && bal.getTp().getCdOrPrtry() != null
                            && "CLBD".equals(bal.getTp().getCdOrPrtry().getCd()))
                    .findFirst().ifPresent(bal -> {
                        Money m = Mx.money(bal.getAmt().getValue(), bal.getAmt().getCcy());
                        b.amount = m != null && bal.getCdtDbtInd() != null && "DBIT".equals(bal.getCdtDbtInd().name()) ? m.negate() : m;
                        b.settlementDate = bal.getDt() != null ? bal.getDt().getDt() : null;
                    });
        }
    }

    // ------------------------------------------------------------------ checks

    private static void checkBic(Builder b, String where, String bic) {
        if (bic == null || !Bic.isValid(bic)) {
            b.error(where + " is not a valid BIC: " + bic);
        }
    }

    private static void checkUetr(Builder b, String where, String uetr) {
        if (uetr == null || !Uetr.isValid(uetr)) {
            b.error(where + " must be a lower-case UUIDv4, got " + uetr);
        }
    }

    private static Money checkAmount(Builder b, String where, java.math.BigDecimal value, String ccy) {
        Money m = Mx.money(value, ccy);
        if (m == null) {
            b.error(where + " " + value + " " + ccy + " has more decimals than " + ccy + " allows, or an unknown currency");
        } else if (!m.isPositive()) {
            b.error(where + " must be positive");
        }
        return m;
    }

    private static void checkReason(Builder b, String where, String code) {
        if (code == null || !code.matches("[A-Z0-9]{4}")) {
            b.error(where + " must be a 4-character ISO reason code, got " + code);
        }
    }

    private static Parsed failed(String error) {
        return new Parsed(null, null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(error));
    }

    private static final class Builder {
        final MessageType type;
        final List<String> errors = new ArrayList<>();
        String msgId, from, to, uetr, endToEndId, instructionId, originalMsgId, account, status, reasonCode, additionalInfo;
        Money amount;
        LocalDate settlementDate;

        Builder(MessageType type) {
            this.type = type;
        }

        void error(String e) {
            errors.add(e);
        }

        Parsed build() {
            return new Parsed(type, msgId, from, to, uetr, endToEndId, instructionId, originalMsgId, account, amount,
                    settlementDate, status, reasonCode, additionalInfo, List.copyOf(errors));
        }
    }
}
