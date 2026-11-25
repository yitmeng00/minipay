package com.minipay.messaging.internal.mt;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.FieldMapping;
import com.minipay.messaging.api.Fidelity;
import com.minipay.messaging.api.SettlementMethod;
import com.minipay.messaging.internal.mx.Mx;
import com.prowidesoftware.swift.model.field.Field;
import com.prowidesoftware.swift.model.field.Field20;
import com.prowidesoftware.swift.model.field.Field23B;
import com.prowidesoftware.swift.model.field.Field32A;
import com.prowidesoftware.swift.model.field.Field33B;
import com.prowidesoftware.swift.model.field.Field50K;
import com.prowidesoftware.swift.model.field.Field52A;
import com.prowidesoftware.swift.model.field.Field57A;
import com.prowidesoftware.swift.model.field.Field59;
import com.prowidesoftware.swift.model.field.Field70;
import com.prowidesoftware.swift.model.field.Field71A;
import com.prowidesoftware.swift.model.field.Field71F;
import com.prowidesoftware.swift.model.mt.mt1xx.MT103;
import com.prowidesoftware.swift.model.mx.MxPacs00800108;
import com.prowidesoftware.swift.model.mx.dic.Charges7;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction39;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * pacs.008 ↔ MT103, following the CBPR+ translation rules in simplified form.
 *
 * <p>MT103 is older and narrower, so translating MX → MT loses information and the report says
 * exactly where:
 * <ul>
 *   <li><b>Field lengths.</b> Names and addresses get 4 lines of 35 characters (one of them taken by the
 *       account); MX allows 140 characters for a name alone. The sender's reference :20: is 16 characters.</li>
 *   <li><b>Character set.</b> MT allows only the SWIFT "X" set: {@code a-z A-Z 0-9 / - ? : ( ) . , ' +}
 *       and space. Accents are stripped, "&amp;" becomes "AND", and anything else becomes ".".</li>
 *   <li><b>No field at all.</b> The end-to-end id, the group-level MsgId and structured data have nowhere to go.</li>
 * </ul>
 * Translating MT → MX cannot bring back what MT never had: the end-to-end id becomes NOTPROVIDED,
 * and name and address lines can no longer be told apart.
 */
public final class Mt103Translator {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final int LINE = 35;

    /** An MT103 → pacs.008 translation: the transfer to render, and the report. */
    public record FromMt(CreditTransfer transfer, List<FieldMapping> fields, List<String> errors) {
    }

    /** An MX → MT translation: the FIN text and the report. */
    public record ToMt(String fin, List<FieldMapping> fields) {
    }

    private Mt103Translator() {
    }

    // =================================================================== pacs.008 → MT103

    public static ToMt toMt103(MxPacs00800108 mx) {
        CreditTransferTransaction39 tx = mx.getFIToFICstmrCdtTrf().getCdtTrfTxInf().get(0);
        String msgId = mx.getFIToFICstmrCdtTrf().getGrpHdr().getMsgId();
        String sender = mx.getAppHdr() != null ? mx.getAppHdr().from() : Mx.bic(tx.getInstgAgt());
        String receiver = Mx.bic(tx.getInstdAgt()) != null ? Mx.bic(tx.getInstdAgt()) : mx.getAppHdr().to();
        List<FieldMapping> rows = new ArrayList<>();
        List<Field> fields = new ArrayList<>();

        MT103 mt = new MT103(sender, receiver);
        rows.add(row("{1:}", "F01" + lt(sender), "AppHdr/Fr/FIId/FinInstnId/BICFI", sender, Fidelity.EQUIVALENT,
                "Sender's logical terminal: BIC8 + terminal code + branch"));
        rows.add(row("{2:}", "I103" + lt(receiver) + "N", "InstdAgt/FinInstnId/BICFI", receiver, Fidelity.EQUIVALENT,
                "Receiver; N = normal priority"));
        String uetr = tx.getPmtId().getUETR();
        mt.getSwiftMessage().setUETR(uetr);
        rows.add(row("{121:}", uetr, "PmtId/UETR", uetr, Fidelity.EQUIVALENT, "Block 3, mandatory for gpi and CBPR+"));
        rows.add(row("—", "", "GrpHdr/MsgId", msgId, Fidelity.LOST, "MT has no group header; the FIN session numbers replace it"));

        // :20: Sender's reference (16x)
        String instrId = tx.getPmtId().getInstrId() != null ? tx.getPmtId().getInstrId() : msgId;
        Text ref = text(instrId, 1, 16);
        fields.add(new Field20(ref.value));
        rows.add(row(":20:", ref.value, "PmtId/InstrId", instrId, ref.fidelity(), ref.note("16 characters")));
        fields.add(new Field23B("CRED"));
        rows.add(row(":23B:", "CRED", "—", "(a customer credit transfer)", Fidelity.IMPLICIT, "Bank operation code, implied by pacs.008"));

        String e2e = tx.getPmtId().getEndToEndId();
        rows.add(row("—", "", "PmtId/EndToEndId", e2e, "NOTPROVIDED".equals(e2e) ? Fidelity.IMPLICIT : Fidelity.LOST,
                "NOTPROVIDED".equals(e2e) ? "Nothing to carry" : "MT103 has no end-to-end reference: the debtor's reference is lost"));

        // :32A: value date, currency, interbank amount
        Money interbank = Mx.money(tx.getIntrBkSttlmAmt().getValue(), tx.getIntrBkSttlmAmt().getCcy());
        String f32a = YYMMDD.format(tx.getIntrBkSttlmDt()) + interbank.currencyCode() + finAmount(interbank.amount());
        fields.add(new Field32A(f32a));
        rows.add(row(":32A:", f32a, "IntrBkSttlmDt + IntrBkSttlmAmt", tx.getIntrBkSttlmDt() + " · " + interbank,
                Fidelity.TRANSFORMED, "YYMMDD and a decimal comma"));

        if (tx.getInstdAmt() != null) {
            Money instructed = Mx.money(tx.getInstdAmt().getValue(), tx.getInstdAmt().getCcy());
            String f33b = instructed.currencyCode() + finAmount(instructed.amount());
            fields.add(new Field33B(f33b));
            rows.add(row(":33B:", f33b, "InstdAmt", instructed.toString(), Fidelity.EQUIVALENT, null));
        }

        // :50K: ordering customer
        String dAcct = Mx.account(tx.getDbtrAcct());
        Party debtor = party(dAcct, tx.getDbtr().getNm());
        fields.add(new Field50K(debtor.value));
        rows.add(row(":50K:", debtor.value, "DbtrAcct + Dbtr/Nm", join(dAcct, tx.getDbtr().getNm()), debtor.fidelity, debtor.note));

        String dAgt = Mx.bic(tx.getDbtrAgt());
        fields.add(new Field52A(dAgt));
        rows.add(row(":52A:", dAgt, "DbtrAgt/FinInstnId/BICFI", dAgt, Fidelity.EQUIVALENT, null));
        String cAgt = Mx.bic(tx.getCdtrAgt());
        fields.add(new Field57A(cAgt));
        rows.add(row(":57A:", cAgt, "CdtrAgt/FinInstnId/BICFI", cAgt, Fidelity.EQUIVALENT, null));

        // :59: beneficiary customer
        String cAcct = Mx.account(tx.getCdtrAcct());
        Party creditor = party(cAcct, tx.getCdtr().getNm());
        fields.add(new Field59(creditor.value));
        rows.add(row(":59:", creditor.value, "CdtrAcct + Cdtr/Nm", join(cAcct, tx.getCdtr().getNm()), creditor.fidelity, creditor.note));

        // :70: remittance information (4 × 35)
        if (tx.getRmtInf() != null && tx.getRmtInf().getUstrd() != null && !tx.getRmtInf().getUstrd().isEmpty()) {
            String ustrd = String.join(" ", tx.getRmtInf().getUstrd());
            Text rmt = text(ustrd, 4, LINE);
            fields.add(new Field70(rmt.value));
            rows.add(row(":70:", rmt.value, "RmtInf/Ustrd", ustrd, rmt.fidelity(), rmt.note("4 × 35 characters")));
        }

        // :71A: details of charges
        String bearer = tx.getChrgBr().name();
        String f71a = switch (bearer) {
            case "DEBT" -> "OUR";
            case "CRED" -> "BEN";
            default -> "SHA";
        };
        fields.add(new Field71A(f71a));
        rows.add(row(":71A:", f71a, "ChrgBr", bearer, Fidelity.TRANSFORMED, "SHAR ↔ SHA, DEBT ↔ OUR, CRED ↔ BEN"));
        if (tx.getChrgsInf() != null) {
            for (Charges7 c : tx.getChrgsInf()) {
                Money m = Mx.money(c.getAmt().getValue(), c.getAmt().getCcy());
                String f71f = m.currencyCode() + finAmount(m.amount());
                fields.add(new Field71F(f71f));
                rows.add(row(":71F:", f71f, "ChrgsInf/Amt + Agt", m + " · " + Mx.bic(c.getAgt()), Fidelity.TRANSFORMED,
                        "Sender's charges; MT does not say which bank took them"));
            }
        }

        String method = mx.getFIToFICstmrCdtTrf().getGrpHdr().getSttlmInf().getSttlmMtd().name();
        rows.add(row("—", "", "GrpHdr/SttlmInf/SttlmMtd", method, Fidelity.IMPLICIT, switch (method) {
            case "INDA", "INGA" -> "No :53a: — settled on the account between sender and receiver";
            case "COVE" -> "Cover method: :53a:/:54a: name the correspondents, and an MT202 COV moves the money";
            default -> "Settled through a clearing system";
        }));

        mt.append(fields.toArray(new Field[0]));
        return new ToMt(mt.message(), rows);
    }

    // =================================================================== MT103 → pacs.008

    public static FromMt fromMt103(String fin) {
        List<String> errors = new ArrayList<>();
        MT103 mt;
        try {
            mt = MT103.parse(fin);
        } catch (RuntimeException e) {
            return new FromMt(null, List.of(), List.of("Not a readable MT103: " + e.getMessage()));
        }
        if (mt == null || mt.getSwiftMessage() == null || !"103".equals(mt.getSwiftMessage().getType())) {
            return new FromMt(null, List.of(), List.of("Not an MT103"));
        }
        require(errors, mt.getField20(), ":20:");
        require(errors, mt.getField32A(), ":32A:");
        require(errors, mt.getField50K(), ":50K:");
        require(errors, mt.getField59(), ":59:");
        require(errors, mt.getField71A(), ":71A:");
        String uetr = mt.getUETR();
        if (uetr == null) {
            errors.add("Block 3 {121:} UETR is missing (mandatory for CBPR+)");
        }
        if (!errors.isEmpty()) {
            return new FromMt(null, List.of(), errors);
        }
        List<FieldMapping> rows = new ArrayList<>();
        String sender = bic11(mt.getSender());
        String receiver = bic11(mt.getReceiver());
        rows.add(row("{1:}", mt.getSender(), "AppHdr/Fr + InstgAgt", sender, Fidelity.EQUIVALENT, null));
        rows.add(row("{2:}", mt.getReceiver(), "AppHdr/To + InstdAgt", receiver, Fidelity.EQUIVALENT, null));
        rows.add(row("{121:}", uetr, "PmtId/UETR", uetr, Fidelity.EQUIVALENT, null));

        String ref = mt.getField20().getValue();
        rows.add(row(":20:", ref, "PmtId/InstrId", ref, Fidelity.EQUIVALENT, null));
        rows.add(row("—", "", "PmtId/EndToEndId", "NOTPROVIDED", Fidelity.LOST, "MT103 never had one"));

        Field32A f32a = mt.getField32A();
        String ccy = f32a.getCurrency();
        Money interbank = Money.of(f32a.getAmountAsBigDecimal().setScale(scale(ccy)), ccy);
        LocalDate date = LocalDate.parse("20" + f32a.getComponent1(), DateTimeFormatter.BASIC_ISO_DATE);
        rows.add(row(":32A:", f32a.getValue(), "IntrBkSttlmDt + IntrBkSttlmAmt", date + " · " + interbank, Fidelity.TRANSFORMED, null));
        Money instructed = interbank;
        if (mt.getField33B() != null) {
            Field33B f33b = mt.getField33B();
            instructed = Money.of(f33b.getAmountAsBigDecimal().setScale(scale(f33b.getCurrency())), f33b.getCurrency());
            rows.add(row(":33B:", f33b.getValue(), "InstdAmt", instructed.toString(), Fidelity.EQUIVALENT, null));
        }

        String[] debtor = splitParty(mt.getField50K().getValue());
        rows.add(row(":50K:", mt.getField50K().getValue(), "DbtrAcct + Dbtr/Nm", join(debtor[0], debtor[1]), Fidelity.TRANSFORMED,
                "Name and address lines cannot be told apart: all go into Nm"));
        String[] creditor = splitParty(mt.getField59().getValue());
        rows.add(row(":59:", mt.getField59().getValue(), "CdtrAcct + Cdtr/Nm", join(creditor[0], creditor[1]), Fidelity.TRANSFORMED,
                "Name and address lines cannot be told apart: all go into Nm"));
        String debtorAgent = mt.getField52A() != null ? lastLine(mt.getField52A().getValue()) : sender;
        rows.add(row(":52A:", mt.getField52A() != null ? mt.getField52A().getValue() : "", "DbtrAgt", debtorAgent,
                mt.getField52A() != null ? Fidelity.EQUIVALENT : Fidelity.IMPLICIT, mt.getField52A() != null ? null : "Absent: the sender is the debtor's bank"));
        String creditorAgent = mt.getField57A() != null ? lastLine(mt.getField57A().getValue()) : receiver;
        rows.add(row(":57A:", mt.getField57A() != null ? mt.getField57A().getValue() : "", "CdtrAgt", creditorAgent,
                mt.getField57A() != null ? Fidelity.EQUIVALENT : Fidelity.IMPLICIT, mt.getField57A() != null ? null : "Absent: the receiver is the creditor's bank"));

        String remittance = null;
        if (mt.getField70() != null) {
            // MT wraps at 35 characters wherever the line is full, so lines are joined without a separator.
            remittance = mt.getField70().getValue().replace("\r", "").replace("\n", "");
            rows.add(row(":70:", mt.getField70().getValue(), "RmtInf/Ustrd", remittance, Fidelity.EQUIVALENT, null));
        }
        String f71a = mt.getField71A().getValue();
        ChargeBearer bearer = switch (f71a) {
            case "OUR" -> ChargeBearer.DEBT;
            case "BEN" -> ChargeBearer.CRED;
            default -> ChargeBearer.SHAR;
        };
        rows.add(row(":71A:", f71a, "ChrgBr", bearer.name(), Fidelity.TRANSFORMED, null));
        Money charges = Money.zero(ccy);
        for (Field71F f : mt.getField71F()) {
            Money c = Money.of(f.getAmountAsBigDecimal().setScale(scale(f.getCurrency())), f.getCurrency());
            charges = charges.plus(c);
            rows.add(row(":71F:", f.getValue(), "ChrgsInf/Amt", c.toString(), Fidelity.TRANSFORMED, "Attributed to the sender"));
        }
        rows.add(row("—", "", "GrpHdr/SttlmInf/SttlmMtd", "INDA", Fidelity.IMPLICIT,
                "No :53a: means settlement on the account between sender and receiver"));

        CreditTransfer t = new CreditTransfer(uetr, ref, "NOTPROVIDED", sender, receiver, debtorAgent, creditorAgent,
                debtor[1], debtor[0], creditor[1], creditor[0], interbank, instructed, charges, bearer, date,
                SettlementMethod.INDA, remittance);
        return new FromMt(t, rows, List.of());
    }

    // =================================================================== helpers

    /** SWIFT X character set, as a translator applies it. */
    static String toX(String s) {
        String plain = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        plain = plain.replace("&", "AND");
        StringBuilder sb = new StringBuilder();
        for (char c : plain.toCharArray()) {
            sb.append(isX(c) ? c : '.');
        }
        return sb.toString();
    }

    private static boolean isX(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "/-?:().,'+ ".indexOf(c) >= 0;
    }

    /** Word-wraps into lines of at most {@code width}; words longer than a line are split. */
    static List<String> wrap(String s, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.trim().split("\\s+")) {
            while (word.length() > width) {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (line.isEmpty()) {
                line.append(word);
            } else if (line.length() + 1 + word.length() <= width) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private record Text(String value, boolean changed, boolean truncated) {
        Fidelity fidelity() {
            return truncated ? Fidelity.TRUNCATED : changed ? Fidelity.TRANSFORMED : Fidelity.EQUIVALENT;
        }

        String note(String limit) {
            if (truncated) {
                return "Cut to " + limit + (changed ? "; characters outside the SWIFT X set replaced" : "");
            }
            return changed ? "Characters outside the SWIFT X set replaced" : null;
        }
    }

    private static Text text(String original, int maxLines, int width) {
        String x = toX(original);
        boolean changed = !x.equals(original);
        if (maxLines == 1) {
            return new Text(x.length() > width ? x.substring(0, width) : x, changed, x.length() > width);
        }
        List<String> lines = wrap(x, width);
        boolean truncated = lines.size() > maxLines;
        return new Text(String.join("\n", truncated ? lines.subList(0, maxLines) : lines), changed, truncated);
    }

    private record Party(String value, Fidelity fidelity, String note) {
    }

    /** "/account" on the first line, then the name in up to 3 lines of 35 (4 lines in total). */
    private static Party party(String account, String name) {
        Text t = text(name, 3, LINE);
        String value = (account != null ? "/" + account + "\n" : "") + t.value;
        return new Party(value, t.fidelity(), t.note("3 × 35 characters after the account line"));
    }

    private static String[] splitParty(String value) {
        String[] lines = value.replace("\r", "").split("\n");
        String account = lines[0].startsWith("/") ? lines[0].substring(1) : null;
        StringBuilder name = new StringBuilder();
        for (int i = account != null ? 1 : 0; i < lines.length; i++) {
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(lines[i].trim());
        }
        return new String[] {account, name.toString()};
    }

    private static String lastLine(String value) {
        String[] lines = value.replace("\r", "").split("\n");
        return lines[lines.length - 1].trim();
    }

    /** A logical terminal is BIC8 + terminal code + branch: MNPAMYKL + X + XXX (X is the default terminal). */
    private static String lt(String bic11) {
        return bic11.substring(0, 8) + "X" + bic11.substring(8);
    }

    private static String bic11(String ltOrBic) {
        return ltOrBic.length() == 12 ? ltOrBic.substring(0, 8) + ltOrBic.substring(9) : ltOrBic;
    }

    /** MT amounts: no thousands separator, a decimal comma that is always present ("100," for JPY). */
    static String finAmount(BigDecimal amount) {
        String s = amount.toPlainString().replace('.', ',');
        return s.contains(",") ? s : s + ",";
    }

    private static int scale(String ccy) {
        return java.util.Currency.getInstance(ccy).getDefaultFractionDigits();
    }

    private static String join(String account, String name) {
        return account == null ? name : account + " · " + name;
    }

    private static void require(List<String> errors, Object field, String tag) {
        if (field == null) {
            errors.add(tag + " is missing");
        }
    }

    private static FieldMapping row(String tag, String mt, String path, String mx, Fidelity f, String note) {
        return new FieldMapping(tag, mt, path, mx, f, note);
    }
}
