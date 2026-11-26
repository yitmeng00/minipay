-- M5: payments that arrive from other banks over the network (direction INCOMING).
--   kind                   CUSTOMER (a pacs.008: someone's customer pays someone) or COVER (a pacs.009 COV:
--                          we are the correspondent moving the money for a cover payment)
--   instructing_agent_bic  the bank that sent it to us (InstgAgt); replies go back to it
--   settlement_method      SttlmMtd of the message: INDA, INGA, COVE, CLRG
--   cover_agent_bic        for COVE, the bank through which the money arrives (InstgRmbrsmntAgt)
--   inbound_message_id     the stored message (messaging schema) that created the payment
--   forward_instruction_id our own InstrId when we forward it to the next bank
--   inbound_account_id     the ledger account the inbound leg settles on (a vostro or nostro with the
--                          instructing agent); a reversal or a return books against it again
ALTER TABLE payment
    ADD COLUMN kind                   VARCHAR(8)  NOT NULL DEFAULT 'CUSTOMER' CHECK (kind IN ('CUSTOMER', 'COVER')),
    ADD COLUMN instructing_agent_bic  VARCHAR(11),
    ADD COLUMN settlement_method      VARCHAR(4)  CHECK (settlement_method IN ('INDA', 'INGA', 'COVE', 'CLRG')),
    ADD COLUMN cover_agent_bic        VARCHAR(11),
    ADD COLUMN inbound_message_id     UUID,
    ADD COLUMN forward_instruction_id VARCHAR(35),
    ADD COLUMN inbound_account_id     BIGINT;

ALTER TABLE payment DROP CONSTRAINT payment_status_check;
ALTER TABLE payment ADD CONSTRAINT payment_status_check CHECK (status IN (
    'RECEIVED', 'VALIDATED', 'SCREENED', 'HELD_COMPLIANCE', 'FUNDS_RESERVED', 'AWAITING_COVER',
    'SENT', 'ACKED', 'SETTLED', 'REJECTED', 'RETURNED'));

-- An incoming payment must say who sent it.
ALTER TABLE payment ADD CONSTRAINT payment_incoming_has_sender
    CHECK (direction = 'OUTGOING' OR instructing_agent_bic IS NOT NULL);
