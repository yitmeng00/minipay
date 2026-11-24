package com.minipay.ledger.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ledger module's public contract. Other modules (payment hub, settlement, recon) call this
 * interface — they never touch the {@code ledger} schema.
 *
 * <p>All money-moving operations are idempotent: they take a client-supplied idempotency key, and
 * repeating a call with the same key and the same request returns the original result.
 */
public interface LedgerApi {

    AccountView openAccount(OpenAccountCommand command);

    /** @param type null for all types */
    List<AccountView> listAccounts(AccountType type);

    AccountView getAccount(long accountId);

    Optional<AccountView> findAccountByNumber(String accountNumber);

    Idempotent<ReservationView> reserve(ReserveFundsCommand command);

    Idempotent<ReservationView> release(ReleaseReservationCommand command);

    Idempotent<ReservationView> settle(SettleReservationCommand command);

    ReservationView getReservation(UUID reservationId);

    Idempotent<JournalEntryView> post(PostEntryCommand command);

    Idempotent<JournalEntryView> reverse(ReverseEntryCommand command);

    JournalEntryView getEntry(UUID entryId);

    /** Every journal entry booked for a payment hub payment, oldest first (holds, bookings, fees, returns). */
    List<JournalEntryView> entriesForPayment(UUID paymentId);

    StatementView statement(long accountId, LocalDate date);

    IntegrityReport checkIntegrity();
}
