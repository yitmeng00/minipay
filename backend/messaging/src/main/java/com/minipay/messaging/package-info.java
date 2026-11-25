/**
 * Messaging: the bank's ISO 20022 (MX) and SWIFT FIN (MT) gateway.
 *
 * <ul>
 *   <li>builds outbound pacs.008, pacs.002, pacs.004 and camt.053, each with a business application header;</li>
 *   <li>parses and checks inbound messages;</li>
 *   <li>translates pacs.008 ↔ MT103 and reports, field by field, what each translation keeps or loses;</li>
 *   <li>stores every message exactly as sent or received, next to the fields parsed from it (rule 8).</li>
 * </ul>
 *
 * <p>{@code com.minipay.messaging.api} is the public contract; {@code internal} is private (ArchUnit).
 * Messaging knows nothing about payments: the payment hub calls it. See ADR-005.
 */
package com.minipay.messaging;
