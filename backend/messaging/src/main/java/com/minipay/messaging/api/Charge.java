package com.minipay.messaging.api;

import com.minipay.common.money.Money;

/** ChrgsInf: an amount a bank on the route took, and which bank took it. */
public record Charge(Money amount, String agentBic) {
}
