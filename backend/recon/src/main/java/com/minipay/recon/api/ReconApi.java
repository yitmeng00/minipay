package com.minipay.recon.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Nostro reconciliation (ADR-009). Two roles, as every bank has both:
 * <ul>
 *   <li><b>Servicer</b>: for every account it keeps for another bank (a vostro, or a participant's account at the
 *       central bank), it sends an end-of-day camt.053 to the owner.</li>
 *   <li><b>Owner</b>: it matches each statement against its own ledger's lines on the mirror account (its nostro),
 *       and its people work the breaks.</li>
 * </ul>
 */
public interface ReconApi {

    /** A camt.053 from a servicer, off the network: stored, read, and reconciled at once. */
    ReconViews.Receipt receiveStatement(String raw);

    /** Servicer: send the camt.053 of {@code date} for every account we keep for a bank on the network (once per date). */
    ReconViews.Published publishStatements(String idempotencyKey, LocalDate date);

    List<ReconViews.Nostro> nostros();

    /** One nostro on one statement date (null = the latest). */
    ReconViews.Reconciliation reconciliation(String account, LocalDate date);

    /** Match again (e.g. after our ledger caught up). Idempotent per key. */
    ReconViews.Reconciliation rerun(String idempotencyKey, String account);

    ReconViews.BreakView resolve(String idempotencyKey, String breakId, ReconViews.Resolve command);

    /** Undo a resolution that booked nothing (carry forward, claim raised): the break is open again. */
    ReconViews.BreakView reopen(String idempotencyKey, String breakId, String by);

    /** Match by hand: any ledger lines and statement entries whose amounts add up to the same. */
    ReconViews.Reconciliation manualMatch(String idempotencyKey, ReconViews.ManualMatch command);
}
