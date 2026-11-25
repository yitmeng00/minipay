package com.minipay.paymenthub.internal.web;

import com.minipay.paymenthub.api.CreatePaymentCommand;
import com.minipay.paymenthub.api.DailyOverview;
import com.minipay.paymenthub.api.NetworkEvent;
import com.minipay.paymenthub.api.PageView;
import com.minipay.paymenthub.api.PaymentDetailView;
import com.minipay.paymenthub.api.PaymentDirection;
import com.minipay.paymenthub.api.PaymentHubApi;
import com.minipay.paymenthub.api.PaymentPreview;
import com.minipay.paymenthub.api.PaymentQuery;
import com.minipay.paymenthub.api.PaymentStatus;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.paymenthub.api.Replayable;
import com.minipay.paymenthub.internal.web.PaymentRequests.CreatePaymentRequest;
import com.minipay.paymenthub.internal.web.PaymentRequests.NetworkEventRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments", description = "Payment hub: create, track and (until M5) simulate network outcomes")
class PaymentController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentHubApi hub;

    PaymentController(PaymentHubApi hub) {
        this.hub = hub;
    }

    @PostMapping
    @Operation(summary = "Create an outgoing customer credit transfer",
            description = "Runs validation, screening, funds reservation and sending synchronously. "
                    + "201 with the resulting status (SENT, REJECTED, or FUNDS_RESERVED if warehoused for a future date).")
    ResponseEntity<PaymentView> create(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                       @Valid @RequestBody CreatePaymentRequest body) {
        Replayable<PaymentView> r = hub.create(new CreatePaymentCommand(requireKey(key), body.debtorAccount(),
                body.creditorName(), body.creditorAccount(), body.creditorAgentBic(), body.amount(), body.chargeBearer(),
                body.requestedExecutionDate(), body.endToEndId(), body.remittanceInformation()));
        return respond(r);
    }

    @GetMapping
    @Operation(summary = "Search payments (newest first)")
    PageView<PaymentView> search(@RequestParam(required = false) Set<PaymentStatus> status,
                                 @RequestParam(required = false) PaymentDirection direction,
                                 @RequestParam(required = false) String currency,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate valueDateFrom,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate valueDateTo,
                                 @RequestParam(required = false) BigDecimal minAmount,
                                 @RequestParam(required = false) BigDecimal maxAmount,
                                 @RequestParam(required = false) String q,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        return hub.search(new PaymentQuery(status, direction, currency, valueDateFrom, valueDateTo, minAmount, maxAmount,
                q, page, size));
    }

    @GetMapping("/status-counts")
    Map<PaymentStatus, Long> statusCounts() {
        return hub.countByStatus();
    }

    @GetMapping("/overview")
    @Operation(summary = "Dashboard figures for one business date (default today, bank time zone)")
    DailyOverview overview(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return hub.overview(date);
    }

    @GetMapping("/{uetr}")
    @Operation(summary = "Payment with its full state history")
    PaymentDetailView get(@PathVariable String uetr) {
        return hub.get(uetr);
    }

    @PostMapping(value = "/inbound", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE})
    @Operation(summary = "Receive a pacs.002 (status) or pacs.004 (return) from another bank, as raw XML",
            description = "The message is stored exactly as received, even if it is invalid (422). A resend of the same "
                    + "message (same sender and MsgId) is recognised and changes nothing (200, Idempotent-Replayed).")
    ResponseEntity<PaymentView> inbound(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                        @RequestBody String raw) {
        return respond(hub.receiveMessage(requireKey(key), raw));
    }

    @PostMapping("/preview")
    @Operation(summary = "The pacs.008 this payment would produce if sent now. Nothing is created, held or booked.")
    PaymentPreview preview(@Valid @RequestBody CreatePaymentRequest body) {
        return hub.preview(new CreatePaymentCommand("preview", body.debtorAccount(), body.creditorName(),
                body.creditorAccount(), body.creditorAgentBic(), body.amount(), body.chargeBearer(),
                body.requestedExecutionDate(), body.endToEndId(), body.remittanceInformation()));
    }

    @PostMapping("/{uetr}/network-events")
    @Operation(summary = "SIMULATION (until M5): the next bank answers — it sends us a pacs.002 (ACCEPTED: ACCP, "
            + "SETTLED: ACSC) or a pacs.004 (RETURNED), which is then received like any real message")
    ResponseEntity<PaymentView> networkEvent(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                             @PathVariable String uetr,
                                             @Valid @RequestBody NetworkEventRequest body) {
        return respond(hub.onNetworkEvent(requireKey(key), uetr,
                new NetworkEvent(body.type(), body.reasonCode(), body.returnedAmount(), body.reference())));
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new MissingIdempotencyKey();
        }
        return key;
    }

    private static ResponseEntity<PaymentView> respond(Replayable<PaymentView> r) {
        if (r.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(r.value());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/payments/" + r.value().uetr()))
                .body(r.value());
    }

    static class MissingIdempotencyKey extends RuntimeException {
        MissingIdempotencyKey() {
            super("The Idempotency-Key header is required on every write request");
        }
    }
}
