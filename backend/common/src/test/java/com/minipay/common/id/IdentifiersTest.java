package com.minipay.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentifiersTest {

    @RepeatedTest(20)
    void generated_uetr_is_valid_uuid_v4() {
        assertThat(Uetr.isValid(Uetr.generate().value())).isTrue();
    }

    @Test
    void uetr_parse_lower_cases() {
        assertThat(Uetr.parse("EB6305C9-1F7F-49DE-AED0-16487C27B42D").value())
                .isEqualTo("eb6305c9-1f7f-49de-aed0-16487c27b42d");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "eb6305c9-1f7f-19de-aed0-16487c27b42d", // version 1, not 4
            "eb6305c9-1f7f-49de-ced0-16487c27b42d", // wrong variant nibble (c)
            "eb6305c91f7f49deaed016487c27b42d",     // no hyphens
            "EB6305C9-1F7F-49DE-AED0-16487C27B42D"  // upper case not canonical
    })
    void rejects_invalid_uetrs(String bad) {
        assertThatThrownBy(() -> new Uetr(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bic8_is_normalised_to_head_office_bic11() {
        assertThat(Bic.parse("mnpamykl")).isEqualTo(new Bic("MNPAMYKLXXX"));
        assertThat(new Bic("MNPCGB2LXXX").countryCode()).isEqualTo("GB");
        assertThat(new Bic("MNPBDEFF100").bic8()).isEqualTo("MNPBDEFF");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MNPA", "MNPAM1KLXXX", "MNPAMYKLXX", "MNPAMYKLXXXX", "mnpamyklxxx"})
    void rejects_invalid_bic_formats(String bad) {
        assertThatThrownBy(() -> new Bic(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
