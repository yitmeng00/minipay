package com.minipay.compliance.api;

public enum DecisionAction {
    /** Not the listed party: let the payment continue (or ask for a second approver). */
    RELEASE,
    /** The listed party: the payment is refused. */
    REJECT,
    /** Cannot tell from what we have: hand to the money laundering reporting officer. */
    ESCALATE,
    /** Second pair of eyes agrees with a release. */
    APPROVE,
    /** Second pair of eyes disagrees with a release: back to the queue. */
    DECLINE,
    /** The releaser takes back a release that is still waiting for approval. */
    WITHDRAW
}
