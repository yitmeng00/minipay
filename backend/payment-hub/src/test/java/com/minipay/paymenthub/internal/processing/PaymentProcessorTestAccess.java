package com.minipay.paymenthub.internal.processing;

/** Lets tests in other packages use the processor's package-private crash seam. */
public final class PaymentProcessorTestAccess {

    private PaymentProcessorTestAccess() {
    }

    public static void crashAfterLedgerBooking(PaymentProcessor processor) {
        processor.afterSettle(() -> {
            throw new IllegalStateException("Simulated crash after the ledger booked the payment, before SENT");
        });
    }

    public static void noCrash(PaymentProcessor processor) {
        processor.afterSettle(null);
    }
}
