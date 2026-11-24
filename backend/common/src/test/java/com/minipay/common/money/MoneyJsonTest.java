package com.minipay.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.common.json.MoneyJacksonModule;
import org.junit.jupiter.api.Test;

class MoneyJsonTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new MoneyJacksonModule())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test
    void writes_amount_as_string() throws Exception {
        assertThat(mapper.writeValueAsString(Money.of("1250", "EUR")))
                .isEqualTo("{\"amount\":\"1250.00\",\"currency\":\"EUR\"}");
    }

    @Test
    void writes_any_big_decimal_as_plain_string() throws Exception {
        assertThat(mapper.writeValueAsString(new java.math.BigDecimal("1E+3"))).isEqualTo("\"1000\"");
    }

    @Test
    void reads_string_or_exact_number() throws Exception {
        assertThat(mapper.readValue("{\"amount\":\"0.30\",\"currency\":\"EUR\"}", Money.class))
                .isEqualTo(Money.of("0.30", "EUR"));
        assertThat(mapper.readValue("{\"amount\":12345678901234.56,\"currency\":\"EUR\"}", Money.class))
                .isEqualTo(Money.of("12345678901234.56", "EUR"));
    }

    @Test
    void rejects_too_many_decimals() {
        assertThatThrownBy(() -> mapper.readValue("{\"amount\":\"1.5\",\"currency\":\"JPY\"}", Money.class))
                .hasMessageContaining("more decimals");
    }
}
