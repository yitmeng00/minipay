package com.minipay.paymenthub.internal.outbox;

/** Lets tests in other packages use the relay's package-private crash seam. */
public final class OutboxRelayTestAccess {

    private OutboxRelayTestAccess() {
    }

    public static void crashAfterSend(OutboxRelay relay) {
        relay.setCrashHook(() -> {
            throw new IllegalStateException("Simulated relay crash after Kafka ack, before marking published");
        });
    }

    public static void noCrash(OutboxRelay relay) {
        relay.setCrashHook(null);
    }
}
