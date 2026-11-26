package com.minipay.compliance.api;

/**
 * One party or agent of the payment, as the message carries it.
 *
 * @param field   where it sits in the message, e.g. {@code Cdtr/Nm} or {@code CdtrAgt/FinInstnId/BICFI}
 * @param name    the party's name (null for an agent identified only by BIC)
 * @param account the party's account (IBAN or other), if any
 * @param bic     the agent's BIC, or the party's bank
 */
public record ScreenedParty(PartyRole role, String field, String name, String account, String bic) {
}
