package com.minipay.common.iso;

/**
 * Who pays the charges of a credit transfer — ISO 20022 {@code ChargeBearerType1Code}
 * (pacs.008 {@code ChrgBr}; MT103 field 71A uses OUR / BEN / SHA for the same idea).
 *
 * <ul>
 *   <li>{@link #DEBT} — debtor pays all charges (MT: OUR). The creditor receives the full amount.</li>
 *   <li>{@link #CRED} — creditor pays all charges (MT: BEN). Every bank on the route deducts its fee
 *       from the amount, so the creditor receives less than was sent.</li>
 *   <li>{@link #SHAR} — shared (MT: SHA). The debtor pays the debtor agent's fee; later banks deduct
 *       theirs from the amount. The usual choice for cross-border business payments.</li>
 * </ul>
 * ({@code SLEV}, "following service level", is used in SEPA, not in CBPR+ customer transfers.)
 */
public enum ChargeBearer {
    DEBT,
    CRED,
    SHAR
}
