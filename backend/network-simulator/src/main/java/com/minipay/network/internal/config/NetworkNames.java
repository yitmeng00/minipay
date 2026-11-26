package com.minipay.network.internal.config;

import com.minipay.common.id.Bic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Topic and consumer-group names for this bank (used by the listeners through SpEL). */
@Component("networkNames")
public class NetworkNames {

    private final String bic8;

    public NetworkNames(@Value("${minipay.bank.bic}") String bankBic) {
        this.bic8 = Bic.parse(bankBic).bic8();
    }

    /** This bank's inbox: messages addressed to it. */
    public String inbox() {
        return "minipay.network." + bic8;
    }

    /** One consumer group per bank: every message in the inbox is processed by this bank once. */
    public String inboxGroup() {
        return "bank-" + bic8;
    }

    /** The tracker reads every topic of the network, with its own group, so it sees every message too. */
    public String trackerGroup() {
        return "tracker-" + bic8;
    }
}
