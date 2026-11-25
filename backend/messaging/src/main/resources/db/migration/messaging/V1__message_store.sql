-- =============================================================================
-- The message store. See docs/adr/ADR-005-messaging.md.
--
-- Rule 8: every message is stored EXACTLY as it was sent or received (raw), next to the fields
-- parsed from it. The raw text is evidence: what we said, what we were told. Parsed columns are for
-- searching and correlating; if they ever disagree with the raw text, the raw text wins.
-- =============================================================================

-- MsgId sequence: "<BIC4><yyyyMMdd>-<type>-<seq>", unique per sending bank.
CREATE SEQUENCE msg_seq;

CREATE TABLE message (
    id              UUID         PRIMARY KEY,
    direction       VARCHAR(8)   NOT NULL CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    -- ISO 20022 message definition identifier (pacs.008.001.08 …) or MT103. NULL only for an inbound
    -- message that could not even be identified.
    message_type    VARCHAR(16),
    syntax          VARCHAR(2)   CHECK (syntax IN ('MX', 'MT')),
    msg_id          VARCHAR(35),
    sender_bic      CHAR(11),
    receiver_bic    CHAR(11),
    -- Outbound only: what the message is FOR (e.g. "pacs.008:<payment id>"). One message per purpose,
    -- so a retried step finds the message it already sent instead of sending another.
    business_key    VARCHAR(100) UNIQUE,
    uetr            UUID,
    end_to_end_id   VARCHAR(35),
    instruction_id  VARCHAR(35),
    -- pacs.002 / pacs.004: the MsgId of the message they refer to.
    original_msg_id VARCHAR(35),
    -- camt.053: the statement's account.
    account         VARCHAR(34),
    amount          NUMERIC(19,4),
    currency        CHAR(3),
    settlement_date DATE,
    -- TxSts (ACCP, ACSC, RJCT …), RTND for a return, INVALID for a message that failed the checks.
    status          VARCHAR(8),
    reason_code     VARCHAR(4),
    errors          TEXT[]       NOT NULL DEFAULT '{}',
    raw             TEXT         NOT NULL,
    -- The database itself proves the raw text is unchanged since it was stored.
    raw_sha256      CHAR(64)     NOT NULL CHECK (raw_sha256 = encode(sha256(convert_to(raw, 'UTF8')), 'hex')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CHECK (status <> 'INVALID' OR direction = 'INBOUND'),
    CHECK ((status = 'INVALID') = (cardinality(errors) > 0))
);

-- A bank never reuses a MsgId. Receiving one again is a duplicate (a resend, or a replay): the
-- constraint makes sure it is stored and acted on only once.
CREATE UNIQUE INDEX message_sender_msg_id_uq ON message (direction, sender_bic, msg_id)
    WHERE msg_id IS NOT NULL AND sender_bic IS NOT NULL;
CREATE INDEX message_uetr_idx ON message (uetr, created_at);
CREATE INDEX message_type_idx ON message (message_type, created_at DESC);
CREATE INDEX message_account_idx ON message (account, created_at) WHERE account IS NOT NULL;

-- Idempotency-Key for the statement API (rule 5).
CREATE TABLE idempotency_key (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    request_hash    CHAR(64)     NOT NULL,
    message_id      UUID         NOT NULL REFERENCES message (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Messages are evidence: append-only, like ledger postings.
CREATE FUNCTION forbid_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'messaging.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER message_append_only BEFORE UPDATE OR DELETE ON message
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER idempotency_key_append_only BEFORE UPDATE OR DELETE ON idempotency_key
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
