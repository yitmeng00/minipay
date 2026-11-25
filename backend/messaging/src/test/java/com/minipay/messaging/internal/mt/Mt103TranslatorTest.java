package com.minipay.messaging.internal.mt;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.messaging.Fixtures;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.FieldMapping;
import com.minipay.messaging.api.Fidelity;
import com.minipay.messaging.internal.mx.Mx;
import com.minipay.messaging.internal.mx.MxReader;
import com.minipay.messaging.internal.mx.Pacs008Writer;
import com.prowidesoftware.swift.model.mt.mt1xx.MT103;
import com.prowidesoftware.swift.model.mx.AbstractMX;
import com.prowidesoftware.swift.model.mx.MxPacs00800108;
import java.util.List;
import org.junit.jupiter.api.Test;

class Mt103TranslatorTest {

    static Mt103Translator.ToMt toMt(CreditTransfer t) {
        String xml = Mx.render(Pacs008Writer.build(t, "MNPA20261124-008-000912", Fixtures.CREATED));
        return Mt103Translator.toMt103((MxPacs00800108) AbstractMX.parse(xml));
    }

    static FieldMapping row(List<FieldMapping> rows, String path) {
        return rows.stream().filter(r -> r.mxPath().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void pacs008_becomes_a_parseable_mt103_with_the_uetr_in_block_3() {
        Mt103Translator.ToMt t = toMt(Fixtures.transfer());
        MT103 mt = MT103.parse(t.fin());
        assertThat(mt.getUETR()).isEqualTo(Fixtures.UETR);
        assertThat(mt.getField20().getValue()).isEqualTo("MNPA261124000912");
        assertThat(mt.getField32A().getValue()).isEqualTo("261124EUR48250,00");
        assertThat(mt.getField71A().getValue()).isEqualTo("SHA");
        assertThat(mt.getField57A().getValue()).isEqualTo("MNPCGB2LXXX");
        assertThat(t.fin()).startsWith("{1:F01MNPAMYKLXXXX").contains("{2:I103MNPBDEFFXXXXN}");
    }

    @Test
    void the_report_says_what_mt_cannot_carry() {
        List<FieldMapping> rows = toMt(Fixtures.transfer()).fields();
        assertThat(row(rows, "PmtId/EndToEndId").fidelity()).isEqualTo(Fidelity.LOST);
        assertThat(row(rows, "GrpHdr/MsgId").fidelity()).isEqualTo(Fidelity.LOST);
        // "&" is not in the SWIFT X character set.
        FieldMapping creditor = row(rows, "CdtrAcct + Cdtr/Nm");
        assertThat(creditor.mtValue()).isEqualTo("/GB96MNPC60161331926819\nHarlow AND Finch Engineering Ltd");
        assertThat(creditor.fidelity()).isEqualTo(Fidelity.TRANSFORMED);
        assertThat(row(rows, "ChrgBr").mtValue()).isEqualTo("SHA");
        assertThat(row(rows, "RmtInf/Ustrd").mtValue()).isEqualTo("INV-HF-2026-1187 CNC spindle\nassemblies");
    }

    @Test
    void a_long_name_is_truncated_to_three_lines_after_the_account() {
        String name = "Société Générale des Équipements Industriels et Mécaniques de Précision du Grand Est, Division des Machines-Outils SA";
        FieldMapping creditor = row(toMt(Fixtures.transfer(ChargeBearer.SHAR, name, null)).fields(), "CdtrAcct + Cdtr/Nm");
        assertThat(creditor.fidelity()).isEqualTo(Fidelity.TRUNCATED);
        String[] lines = creditor.mtValue().split("\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[1]).startsWith("Societe Generale des Equipements"); // accents stripped
        assertThat(lines).allMatch(l -> l.length() <= 35);
    }

    @Test
    void cred_carries_our_charges_in_71f() {
        Mt103Translator.ToMt t = toMt(Fixtures.transfer(ChargeBearer.CRED, "Harlow", null));
        MT103 mt = MT103.parse(t.fin());
        assertThat(mt.getField71A().getValue()).isEqualTo("BEN");
        assertThat(mt.getField71F()).hasSize(1).first().satisfies(f -> assertThat(f.getValue()).isEqualTo("EUR20,00"));
        assertThat(mt.getField32A().getValue()).isEqualTo("261124EUR48230,00"); // interbank = instructed − fee
        assertThat(mt.getField33B().getValue()).isEqualTo("EUR48250,00");
    }

    @Test
    void mt103_back_to_pacs008_keeps_what_mt_carried_and_cannot_recover_the_rest() {
        Mt103Translator.FromMt back = Mt103Translator.fromMt103(toMt(Fixtures.transfer()).fin());
        assertThat(back.errors()).isEmpty();
        CreditTransfer t = back.transfer();
        assertThat(t.uetr()).isEqualTo(Fixtures.UETR);
        assertThat(t.instructionId()).isEqualTo("MNPA261124000912");
        assertThat(t.endToEndId()).isEqualTo("NOTPROVIDED");                 // lost on the way out
        assertThat(t.interbankAmount()).isEqualTo(Money.of("48250.00", "EUR"));
        assertThat(t.creditorAccount()).isEqualTo("GB96MNPC60161331926819");
        assertThat(t.creditorName()).isEqualTo("Harlow AND Finch Engineering Ltd"); // the "&" does not come back
        assertThat(t.remittanceInformation()).isEqualTo("INV-HF-2026-1187 CNC spindleassemblies"); // MT line breaks are not spaces
        assertThat(t.instructingAgentBic()).isEqualTo("MNPAMYKLXXX");
        assertThat(t.instructedAgentBic()).isEqualTo("MNPBDEFFXXX");
        assertThat(t.chargeBearer()).isEqualTo(ChargeBearer.SHAR);
        // ... and what it produces is a valid pacs.008.
        String xml = Mx.render(Pacs008Writer.build(t, "MNPA20261124-008-000913", Fixtures.CREATED));
        assertThat(MxReader.read(xml, "MNPBDEFFXXX").errors()).isEmpty();
    }

    @Test
    void an_mt103_without_uetr_or_mandatory_fields_is_refused() {
        String fin = "{1:F01MNPAMYKLAXXX0000000000}{2:I103MNPBDEFFXXXXN}{4:\n:20:REF1\n:23B:CRED\n-}";
        assertThat(Mt103Translator.fromMt103(fin).errors())
                .contains(":32A: is missing", ":59: is missing", "Block 3 {121:} UETR is missing (mandatory for CBPR+)");
    }

    @Test
    void x_character_set_and_wrapping() {
        assertThat(Mt103Translator.toX("Müller & Söhne GmbH @ Köln")).isEqualTo("Muller AND Sohne GmbH . Koln");
        assertThat(Mt103Translator.wrap("aaaa bbbb cccc", 9)).containsExactly("aaaa bbbb", "cccc");
        assertThat(Mt103Translator.wrap("abcdefghijk", 5)).containsExactly("abcde", "fghij", "k");
        assertThat(Mt103Translator.finAmount(new java.math.BigDecimal("50000"))).isEqualTo("50000,");
    }
}
