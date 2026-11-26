package com.minipay.settlement.api;

import com.minipay.common.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** What the operator shows its participants (the console's Settlement screen, like T2's GUI). */
public final class Views {

    private Views() {
    }

    public record TransferEvent(Instant at, TransferStatus from, TransferStatus to, String detail, String actor) {
    }

    public record TransferDetail(TransferView transfer, List<TransferEvent> events) {
    }

    /**
     * @param simulated played by the operator (a bank MiniPay does not run)
     */
    public record Participant(String bic, String name, boolean simulated, String rtgsAccount, String instantAccount,
                              Money rtgsBalance, Money instantAvailable, Money instantReserved) {
    }

    public record BalancePoint(Instant at, Money balance) {
    }

    /**
     * One participant's view of the RTGS.
     *
     * @param available  its account's balance now: what queued payments can use
     * @param queuedOut  its payments waiting for liquidity (or their date)
     * @param queuedIn   payments to it waiting in other participants' queues: liquidity it may get
     * @param projected  available − queued out + queued in: where it ends if everything settles
     * @param intraday   the balance after each booking today
     */
    public record Rtgs(String system, String systemName, String participantBic, String participantName,
                       LocalDate businessDate, String timeZone, LocalTime cutoff, Instant cutoffAt,
                       Money available, Money queuedOut, int queuedOutCount, Money queuedIn, int queuedInCount,
                       Money projected, List<BalancePoint> intraday, List<TransferView> queue, List<TransferView> recent) {
    }

    public record Position(String bic, String name, Money pays, Money receives, Money net) {
    }

    /** Row = payer, cells in the order of {@link DnsCycle#participants()}. */
    public record MatrixRow(String payerBic, List<Money> cells) {
    }

    /**
     * A DNS cycle: while OPEN, the positions so far; once SETTLED, what settled.
     *
     * @param grossTotal       liquidity needed to settle every payment on its own
     * @param bilateralTotal   ... to settle each pair's difference
     * @param multilateralTotal ... to settle the net positions: what the cycle actually moves
     * @param unwound          participants whose payments were taken out because they could not pay
     */
    public record DnsCycle(String id, String system, String systemName, LocalDate businessDate, int seq, String status,
                           Instant scheduledClose, Instant settledAt, int payments, int rejected, List<String> participants,
                           List<MatrixRow> gross, List<Position> positions, Money grossTotal, Money bilateralTotal,
                           Money multilateralTotal, List<String> unwound) {
    }

    /**
     * One participant's view of the instant scheme.
     *
     * @param available its prefunded position that payments can still use (balance − reserved)
     * @param reserved  held for payments waiting for an answer
     */
    public record InstantPosition(String system, String systemName, int timeoutSeconds, String participantBic, Money available,
                          Money reserved, int today, int accepted, int rejected, int timedOut, Long medianMillis,
                          Long p99Millis, List<TransferView> feed) {
    }

    public record SystemInfo(String code, String name, String kind, String currency, String operatorBic,
                             String timeZone, LocalTime cutoff, List<LocalTime> cycleTimes, Integer timeoutSeconds) {
    }

    /** What happened to a message the operator received. */
    public record Receipt(UUID transferId, TransferStatus status, String detail, boolean replayed) {
    }
}
