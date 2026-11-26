package com.minipay.settlement.internal.web;

import com.minipay.settlement.api.Replayed;
import com.minipay.settlement.api.SettlementOperatorApi;
import com.minipay.settlement.api.TransferView;
import com.minipay.settlement.api.Views;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
 * The clearing systems' screens (U2A), served by the operator. A participant sees its own accounts, queue and
 * positions; on a bank instance every call answers 409 NOT_OPERATOR.
 */
@RestController
@RequestMapping("/api/settlement")
@Tag(name = "Settlement systems", description = "T2 (RTGS), MiniPay Net (DNS), MiniPay Instant: run by the central bank")
class SettlementController {

    private final SettlementOperatorApi operator;

    SettlementController(SettlementOperatorApi operator) {
        this.operator = operator;
    }

    @GetMapping("/systems")
    List<Views.SystemInfo> systems() {
        return operator.systems();
    }

    @GetMapping("/participants")
    List<Views.Participant> participants() {
        return operator.participants();
    }

    @GetMapping("/rtgs/{bic}")
    @Operation(summary = "A participant's RTGS account, queue and intraday balance")
    Views.Rtgs rtgs(@PathVariable String bic) {
        return operator.rtgs(bic);
    }

    @GetMapping("/dns/cycles")
    @Operation(summary = "The DNS cycles of a business date (default today), newest first")
    List<Views.DnsCycle> cycles(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return operator.cycles(date);
    }

    @GetMapping("/dns/cycles/{id}")
    Views.DnsCycle cycle(@PathVariable String id) {
        return operator.cycle(id);
    }

    @PostMapping("/dns/cycles/close")
    @Operation(summary = "Close and settle today's open cycle now")
    ResponseEntity<Views.DnsCycle> close(@RequestHeader("Idempotency-Key") String key) {
        return respond(operator.closeCycle(key));
    }

    @GetMapping("/instant/{bic}")
    @Operation(summary = "A participant's instant position, today's figures and the live feed")
    Views.InstantPosition instant(@PathVariable String bic) {
        return operator.instant(bic);
    }

    @GetMapping("/transfers/{id}")
    Views.TransferDetail transfer(@PathVariable UUID id) {
        return operator.transfer(id);
    }

    @GetMapping("/transfers")
    List<TransferView> forUetr(@RequestParam UUID uetr) {
        return operator.transfersForUetr(uetr);
    }

    record ParticipantCommand(String participant, String priority) {
    }

    @PostMapping("/rtgs/transfers/{id}/move-up")
    @Operation(summary = "Move a queued payment one place up the payer's queue")
    ResponseEntity<TransferView> moveUp(@RequestHeader("Idempotency-Key") String key, @PathVariable UUID id,
                                        @RequestBody ParticipantCommand body) {
        return respond(operator.moveUp(key, id, require(body.participant(), "participant")));
    }

    @PostMapping("/rtgs/transfers/{id}/priority")
    @Operation(summary = "Make a queued payment urgent (HIGH) or normal (NORM)")
    ResponseEntity<TransferView> priority(@RequestHeader("Idempotency-Key") String key, @PathVariable UUID id,
                                          @RequestBody ParticipantCommand body) {
        return respond(operator.changePriority(key, id, require(body.priority(), "priority"), require(body.participant(), "participant")));
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim().toUpperCase();
    }

    private static <T> ResponseEntity<T> respond(Replayed<T> r) {
        return r.replayed() ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(r.value())
                : ResponseEntity.status(HttpStatus.OK).body(r.value());
    }

}
