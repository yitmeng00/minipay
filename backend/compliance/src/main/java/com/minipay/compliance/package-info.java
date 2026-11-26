/**
 * Compliance: sanctions screening of every payment, cases for hits, and four-eyes release (M6, ADR-007).
 *
 * <p>{@code com.minipay.compliance.api} is the public contract, {@code com.minipay.compliance.internal} is
 * private. The module depends on nothing but {@code common}: the payment hub calls it, never the reverse.
 * When a decision closes a case it publishes {@link com.minipay.compliance.api.ComplianceDecisionMade}, an
 * in-process event the hub listens to.
 */
package com.minipay.compliance;
