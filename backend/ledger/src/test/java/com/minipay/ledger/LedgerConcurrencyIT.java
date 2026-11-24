package com.minipay.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountView;
import com.minipay.ledger.api.Direction;
import com.minipay.ledger.api.EntryType;
import com.minipay.ledger.api.Idempotent;
import com.minipay.ledger.api.IntegrityReport;
import com.minipay.ledger.api.LedgerError;
import com.minipay.ledger.api.LedgerException;
import com.minipay.ledger.api.PostEntryCommand;
import com.minipay.ledger.api.PostingLine;
import com.minipay.ledger.api.ReleaseReservationCommand;
import com.minipay.ledger.api.ReservationView;
import com.minipay.ledger.api.ReserveFundsCommand;
import com.minipay.ledger.api.SettleReservationCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Concurrency guarantees. Each test releases all threads at once from a latch, so the requests really
 * do hit PostgreSQL at the same time and fight over the same rows.
 */
class LedgerConcurrencyIT extends LedgerIntegrationTest {

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    /** Runs all tasks simultaneously and returns their outcomes (value or exception). */
    private static <T> List<Object> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void hundred_concurrent_debits_never_overdraw_the_account() throws Exception {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        fund(customer, nostro, "500.00");

        // 100 debits of 10.00 against a balance of 500.00: exactly 50 may succeed.
        List<Callable<Object>> debits = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            debits.add(() -> ledger.post(new PostEntryCommand(key(), EntryType.SETTLE, null, null, "Outgoing",
                    List.of(new PostingLine(customer.id(), Direction.D, eur("10.00")),
                            new PostingLine(nostro.id(), Direction.C, eur("10.00"))))));
        }
        List<Object> results = runTogether(debits);

