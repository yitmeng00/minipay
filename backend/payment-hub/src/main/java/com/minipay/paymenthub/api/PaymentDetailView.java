package com.minipay.paymenthub.api;

import java.util.List;

public record PaymentDetailView(PaymentView payment, List<StateChangeView> history) {
}
