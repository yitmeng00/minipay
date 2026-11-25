package com.minipay.refdata.api;

/** Whether we can exchange messages with an institution. */
public enum Connectivity {
    /** Live on the network, and our relationship (RMA) with it is authorised: we may send to it. */
    CONNECTED,
    /** Live on the network, but no authorised relationship yet: reachable only through another bank. */
    PENDING_RMA,
    /** Not on the network (a "non-connected BIC"): reachable only through a correspondent. */
    NOT_CONNECTED
}
