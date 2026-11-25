package com.minipay.refdata;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = RefDataTestApplication.class)
@AutoConfigureMockMvc
class RefDataApiIT {

    @Autowired
    MockMvc mvc;

    @Test
    void lookups_default_to_the_business_date_and_accept_any_date() throws Exception {
        mvc.perform(get("/api/refdata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-24"))
                .andExpect(jsonPath("$.dataVersion").value(org.hamcrest.Matchers.startsWith("v2026.11.")));
        mvc.perform(get("/api/refdata/bics/MNPXNL2AXXX").param("date", "2027-01-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/refdata/bics").param("date", "2026-11-24"))
                .andExpect(jsonPath("$[*].bic", hasItem("MNPSSGSGXXX")));
    }

    @Test
    void iban_validation_reports_every_check() throws Exception {
        mvc.perform(get("/api/refdata/iban/GB97MNPC60161331926819"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.checks[4].name").value("Check digits (mod-97)"))
                .andExpect(jsonPath("$.checks[4].outcome").value("FAIL"));
    }

    @Test
    void value_date_and_cut_offs() throws Exception {
        mvc.perform(get("/api/refdata/value-date").param("currency", "eur").param("date", "2026-12-25"))
                .andExpect(jsonPath("$.valueDate").value("2026-12-28"))
                .andExpect(jsonPath("$.reasons.length()").value(3));
        mvc.perform(get("/api/refdata/cutoffs")).andExpect(jsonPath("$[0].schemeName").value("T2 RTGS"));
    }

    @Test
    void publishing_a_rule_requires_a_key_and_is_idempotent() throws Exception {
        String body = """
                {"destination":"TQZXDE2A*","currency":"EUR","method":"SERIAL","nextHopBic":"MNPBDEFFXXX",
                 "settlementAccount":"1101-EUR-MNPB","priority":70,"effectiveFrom":"2026-11-25",
                 "publishedBy":"r.lim","approvedBy":"a.wong","description":"API test"}
                """;
        mvc.perform(post("/api/refdata/routing-rules").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        String key = "api-" + UUID.randomUUID();
        mvc.perform(post("/api/refdata/routing-rules").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveTo").doesNotExist());
        mvc.perform(post("/api/refdata/routing-rules").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        mvc.perform(post("/api/refdata/routing-rules").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"priority\":70", "\"priority\":71")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(get("/api/refdata/routes").param("bic", "TQZXDE2AXXX").param("currency", "EUR").param("date", "2026-11-25"))
                .andExpect(jsonPath("$.route.instructedAgentBic").value("MNPBDEFFXXX"));
    }
}
