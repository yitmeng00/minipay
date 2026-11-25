package com.minipay.messaging.internal.mx;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.common.money.Money;
import com.minipay.messaging.Fixtures;
import com.minipay.messaging.api.MessageType;
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
                List.of(new PaymentReturn.Charge(Money.of("15.00", "EUR"), "MNPBDEFFXXX"),
                        new PaymentReturn.Charge(Money.of("10.00", "EUR"), "MNPCGB2LXXX")),
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
}
