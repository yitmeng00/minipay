/**
 * Reference data: the effective-dated facts the payment pipeline checks against.
 *
 * <ul>
 *   <li>the BIC directory: which institutions exist, and whether we can message them;</li>
 *   <li>the IBAN registry and IBAN-to-BIC map: what a valid IBAN looks like in each country, and which bank it belongs to;</li>
 *   <li>settlement schemes, holidays and cut-offs: when a currency can settle;</li>
 *   <li>routing rules: which account we settle on and which bank gets our message.</li>
 * </ul>
 *
 * <p>{@code com.minipay.refdata.api} is the public contract; {@code com.minipay.refdata.internal} is
 * private (ArchUnit-enforced). Every lookup takes a date. See ADR-004.
 */
package com.minipay.refdata;
