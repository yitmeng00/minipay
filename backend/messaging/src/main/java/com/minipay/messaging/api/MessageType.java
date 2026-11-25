package com.minipay.messaging.api;

/** The messages MiniPay speaks. MX names are ISO 20022 message definition identifiers. */
public enum MessageType {
    PACS_008("pacs.008.001.08", "FI to FI customer credit transfer"),
    PACS_002("pacs.002.001.10", "Payment status report"),
    PACS_004("pacs.004.001.09", "Payment return"),
    CAMT_053("camt.053.001.08", "Bank to customer statement"),
    MT103("MT103", "Single customer credit transfer (SWIFT FIN)");

    private final String identifier;
    private final String description;

    MessageType(String identifier, String description) {
        this.identifier = identifier;
        this.description = description;
    }

    public String identifier() {
        return identifier;
    }

    public String description() {
        return description;
    }

    public boolean isMx() {
        return this != MT103;
    }

    public static MessageType fromIdentifier(String id) {
        for (MessageType t : values()) {
            if (t.identifier.equals(id)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unsupported message type " + id);
    }
}
