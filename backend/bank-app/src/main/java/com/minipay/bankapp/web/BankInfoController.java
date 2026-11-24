package com.minipay.bankapp.web;

import com.minipay.bankapp.BankProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who am I? Used by the ops console header (bank switcher, local time, business date). */
@RestController
class BankInfoController {

    record BankInfo(String name, String bic, String city, String role, String timeZone,
                    Instant serverTime, LocalDate businessDate) {
    }

    private final BankProperties bank;
    private final Clock clock;

    BankInfoController(BankProperties bank, ObjectProvider<Clock> clock) {
        this.bank = bank;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @GetMapping("/api/bank")
    BankInfo info() {
        ZonedDateTime local = ZonedDateTime.now(clock.withZone(bank.timeZone()));
        return new BankInfo(bank.name(), bank.bic(), bank.city(), bank.role(), bank.timeZone().getId(),
                local.toInstant(), local.toLocalDate());
    }
}
