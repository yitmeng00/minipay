package com.minipay.common.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.minipay.common.money.Money;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * JSON form of {@link Money}: {@code {"amount": "1250.00", "currency": "EUR"}}.
 *
 * <p>The amount is written as a <b>string</b>. JSON numbers are parsed as IEEE-754 doubles by
 * JavaScript and many other clients, which silently corrupts large or precise amounts
 * ({@code 9007199254740993} or {@code 0.1 + 0.2}). On input we accept a string or a number, but read
 * the number as an exact decimal — never through a double.
 *
 * <p>For the same reason every other {@link BigDecimal} in an API response (e.g. totals in the
 * integrity report) is written as a plain string such as {@code "2947903.1700"}.
 */
public class MoneyJacksonModule extends SimpleModule {

    public MoneyJacksonModule() {
        super("MoneyJacksonModule");
        addSerializer(Money.class, new JsonSerializer<>() {
            @Override
            public void serialize(Money value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeStartObject();
                gen.writeStringField("amount", value.amount().toPlainString());
                gen.writeStringField("currency", value.currencyCode());
                gen.writeEndObject();
            }
        });
        addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.toPlainString());
            }
        });
        addDeserializer(Money.class, new JsonDeserializer<>() {
            @Override
            public Money deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                JsonNode node = p.readValueAsTree();
                JsonNode amount = node.get("amount");
                JsonNode currency = node.get("currency");
                if (amount == null || currency == null || amount.isNull() || currency.isNull()) {
                    return ctxt.reportInputMismatch(Money.class, "Money needs both 'amount' and 'currency'");
                }
                BigDecimal value = amount.isNumber() ? amount.decimalValue() : new BigDecimal(amount.asText());
                try {
                    return Money.of(value, currency.asText());
                } catch (IllegalArgumentException e) {
                    return ctxt.reportInputMismatch(Money.class, e.getMessage());
                }
            }
        });
    }
}
