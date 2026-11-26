package com.minipay.messaging.internal.mx;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.common.money.Money;
import com.minipay.messaging.Fixtures;
import com.minipay.messaging.api.Charge;
import com.minipay.messaging.api.CoverTransfer;
import com.minipay.messaging.api.CreditNotification;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.SettlementMethod;
import com.prowidesoftware.swift.model.mx.AbstractMX;
import com.prowidesoftware.swift.model.mx.MxCamt05400108;
import com.prowidesoftware.swift.model.mx.MxPacs00800108;
import com.prowidesoftware.swift.model.mx.MxPacs00900108;
import com.minipay.messaging.api.PaymentReturn;
import com.minipay.messaging.api.StatusReport;
import com.minipay.messaging.api.TransactionStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MxRoundTripTest {

    static String pacs008() {
        return Mx.render(Pacs008Writer.build(Fixtures.transfer(), "MNPA20261124-008-000912", Fixtures.CREATED));
    }

    @Test
    void a_pacs008_is_a_business_message_and_reads_back_without_errors() {
        String raw = pacs008();
        assertThat(raw).contains("nvlp.001.001.02").contains("head.001.001.02").contains("pacs.008.001.08")
                .contains("<head:BizSvc>swift.cbprplus.02</head:BizSvc>");
        MxReader.Parsed p = MxReader.read(raw, "MNPBDEFFXXX");
        assertThat(p.errors()).isEmpty();
        assertThat(p.type()).isEqualTo(MessageType.PACS_008);
        assertThat(p.msgId()).isEqualTo("MNPA20261124-008-000912");
        assertThat(p.from()).isEqualTo("MNPAMYKLXXX");
        assertThat(p.to()).isEqualTo("MNPBDEFFXXX");
        assertThat(p.uetr()).isEqualTo(Fixtures.UETR);
        assertThat(p.amount()).isEqualTo(Money.of("48250.00", "EUR"));
        assertThat(p.settlementDate()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(p.account()).isEqualTo("GB96MNPC60161331926819");
    }

    @Test
    void the_iban_goes_in_iban_and_a_domestic_account_in_othr() {
        String raw = pacs008();
        assertThat(raw).containsPattern("<(\\w+:)?IBAN>GB96MNPC60161331926819</");
        assertThat(raw).containsPattern("<(\\w+:)?Othr>\\s*<(\\w+:)?Id>514087234412</");
    }

    @Test
    void a_message_for_another_bank_is_refused() {
        assertThat(MxReader.read(pacs008(), "MNPCGB2LXXX").errors())
                .containsExactly("Addressed to MNPBDEFFXXX, not to us (MNPCGB2LXXX)");
    }

    @Test
    void every_broken_rule_is_reported_at_once() {
        String broken = pacs008()
                .replace(Fixtures.UETR, "EB6305C9-1F7F-49DE-AED0-16487C27B42D")      // upper case UETR
                .replace("48250.00</", "48250.001</")                                  // 3 decimals in EUR
                .replace("<head:BizMsgIdr>MNPA20261124-008-000912", "<head:BizMsgIdr>OTHER");
        List<String> errors = MxReader.read(broken, null).errors();
        assertThat(errors).anyMatch(e -> e.startsWith("PmtId/UETR must be a lower-case UUIDv4"));
        assertThat(errors).anyMatch(e -> e.contains("more decimals than EUR allows"));
        assertThat(errors).anyMatch(e -> e.startsWith("AppHdr/BizMsgIdr OTHER differs"));
    }

    @Test
    void garbage_is_not_a_message() {
        assertThat(MxReader.read("<hello/>", null).errors()).hasSize(1);
        assertThat(MxReader.read("{1:F01MNPAMYKLAXXX}", null).type()).isNull();
    }

    @Test
    void pacs002_reports_a_status_against_the_original() {
        String raw = Mx.render(Pacs002Writer.build(new StatusReport("MNPBDEFFXXX", "MNPAMYKLXXX", "MNPA20261124-008-000912",
                "MNPA261124000912", "INV-HF-2026-1187", Fixtures.UETR, TransactionStatus.ACCP, null, null),
                "MNPB20261124-002-118842", Fixtures.CREATED));
        MxReader.Parsed p = MxReader.read(raw, "MNPAMYKLXXX");
        assertThat(p.errors()).isEmpty();
        assertThat(p.type()).isEqualTo(MessageType.PACS_002);
        assertThat(p.status()).isEqualTo("ACCP");
        assertThat(p.originalMsgId()).isEqualTo("MNPA20261124-008-000912");
        assertThat(p.uetr()).isEqualTo(Fixtures.UETR);
    }

    @Test
    void a_rejection_needs_a_reason() {
        String raw = Mx.render(Pacs002Writer.build(new StatusReport("MNPBDEFFXXX", "MNPAMYKLXXX", "M1", "I1", "E1",
                Fixtures.UETR, TransactionStatus.RJCT, null, null), "MNPB20261124-002-000001", Fixtures.CREATED));
        assertThat(MxReader.read(raw, "MNPAMYKLXXX").errors()).anyMatch(e -> e.startsWith("StsRsnInf/Rsn/Cd"));
    }

    @Test
    void pacs004_returns_less_than_was_sent_and_says_who_kept_the_difference() {
        String raw = Mx.render(Pacs004Writer.build(new PaymentReturn("MNPBDEFFXXX", "MNPAMYKLXXX", "MNPC-RTN-0071",
                "MNPA20261124-008-000912", "MNPA261124000912", "INV-HF-2026-1187", Fixtures.UETR,
                Money.of("48250.00", "EUR"), Money.of("48225.00", "EUR"), LocalDate.of(2026, 11, 24),
                List.of(new Charge(Money.of("15.00", "EUR"), "MNPBDEFFXXX"),
                        new Charge(Money.of("10.00", "EUR"), "MNPCGB2LXXX")),
                "AC04", "Creditor account closed 2026-10-31"), "MNPB20261124-004-003317", Fixtures.CREATED));
        MxReader.Parsed p = MxReader.read(raw, "MNPAMYKLXXX");
        assertThat(p.errors()).isEmpty();
        assertThat(p.status()).isEqualTo("RTND");
        assertThat(p.amount()).isEqualTo(Money.of("48225.00", "EUR"));
        assertThat(p.reasonCode()).isEqualTo("AC04");
        assertThat(p.additionalInfo()).isEqualTo("Creditor account closed 2026-10-31");
        assertThat(raw).contains("<pacs:ChrgBr>CRED</pacs:ChrgBr>").contains("<pacs:RtrId>MNPC-RTN-0071</pacs:RtrId>");
    }

    @Test
    void a_return_cannot_be_more_than_the_original() {
        String raw = Mx.render(Pacs004Writer.build(new PaymentReturn("MNPBDEFFXXX", "MNPAMYKLXXX", "R1", "M1", "I1", "E1",
                Fixtures.UETR, Money.of("100.00", "EUR"), Money.of("100.01", "EUR"), LocalDate.of(2026, 11, 24),
                List.of(), "AC04", null), "MNPB20261124-004-000002", Fixtures.CREATED));
        assertThat(MxReader.read(raw, "MNPAMYKLXXX").errors()).containsExactly("Returned amount EUR 100.01 exceeds the original EUR 100.00");
    }

    @Test
    void a_forwarded_pacs008_carries_the_charge_chain_and_the_previous_agent() {
        CreditTransfer f = Fixtures.transfer();
        // Bank B forwards to Bank C: B is now the instructing agent, A a previous one, and B took EUR 15.
        CreditTransfer fwd = new CreditTransfer(f.uetr(), "MNPB261124000001", f.endToEndId(), "MNPBDEFFXXX", "MNPCGB2LXXX",
                f.debtorAgentBic(), f.creditorAgentBic(), f.debtorName(), f.debtorAccount(), f.creditorName(),
                f.creditorAccount(), Money.of("48235.00", "EUR"), f.instructedAmount(),
                List.of(new Charge(Money.of("15.00", "EUR"), "MNPBDEFFXXX")), f.chargeBearer(), f.settlementDate(),
                SettlementMethod.INGA, null, List.of("MNPAMYKLXXX"), f.remittanceInformation());
        String raw = Mx.render(Pacs008Writer.build(fwd, "MNPB20261124-008-000001", Fixtures.CREATED));
        assertThat(MxReader.read(raw, "MNPCGB2LXXX").errors()).isEmpty();
        CreditTransfer back = TransferReader.pacs008((MxPacs00800108) AbstractMX.parse(raw));
        assertThat(back).isEqualTo(fwd);
    }

    @Test
    void a_cover_pacs008_names_its_reimbursement_agent() {
        CreditTransfer f = Fixtures.transfer();
        CreditTransfer cover = new CreditTransfer(f.uetr(), f.instructionId(), f.endToEndId(), "MNPAMYKLXXX", "MNPCGB2LXXX",
                f.debtorAgentBic(), f.creditorAgentBic(), f.debtorName(), f.debtorAccount(), f.creditorName(),
                f.creditorAccount(), f.interbankAmount(), f.instructedAmount(), List.of(), f.chargeBearer(),
                f.settlementDate(), SettlementMethod.COVE, "MNPBDEFFXXX", List.of(), null);
        String raw = Mx.render(Pacs008Writer.build(cover, "MNPA20261124-008-000002", Fixtures.CREATED));
        assertThat(raw).contains("<pacs:SttlmMtd>COVE</pacs:SttlmMtd>").containsPattern("<pacs:InstgRmbrsmntAgt>\\s*<pacs:FinInstnId>\\s*<pacs:BICFI>MNPBDEFFXXX");
        assertThat(TransferReader.pacs008((MxPacs00800108) AbstractMX.parse(raw)).coverAgentBic()).isEqualTo("MNPBDEFFXXX");
    }

    @Test
    void pacs009_cov_carries_the_underlying_customer_payment() {
        CoverTransfer c = new CoverTransfer(Fixtures.UETR, "MNPA261124000912", "INV-HF-2026-1187", "MNPAMYKLXXX",
                "MNPBDEFFXXX", "MNPAMYKLXXX", "MNPCGB2LXXX", Money.of("48250.00", "EUR"), LocalDate.of(2026, 11, 24),
                Fixtures.transfer());
        String raw = Mx.render(Pacs009Writer.build(c, "MNPA20261124-009-000003", Fixtures.CREATED));
        MxReader.Parsed p = MxReader.read(raw, "MNPBDEFFXXX");
        assertThat(p.errors()).isEmpty();
        assertThat(p.type()).isEqualTo(MessageType.PACS_009);
        CoverTransfer back = TransferReader.pacs009((MxPacs00900108) AbstractMX.parse(raw));
        assertThat(back.creditorBic()).isEqualTo("MNPCGB2LXXX");
        assertThat(back.underlying().creditorName()).isEqualTo("Harlow & Finch Engineering Ltd");
        assertThat(back.underlying().creditorAccount()).isEqualTo("GB96MNPC60161331926819");
    }

    @Test
    void camt054_notifies_a_credit_with_the_uetr_to_match_on() {
        CreditNotification n = new CreditNotification("MNPBDEFFXXX", "MNPCGB2LXXX", "DE58500100600099887766",
                Money.of("48250.00", "EUR"), LocalDate.of(2026, 11, 24), Fixtures.CREATED.toInstant(), Fixtures.UETR,
                "INV-HF-2026-1187", "MNPA261124000912", "REF1");
        String raw = Mx.render(Camt054Writer.build(n, "MNPB20261124-054-000004", Fixtures.CREATED, java.time.ZoneId.of("Europe/Berlin")));
        MxReader.Parsed p = MxReader.read(raw, "MNPCGB2LXXX");
        assertThat(p.errors()).isEmpty();
        assertThat(p.uetr()).isEqualTo(Fixtures.UETR);
        assertThat(p.status()).isEqualTo("CRDT");
        assertThat(TransferReader.camt054((MxCamt05400108) AbstractMX.parse(raw))).isEqualTo(n);
    }

    @Test
    void through_a_clearing_system_the_apphdr_goes_to_the_operator_and_the_system_is_named() {
        CreditTransfer f = Fixtures.transfer();
        CreditTransfer urgent = new CreditTransfer(f.uetr(), f.instructionId(), f.endToEndId(), "MNPCGB2LXXX", "MNPBDEFFXXX",
                f.debtorAgentBic(), f.creditorAgentBic(), f.debtorName(), f.debtorAccount(), f.creditorName(),
                f.creditorAccount(), f.interbankAmount(), f.instructedAmount(), List.of(), f.chargeBearer(),
                f.settlementDate(), SettlementMethod.CLRG, null, List.of(), null,
                new com.minipay.messaging.api.Clearing("MPINST", "HIGH", true, "MNCBDEFFXXX"));
        String raw = Mx.render(Pacs008Writer.build(urgent, "MNPC20261124-008-000003", Fixtures.CREATED));
        MxReader.Parsed p = MxReader.read(raw, "MNCBDEFFXXX");
        assertThat(p.errors()).isEmpty();
        assertThat(p.to()).isEqualTo("MNCBDEFFXXX");
        assertThat(raw).contains("<pacs:Prtry>MPINST</pacs:Prtry>").contains("<pacs:InstrPrty>HIGH</pacs:InstrPrty>")
                .containsPattern("<pacs:LclInstrm>\\s*<pacs:Cd>INST</pacs:Cd>");
        CreditTransfer back = TransferReader.pacs008((MxPacs00800108) AbstractMX.parse(raw));
        assertThat(back.instructedAgentBic()).isEqualTo("MNPBDEFFXXX");
        assertThat(back.clearing()).isEqualTo(new com.minipay.messaging.api.Clearing("MPINST", "HIGH", true, null));
    }

    @Test
    void a_relayed_message_keeps_the_document_byte_for_byte_and_only_the_header_changes() {
        CreditTransfer f = Fixtures.transfer();
        CreditTransfer viaT2 = new CreditTransfer(f.uetr(), f.instructionId(), f.endToEndId(), "MNPCGB2LXXX", "MNPBDEFFXXX",
                f.debtorAgentBic(), f.creditorAgentBic(), f.debtorName(), f.debtorAccount(), f.creditorName(),
                f.creditorAccount(), f.interbankAmount(), f.instructedAmount(), List.of(), f.chargeBearer(),
                f.settlementDate(), SettlementMethod.CLRG, null, List.of(), null,
                new com.minipay.messaging.api.Clearing("T2", "NORM", false, "MNCBDEFFXXX"));
        String raw = Mx.render(Pacs008Writer.build(viaT2, "MNPC20261124-008-000004", Fixtures.CREATED));
        String relayed = Mx.readdressed(raw, "MNCBDEFFXXX", "MNPBDEFFXXX", com.minipay.messaging.api.MessageType.PACS_008,
                Fixtures.CREATED.plusSeconds(2));
        MxReader.Parsed p = MxReader.read(relayed, "MNPBDEFFXXX");
        assertThat(p.errors()).isEmpty();
        assertThat(p.from()).isEqualTo("MNCBDEFFXXX");
        assertThat(p.msgId()).isEqualTo("MNPC20261124-008-000004");
        assertThat(document(relayed)).isEqualTo(document(raw));
        assertThat(TransferReader.pacs008((MxPacs00800108) AbstractMX.parse(relayed)).instructingAgentBic()).isEqualTo("MNPCGB2LXXX");
    }

    private static String document(String raw) {
        return raw.substring(raw.indexOf("<Document") >= 0 ? raw.indexOf("<Document") : raw.indexOf(":Document"));
    }
}
