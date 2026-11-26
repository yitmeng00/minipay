package com.minipay.settlement.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The clearing and settlement systems the operator (the simulated central bank) runs (ADR-008): T2, an RTGS;
 * MiniPay Net, a deferred net settlement ACH; MiniPay Instant, an instant scheme on prefunded positions.
 * Participants reach it over the network (A2A messages) and through its screens (U2A: this API).
 */
public interface SettlementOperatorApi {

    /** Does this instance run the systems? (Only the operator does; on a bank every call is refused.) */
    boolean isOperator();

    /** A message from a participant: a payment order (pacs.008), a return (pacs.004) or an instant answer (pacs.002). */
    Views.Receipt receive(String raw);

    List<Views.SystemInfo> systems();

    List<Views.Participant> participants();

    Views.Rtgs rtgs(String participantBic);

    /** The cycles of a business date (null = today), newest first. */
    List<Views.DnsCycle> cycles(LocalDate date);

    Views.DnsCycle cycle(String id);

    Views.InstantPosition instant(String participantBic);

    Views.TransferDetail transfer(UUID id);

    List<TransferView> transfersForUetr(UUID uetr);

    /** RTGS: the payer moves a queued payment one place up its queue. */
    Replayed<TransferView> moveUp(String idempotencyKey, UUID transferId, String participantBic);

    /** RTGS: the payer makes a queued payment urgent (HIGH) or normal (NORM). */
    Replayed<TransferView> changePriority(String idempotencyKey, UUID transferId, String priority, String participantBic);

    /** DNS: close today's open cycle now (an extra settlement cycle, as an ACH may run on demand). */
    Replayed<Views.DnsCycle> closeCycle(String idempotencyKey);
}
