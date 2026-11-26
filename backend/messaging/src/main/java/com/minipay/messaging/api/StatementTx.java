package com.minipay.messaging.api;

import com.minipay.common.money.Money;

/**
 * One transaction inside a statement entry (camt.053 Ntry/NtryDtls/TxDtls): the references the account owner
 * reconciles on. A single payment is one TxDtls; a batch booking (the net settlement of a DNS cycle) lists every
 * payment in it, each with its own amount and direction, and the entry's amount is their net.
 *
 * @param credit from the account holder's point of view: true = money in (CRDT)
 */
public record StatementTx(String uetr, String endToEndId, String instructionId, Money amount, boolean credit) {
}
