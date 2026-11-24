package com.minipay.paymenthub;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.minipay.ledger.api.AccountView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract of /api/payments. */
@AutoConfigureMockMvc
class PaymentApiIT extends PaymentHubIntegrationTest {

    @Autowired
    MockMvc mvc;

    private String body(AccountView debtor, String amount) {
        return """
                {"debtorAccount": "%s", "creditorName": "Harlow & Finch Engineering Ltd",
                 "creditorAccount": "GB96 MNPC 6016 1331 9268 19", "creditorAgentBic": "MNPCGB2LXXX",
                 "amount": {"amount": "%s", "currency": "EUR"}, "chargeBearer": "SHAR",
                 "endToEndId": "INV-HF-2026-1187", "remittanceInformation": "INV-HF-2026-1187"}
                """.formatted(debtor.accountNumber(), amount);
    }

    @Test
    void create_replay_conflict_and_read_back() throws Exception {
        AccountView debtor = customer("EUR", "1000.00");
        String key = key();

        String created = mvc.perform(post("/api/payments").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(debtor, "100.00")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.amount.amount").value("100.00"))
                .andExpect(jsonPath("$.chargesAmount.amount").value("20.00"))
                .andReturn().getResponse().getContentAsString();
        String uetr = JsonPath.read(created, "$.uetr");

        mvc.perform(post("/api/payments").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(debtor, "100")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.uetr").value(uetr));

        mvc.perform(post("/api/payments").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(debtor, "101.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mvc.perform(get("/api/payments/" + uetr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(5))
                .andExpect(jsonPath("$.history[0].to").value("RECEIVED"))
                .andExpect(jsonPath("$.history[4].to").value("SENT"));

        mvc.perform(get("/api/payments").param("status", "SENT").param("q", uetr))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void malformed_requests_are_400_and_create_nothing() throws Exception {
        AccountView debtor = customer("EUR", "10.00");
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body(debtor, "1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/api/payments").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body(debtor, "1.001")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/payments").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"debtorAccount\": \"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void network_events_follow_the_state_machine() throws Exception {
        AccountView debtor = customer("EUR", "1000.00");
        String created = mvc.perform(post("/api/payments").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body(debtor, "100.00")))
                .andReturn().getResponse().getContentAsString();
        String uetr = JsonPath.read(created, "$.uetr");

        mvc.perform(post("/api/payments/" + uetr + "/network-events").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\": \"SETTLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
        mvc.perform(post("/api/payments/" + uetr + "/network-events").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\": \"ACCEPTED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACKED"));
    }

    @Test
    void unknown_payment_is_404() throws Exception {
        mvc.perform(get("/api/payments/eb6305c9-1f7f-49de-aed0-16487c27b42d"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }
}
