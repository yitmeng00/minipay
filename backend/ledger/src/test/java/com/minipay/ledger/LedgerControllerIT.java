package com.minipay.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.minipay.ledger.api.AccountView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract: Idempotency-Key handling, status codes, Problem Details with ISO reason codes. */
@AutoConfigureMockMvc
class LedgerControllerIT extends LedgerIntegrationTest {

    @Autowired
    MockMvc mvc;

    private String reserveBody(AccountView account, String amount) {
        return """
                {"accountId": %d, "amount": {"amount": "%s", "currency": "EUR"}, "reason": "pacs.008"}
                """.formatted(account.id(), amount);
    }

    @Test
    void write_without_idempotency_key_is_400() throws Exception {
        AccountView c = openCustomer("EUR");
        mvc.perform(post("/api/ledger/reservations").contentType(MediaType.APPLICATION_JSON).content(reserveBody(c, "1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void create_then_replay_then_conflicting_reuse() throws Exception {
        AccountView c = openCustomer("EUR");
        fund(c, openNostro("EUR"), "100.00");
        String key = key();

        mvc.perform(post("/api/ledger/reservations").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(reserveBody(c, "25.00")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // Amounts travel as strings so no client ever parses money into a float.
                .andExpect(jsonPath("$.amount.amount").value("25.00"))
                .andExpect(jsonPath("$.amount.currency").value("EUR"));

        mvc.perform(post("/api/ledger/reservations").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(reserveBody(c, "25")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mvc.perform(post("/api/ledger/reservations").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(reserveBody(c, "26.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mvc.perform(get("/api/ledger/accounts/" + c.id()))
                .andExpect(jsonPath("$.reserved.amount").value("25.00"))
                .andExpect(jsonPath("$.available.amount").value("75.00"));
    }

    @Test
    void insufficient_funds_is_422_with_iso_reason_AM04() throws Exception {
        AccountView c = openCustomer("EUR");
        mvc.perform(post("/api/ledger/reservations").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(reserveBody(c, "1.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("AM04"));
    }

    @Test
    void unknown_account_is_404_with_AC01() throws Exception {
        mvc.perform(get("/api/ledger/accounts/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reasonCode").value("AC01"));
    }

    @Test
    void too_many_decimals_is_400() throws Exception {
        AccountView c = openCustomer("EUR");
        mvc.perform(post("/api/ledger/reservations").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(reserveBody(c, "1.005")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unbalanced_journal_entry_is_422() throws Exception {
        AccountView c = openCustomer("EUR");
        AccountView n = openNostro("EUR");
        mvc.perform(post("/api/ledger/journal-entries").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"entryType": "SETTLE", "postings": [
                                  {"accountId": %d, "direction": "D", "amount": {"amount": "10.00", "currency": "EUR"}},
                                  {"accountId": %d, "direction": "C", "amount": {"amount": "9.00", "currency": "EUR"}}
                                ]}""".formatted(n.id(), c.id())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNBALANCED_ENTRY"));
    }

    @Test
    void integrity_endpoint_reports() throws Exception {
        mvc.perform(get("/api/ledger/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialBalance").isArray());
    }
}
