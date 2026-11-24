package com.minipay.ledger.internal.web;

import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Idempotent;
import com.minipay.ledger.api.IntegrityReport;
import com.minipay.ledger.api.JournalEntryView;
import com.minipay.ledger.api.LedgerApi;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.ledger.api.ReleaseReservationCommand;
import com.minipay.ledger.api.ReservationView;
import com.minipay.ledger.api.ReserveFundsCommand;
import com.minipay.ledger.api.ReverseEntryCommand;
import com.minipay.ledger.api.SettleReservationCommand;
import com.minipay.ledger.api.SettlementLeg;
import com.minipay.ledger.api.StatementView;
import com.minipay.ledger.internal.web.LedgerRequests.PostEntryRequest;
import com.minipay.ledger.internal.web.LedgerRequests.ReleaseRequest;
import com.minipay.ledger.internal.web.LedgerRequests.ReserveRequest;
import com.minipay.ledger.internal.web.LedgerRequests.ReverseRequest;
import com.minipay.ledger.internal.web.LedgerRequests.SettleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
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
 * REST API of the ledger.
 *
 * <p>Writes return 201 Created. A retried request (same Idempotency-Key, same body) returns the same
 * resource with 200 OK and the header {@code Idempotent-Replayed: true}; nothing is posted twice.
 */
@RestController
@RequestMapping("/api/ledger")
@Tag(name = "Ledger", description = "Double-entry ledger: accounts, holds, postings, statements")
class LedgerController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final LedgerApi ledger;

    LedgerController(LedgerApi ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/accounts")
    @Operation(summary = "List accounts with booked, reserved and available balances")
    List<AccountView> accounts(@RequestParam(required = false) AccountType type) {
        return ledger.listAccounts(type);
    }

    @GetMapping("/accounts/{id}")
    AccountView account(@PathVariable long id) {
        return ledger.getAccount(id);
    }

    @GetMapping("/accounts/{id}/statement")
    @Operation(summary = "Booked postings for one business date (bank time zone), with running balance and active holds")
    StatementView statement(@PathVariable long id,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ledger.statement(id, date);
    }

    @PostMapping("/reservations")
    @Operation(summary = "Place a hold on funds (payment state FUNDS_RESERVED)")
    ResponseEntity<ReservationView> reserve(@Parameter(required = true) @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                            @Valid @RequestBody ReserveRequest body) {
        Idempotent<ReservationView> r = ledger.reserve(new ReserveFundsCommand(
                requireKey(key), body.accountId(), body.amount(), body.paymentId(), body.reason()));
        return respond(r, URI.create("/api/ledger/reservations/" + r.value().id()));
    }

    @GetMapping("/reservations/{id}")
    ReservationView reservation(@PathVariable UUID id) {
        return ledger.getReservation(id);
    }

    @PostMapping("/reservations/{id}/release")
    @Operation(summary = "Release a hold without moving money (payment rejected or cancelled)")
    ResponseEntity<ReservationView> release(@Parameter(required = true) @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                            @PathVariable UUID id,
                                            @Valid @RequestBody(required = false) ReleaseRequest body) {
        Idempotent<ReservationView> r = ledger.release(new ReleaseReservationCommand(
                requireKey(key), id, body == null ? null : body.reason()));
        return respond(r, URI.create("/api/ledger/reservations/" + id));
    }

    @PostMapping("/reservations/{id}/settle")
    @Operation(summary = "Release a hold and book the payment to the credit account, atomically")
    ResponseEntity<ReservationView> settle(@Parameter(required = true) @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                           @PathVariable UUID id,
                                           @Valid @RequestBody SettleRequest body) {
        SettleReservationCommand command;
        if (body.legs() != null && !body.legs().isEmpty()) {
            command = new SettleReservationCommand(requireKey(key), id, body.legs().stream()
                    .map(l -> new SettlementLeg(l.creditAccountId(), l.amount(), l.entryType(), l.narrative())).toList(),
                    body.valueDate());
        } else if (body.creditAccountId() != null) {
            command = new SettleReservationCommand(requireKey(key), id, body.creditAccountId(), body.narrative(), body.valueDate());
        } else {
            throw new IllegalArgumentException("Give either creditAccountId or legs");
        }
        Idempotent<ReservationView> r = ledger.settle(command);
        return respond(r, URI.create("/api/ledger/reservations/" + id));
    }

    @PostMapping("/journal-entries")
    @Operation(summary = "Post a balanced SETTLE or FEE entry")
    ResponseEntity<JournalEntryView> post(@Parameter(required = true) @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                          @Valid @RequestBody PostEntryRequest body) {
        Idempotent<JournalEntryView> r = ledger.post(new PostEntryCommand(
                requireKey(key), body.entryType(), body.paymentId(), body.valueDate(), body.narrative(),
                body.postings().stream().map(p -> new PostingLine(p.accountId(), p.direction(), p.amount())).toList()));
        return respond(r, URI.create("/api/ledger/journal-entries/" + r.value().id()));
    }

    @GetMapping("/journal-entries")
    @Operation(summary = "Journal entries booked for one payment (holds, bookings, fees, returns)")
    List<JournalEntryView> entriesForPayment(@RequestParam UUID paymentId) {
        return ledger.entriesForPayment(paymentId);
    }

    @GetMapping("/journal-entries/{id}")
    JournalEntryView entry(@PathVariable UUID id) {
        return ledger.getEntry(id);
    }

    @PostMapping("/journal-entries/{id}/reversal")
    @Operation(summary = "Reverse a SETTLE or FEE entry with an equal and opposite entry")
    ResponseEntity<JournalEntryView> reverse(@Parameter(required = true) @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                             @PathVariable UUID id,
                                             @Valid @RequestBody(required = false) ReverseRequest body) {
        Idempotent<JournalEntryView> r = ledger.reverse(new ReverseEntryCommand(
                requireKey(key), id, body == null ? null : body.narrative()));
        return respond(r, URI.create("/api/ledger/journal-entries/" + r.value().id()));
    }

    @GetMapping("/integrity")
    @Operation(summary = "End-of-day integrity check: balanced entries, balances = postings, trial balance, holds")
    IntegrityReport integrity() {
        return ledger.checkIntegrity();
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }
        return key;
    }

    private static <T> ResponseEntity<T> respond(Idempotent<T> result, URI location) {
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(result.value());
        }
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(result.value());
    }
}
