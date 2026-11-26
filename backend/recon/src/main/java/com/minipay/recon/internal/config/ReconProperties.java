package com.minipay.recon.internal.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code minipay.recon}: which of our accounts are kept by another bank, and under which number there (nostro master
 * data, as every bank keeps it), and where reconciliation adjustments are booked.
 *
 * @param nostros            our account → the servicer and the account's number at the servicer
 * @param adjustmentAccounts per currency, the account for differences we book (bank charges, adjustments)
 * @param autoPublish        servicer: send yesterday's statements by itself after midnight (off in tests)
 */
@ConfigurationProperties("minipay.recon")
public record ReconProperties(List<Nostro> nostros, Map<String, String> adjustmentAccounts, Boolean autoPublish) {

    public ReconProperties {
        nostros = nostros == null ? List.of() : List.copyOf(nostros);
        adjustmentAccounts = adjustmentAccounts == null ? Map.of() : Map.copyOf(adjustmentAccounts);
        autoPublish = autoPublish == null || autoPublish;
    }

    public record Nostro(String account, String servicer, String servicerAccount) {
    }
}
