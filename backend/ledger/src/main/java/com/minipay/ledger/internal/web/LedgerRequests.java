package com.minipay.ledger.internal.web;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** HTTP request bodies. The Idempotency-Key travels in a header, not in the body. */
final class LedgerRequests {

    private LedgerRequests() {
    }

    record ReserveRequest(
            @NotNull Long accountId,
            @NotNull Money amount,
            UUID paymentId,
            @Size(max = 140) String reason) {
    }

    record ReleaseRequest(@Size(max = 140) String reason) {
    }

    /** Either {@code creditAccountId} (whole hold to one account) or {@code legs} (split, e.g. principal + fee). */
    record SettleRequest(
            Long creditAccountId,
            @Size(max = 140) String narrative,
            LocalDate valueDate,
            List<@Valid SettlementLegRequest> legs) {
    }

    record SettlementLegRequest(@NotNull Long creditAccountId, Money amount, @NotNull EntryType entryType,
                                @Size(max = 140) String narrative) {
    }

    record PostingLineRequest(@NotNull Long accountId, @NotNull Direction direction, @NotNull Money amount) {
    }

    record PostEntryRequest(
            @NotNull EntryType entryType,
            UUID paymentId,
            LocalDate valueDate,
            @Size(max = 140) String narrative,
            @NotEmpty @Size(min = 2) List<@Valid PostingLineRequest> postings) {
    }

    record ReverseRequest(@Size(max = 140) String narrative) {
    }
}
