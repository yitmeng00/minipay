/**
 * The simulated network between the banks (SWIFT's role).
 *
 * <ul>
 *   <li><b>Inbox:</b> each bank consumes its own topic {@code minipay.network.<BIC8>} and hands every message to
 *       its payment hub. Messages are put on the network by the sending bank's transactional outbox.</li>
 *   <li><b>UETR tracker:</b> like the gpi Tracker, it sees every message on the network and builds, per UETR,
 *       the end-to-end route: hops, amounts and deductions, confirmations, cover legs and returns. Each bank
 *       instance keeps its own copy of this read model.</li>
 * </ul>
 * {@code com.minipay.network.api} is the public contract; {@code internal} is private. See ADR-006.
 */
package com.minipay.network;
