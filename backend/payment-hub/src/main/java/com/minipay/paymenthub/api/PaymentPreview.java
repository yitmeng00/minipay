package com.minipay.paymenthub.api;

import java.util.List;

/**
 * A pacs.008 built from a payment that has not been created.
 *
 * @param notes route, value date and anything that could not be determined (e.g. no route)
 */
public record PaymentPreview(String xml, List<String> notes) {
}
