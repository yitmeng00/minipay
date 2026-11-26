package com.minipay.compliance.api;

/** Who in the payment a screened name or BIC belongs to. */
public enum PartyRole {
    DEBTOR,
    CREDITOR,
    DEBTOR_AGENT,
    CREDITOR_AGENT,
    INSTRUCTING_AGENT,
    INSTRUCTED_AGENT,
    REIMBURSEMENT_AGENT;

    public boolean isAgent() {
        return this != DEBTOR && this != CREDITOR;
    }
}