        long ok = results.stream().filter(r -> r instanceof Idempotent<?>).count();
        long am04 = results.stream().filter(r -> r instanceof LedgerException e
                && e.error() == LedgerError.INSUFFICIENT_FUNDS).count();
        assertThat(ok).isEqualTo(50);
        assertThat(am04).isEqualTo(50);
        assertThat(reload(customer).booked()).isEqualTo(eur("0.00"));
        assertThat(reload(customer).available().isNegative()).isFalse();
    }

    @Test
    void hundred_concurrent_reservations_never_over_reserve() throws Exception {
        AccountView customer = openCustomer("EUR");
        fund(customer, openNostro("EUR"), "333.00");

        List<Callable<Object>> holds = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            holds.add(() -> ledger.reserve(new ReserveFundsCommand(key(), customer.id(), eur("10.00"), null, null)));
        }
        List<Object> results = runTogether(holds);

        assertThat(results.stream().filter(r -> r instanceof Idempotent<?>).count()).isEqualTo(33);
        AccountView after = reload(customer);
        assertThat(after.reserved()).isEqualTo(eur("330.00"));
        assertThat(after.available()).isEqualTo(eur("3.00"));
    }

    @Test
    void opposite_transfers_in_parallel_do_not_deadlock() throws Exception {
        // A→B locks (A,B); B→A would lock (B,A) — the classic deadlock. Locking in id order
        // makes both lock (min,max), so one simply waits for the other.
        AccountView a = openCustomer("EUR");
        AccountView b = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        fund(a, nostro, "10000.00");
        fund(b, nostro, "10000.00");

        List<Callable<Object>> transfers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            AccountView from = i % 2 == 0 ? a : b;
            AccountView to = i % 2 == 0 ? b : a;
            transfers.add(() -> ledger.post(new PostEntryCommand(key(), EntryType.SETTLE, null, null, "Book transfer",
                    List.of(new PostingLine(from.id(), Direction.D, eur("1.00")),
                            new PostingLine(to.id(), Direction.C, eur("1.00"))))));
        }
        List<Object> results = runTogether(transfers);

        // A deadlock would surface as PostgreSQL error 40P01 (DeadlockLoserDataAccessException).
        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(Idempotent.class));
        assertThat(reload(a).booked()).isEqualTo(eur("10000.00"));
        assertThat(reload(b).booked()).isEqualTo(eur("10000.00"));
    }

    @Test
    void the_same_idempotency_key_sent_concurrently_posts_exactly_once() throws Exception {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        fund(customer, nostro, "100.00");
        String key = key();

        List<Callable<Idempotent<ReservationView>>> retries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            retries.add(() -> ledger.reserve(new ReserveFundsCommand(key, customer.id(), eur("40.00"), null, null)));
        }
        List<Object> results = runTogether(retries);

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(Idempotent.class));
        long created = results.stream().filter(r -> !((Idempotent<?>) r).replayed()).count();
        assertThat(created).isEqualTo(1);
        assertThat(results.stream().map(r -> ((ReservationView) ((Idempotent<?>) r).value()).id()).distinct()).hasSize(1);
        assertThat(reload(customer).reserved()).isEqualTo(eur("40.00"));
    }

    @Test
    void racing_release_and_settle_on_one_hold_only_one_wins() throws Exception {
        AccountView customer = openCustomer("EUR");
        AccountView nostro = openNostro("EUR");
        fund(customer, nostro, "100.00");
        ReservationView hold = ledger.reserve(new ReserveFundsCommand(key(), customer.id(), eur("100.00"), null, null)).value();

        List<Object> results = runTogether(List.<Callable<Object>>of(
                () -> ledger.release(new ReleaseReservationCommand(key(), hold.id(), null)),
                () -> ledger.settle(new SettleReservationCommand(key(), hold.id(), nostro.id(), null, null))));

        assertThat(results.stream().filter(r -> r instanceof Idempotent<?>).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r instanceof LedgerException e
                && e.error() == LedgerError.RESERVATION_NOT_ACTIVE).count()).isEqualTo(1);
        assertThat(reload(customer).reserved()).isEqualTo(eur("0.00"));
    }

    @Test
    void end_of_day_integrity_holds_after_a_random_concurrent_workload() throws Exception {
        List<AccountView> customers = new ArrayList<>();
        AccountView nostro = openNostro("EUR");
        AccountView fees = openFeeIncome("EUR");
        for (int i = 0; i < 6; i++) {
            AccountView c = openCustomer("EUR");
            fund(c, nostro, "1000.00");
            customers.add(c);
        }
        Map<Integer, ReservationView> holds = new ConcurrentHashMap<>();
        AtomicInteger seq = new AtomicInteger();

        List<Callable<Object>> work = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            work.add(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                AccountView c = customers.get(rnd.nextInt(customers.size()));
                Money amount = eur(rnd.nextInt(1, 200) + "." + rnd.nextInt(10, 99));
                return switch (rnd.nextInt(5)) {
                    case 0 -> {
                        var r = ledger.reserve(new ReserveFundsCommand(key(), c.id(), amount, null, null)).value();
                        holds.put(seq.incrementAndGet(), r);
                        yield r;
                    }
                    case 1 -> holds.values().stream().findAny()
                            .map(h -> (Object) ledger.release(new ReleaseReservationCommand(key(), h.id(), null)))
                            .orElse("no hold");
                    case 2 -> holds.values().stream().findAny()
                            .map(h -> (Object) ledger.settle(new SettleReservationCommand(key(), h.id(), nostro.id(), null, null)))
                            .orElse("no hold");
                    case 3 -> ledger.post(new PostEntryCommand(key(), EntryType.FEE, null, null, "Fee",
                            List.of(new PostingLine(c.id(), Direction.D, eur("2.50")),
                                    new PostingLine(fees.id(), Direction.C, eur("2.50")))));
                    default -> {
                        AccountView other = customers.get(rnd.nextInt(customers.size()));
                        yield other.id() == c.id() ? "same" : ledger.post(new PostEntryCommand(key(), EntryType.SETTLE,
                                null, null, "Transfer",
                                List.of(new PostingLine(c.id(), Direction.D, amount),
                                        new PostingLine(other.id(), Direction.C, amount))));
                    }
                };
            });
        }
        List<Object> results = runTogether(work);

        // Business refusals (AM04, hold already settled ...) are expected; technical failures are not.
        assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof LedgerException));

        IntegrityReport report = ledger.checkIntegrity();
        assertThat(report.unbalancedEntries()).isEmpty();
        assertThat(report.balanceMismatches()).isEmpty();
        assertThat(report.holdMismatches()).isEmpty();
        assertThat(report.trialBalance()).allSatisfy(l -> assertThat(l.balanced()).isTrue());
        assertThat(report.ok()).isTrue();
        assertThat(customers).allSatisfy(c -> assertThat(reload(c).available().isNegative()).isFalse());
    }
}
