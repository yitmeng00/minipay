package com.minipay.bankapp;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity of the bank this instance plays.
 *
 * @param timeZone where the bank is. Business date, statement days and (from M3) cut-offs are
 *                 evaluated here, while all timestamps are stored in UTC.
 */
@ConfigurationProperties("minipay.bank")
public record BankProperties(String name, String bic, String city, String role, ZoneId timeZone) {
}
