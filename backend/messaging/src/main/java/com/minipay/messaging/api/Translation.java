package com.minipay.messaging.api;

import java.util.List;

/**
 * A translated message and the field-by-field report of what the translation kept, changed or lost.
 *
 * @param target the translated message (MT103 FIN text, or pacs.008 XML)
 */
public record Translation(MessageType from, MessageType to, String target, List<FieldMapping> fields) {

    public long lost() {
        return fields.stream().filter(f -> f.fidelity() == Fidelity.LOST || f.fidelity() == Fidelity.TRUNCATED).count();
    }
}
