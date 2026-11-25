package com.minipay.messaging.internal.web;

import com.minipay.messaging.api.Idempotent;
import com.minipay.messaging.api.MessageDetail;
import com.minipay.messaging.api.MessageType;
import com.minipay.messaging.api.MessageView;
import com.minipay.messaging.api.MessagingApi;
import com.minipay.messaging.api.Translation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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

/**
 * The message store and the translation tools. Payments send and receive messages through the
 * payment hub ({@code POST /api/payments/inbound}); this API is for reading what was exchanged.
 */
@RestController
@RequestMapping("/api/messages")
@Tag(name = "Messages", description = "ISO 20022 / MT messages: raw store, MT103 translation, camt.053 statements")
class MessagingController {

    private final MessagingApi messaging;

    MessagingController(MessagingApi messaging) {
        this.messaging = messaging;
    }

    @GetMapping
    @Operation(summary = "Messages about one payment (by UETR), or the most recent ones of a type")
    List<MessageView> list(@RequestParam(required = false) String uetr, @RequestParam(required = false) String type,
                           @RequestParam(defaultValue = "50") int limit) {
        if (uetr != null) {
            return messaging.forUetr(uetr);
        }
        return messaging.recent(type == null ? null : MessageType.fromIdentifier(type), limit);
    }

    @GetMapping("/{id}")
    @Operation(summary = "A message with its raw text, exactly as sent or received")
    MessageDetail get(@PathVariable UUID id) {
        return messaging.get(id);
    }

    @GetMapping(value = "/{id}/raw", produces = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_PLAIN_VALUE})
    @Operation(summary = "Download the raw message")
    ResponseEntity<String> raw(@PathVariable UUID id) {
        MessageDetail d = messaging.get(id);
        boolean mx = d.message().type() == null || d.message().type().isMx();
        String name = (d.message().msgId() != null ? d.message().msgId() : id.toString()) + (mx ? ".xml" : ".fin");
        return ResponseEntity.ok()
                .contentType(mx ? MediaType.APPLICATION_XML : MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(d.raw());
    }

    @GetMapping("/{id}/mt103")
    @Operation(summary = "The MT103 a pacs.008 translates to, with a field-by-field report of what is kept, changed or lost")
    Translation toMt103(@PathVariable UUID id) {
        return messaging.toMt103(id);
    }

    @PostMapping(value = "/translate/mt103", consumes = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Translate an MT103 (FIN text) to pacs.008; nothing is stored")
    Translation fromMt103(@RequestBody String fin) {
        return messaging.fromMt103(fin);
    }

    record StatementRequest(Long accountId, LocalDate date) {
    }

    @PostMapping("/camt053")
    @Operation(summary = "Issue a camt.053 statement for a customer or vostro account (default: today)")
    ResponseEntity<MessageView> camt053(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                        @RequestBody StatementRequest r) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (r.accountId() == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        Idempotent<MessageView> result = messaging.issueCamt053(key, r.accountId(), r.date());
        return result.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.value())
                : ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/messages/" + result.value().id()))
                        .body(result.value());
    }
}
