package com.minipay.network.api;

import com.minipay.common.money.Money;
import java.time.Instant;

/** One line of the tracker's list: a payment and where it stands. */
public record TrackerSummary(String uetr, String status, String debtorAgentBic, String creditorAgentBic, Money sentAmount,
                             int messages, Instant lastUpdate) {
}
