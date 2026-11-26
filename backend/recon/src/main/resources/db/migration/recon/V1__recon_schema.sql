-- =============================================================================
-- Nostro reconciliation (ADR-009). Our books against the servicer's: for each nostro, the camt.053 statements the
-- servicer sends, our ledger's lines on that account, what matched what, and the breaks.
--
-- * statement / statement_entry: what the servicer said, as received (the raw camt.053 is in the message store).
-- * ledger_item: a snapshot of our ledger lines on the nostro, with the payment references (UETR, E2E) the ledger
--   itself does not keep. Written once per posting.
-- * match / match_item: an item (a ledger line or a statement entry) is matched at most once (UNIQUE).
-- * recon_break: what did not match, typed, with its difference; worked by people (resolved) or cleared by a
--   later match. Only status and resolution change.
-- =============================================================================

CREATE TABLE statement (
    id                UUID          PRIMARY KEY,
    message_id        UUID          NOT NULL UNIQUE,
    ledger_account    VARCHAR(34)   NOT NULL,
    servicer_bic      CHAR(11)      NOT NULL,
    servicer_account  VARCHAR(34)   NOT NULL,
    statement_ref     VARCHAR(35)   NOT NULL,
    msg_id            VARCHAR(35)   NOT NULL,
    statement_date    DATE          NOT NULL,
    currency          CHAR(3)       NOT NULL,
    opening_booked    NUMERIC(20,4) NOT NULL,
    closing_booked    NUMERIC(20,4) NOT NULL,
    received_at       TIMESTAMPTZ   NOT NULL
);
CREATE INDEX statement_account ON statement (ledger_account, statement_date);

CREATE TABLE statement_entry (
    id            BIGSERIAL     PRIMARY KEY,
    statement_id  UUID          NOT NULL REFERENCES statement (id),
    seq           INT           NOT NULL,
    entry_ref     VARCHAR(35),
    signed_amount NUMERIC(20,4) NOT NULL,                -- owner's view: + money in (CRDT), − money out (DBIT)
    booked_at     TIMESTAMPTZ,
    value_date    DATE,
    servicer_ref  VARCHAR(35),
    reversal      BOOLEAN       NOT NULL,
    info          VARCHAR(500),
    bank_tx_code  VARCHAR(40),
    uetr          VARCHAR(36),                           -- single-transaction entries
    end_to_end_id VARCHAR(35),
    txs           JSONB         NOT NULL,                -- every TxDtls (several for a batch booking)
    xml           TEXT,
    UNIQUE (statement_id, seq)
);

CREATE TABLE ledger_item (
    posting_id       BIGINT        PRIMARY KEY,
    ledger_account   VARCHAR(34)   NOT NULL,
    journal_entry_id UUID          NOT NULL,
    item_date        DATE          NOT NULL,
    booked_at        TIMESTAMPTZ   NOT NULL,
    signed_amount    NUMERIC(20,4) NOT NULL,             -- + debit on our nostro (money in), − credit
    entry_type       VARCHAR(16)   NOT NULL,
    payment_id       UUID,
    uetr             VARCHAR(36),
    end_to_end_id    VARCHAR(35),
    narrative        VARCHAR(500)
);
CREATE INDEX ledger_item_account ON ledger_item (ledger_account, item_date);

CREATE TABLE match (
    id             BIGSERIAL    PRIMARY KEY,
    ledger_account VARCHAR(34)  NOT NULL,
    rule           VARCHAR(12)  NOT NULL CHECK (rule IN ('TAKE_ON', 'UETR', 'BATCH', 'E2E', 'REFERENCE', 'CONTRA', 'ADJUSTMENT', 'MANUAL')),
    matched_by     VARCHAR(64)  NOT NULL,
    comment        VARCHAR(500),
    matched_at     TIMESTAMPTZ  NOT NULL,
    CHECK (rule <> 'MANUAL' OR comment IS NOT NULL)
);

CREATE TABLE match_item (
    match_id      BIGINT        NOT NULL REFERENCES match (id),
    side          VARCHAR(9)    NOT NULL CHECK (side IN ('LEDGER', 'STATEMENT')),
    item_id       BIGINT        NOT NULL,
    signed_amount NUMERIC(20,4) NOT NULL,
    PRIMARY KEY (side, item_id)                          -- an item is matched once, never twice
);
CREATE INDEX match_item_match ON match_item (match_id);

CREATE SEQUENCE break_seq;

CREATE TABLE recon_break (
    id                 VARCHAR(12)   PRIMARY KEY,         -- BRK-0001
    ledger_account     VARCHAR(34)   NOT NULL,
    type               VARCHAR(20)   NOT NULL CHECK (type IN ('MISSING_IN_STATEMENT', 'MISSING_IN_LEDGER', 'AMOUNT_MISMATCH', 'DUPLICATE')),
    ledger_item_id     BIGINT,
    statement_entry_id BIGINT        REFERENCES statement_entry (id),
    difference         NUMERIC(20,4) NOT NULL,           -- statement − ledger, owner's view
    status             VARCHAR(8)    NOT NULL CHECK (status IN ('OPEN', 'RESOLVED', 'CLEARED')),
    opened_at          TIMESTAMPTZ   NOT NULL,
    resolution         VARCHAR(16)   CHECK (resolution IN ('CARRY_FORWARD', 'ADJUSTMENT', 'CLAIM_RAISED')),
    comment            VARCHAR(500),
    resolved_by        VARCHAR(64),
    resolved_at        TIMESTAMPTZ,
    cleared_by_match   BIGINT        REFERENCES match (id),
    CHECK (ledger_item_id IS NOT NULL OR statement_entry_id IS NOT NULL),
    CHECK ((status = 'RESOLVED') = (resolution IS NOT NULL)),
    CHECK (status <> 'CLEARED' OR cleared_by_match IS NOT NULL)
);
CREATE INDEX recon_break_account ON recon_break (ledger_account, status);

CREATE TABLE command_key (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    request_hash    CHAR(64)     NOT NULL,
    result          VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE FUNCTION forbid_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'recon.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

-- A break keeps what it is about; only its status and resolution move.
CREATE FUNCTION break_guard() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'recon.recon_break rows are never deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'resolution' - 'comment' - 'resolved_by' - 'resolved_at' - 'cleared_by_match')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'resolution' - 'comment' - 'resolved_by' - 'resolved_at' - 'cleared_by_match') THEN
        RAISE EXCEPTION 'only the status and resolution of a break change' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status = 'CLEARED' THEN
        RAISE EXCEPTION 'break % was cleared by a match', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER statement_append_only BEFORE UPDATE OR DELETE ON statement FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER statement_entry_append_only BEFORE UPDATE OR DELETE ON statement_entry FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER ledger_item_append_only BEFORE UPDATE OR DELETE ON ledger_item FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER match_append_only BEFORE UPDATE OR DELETE ON match FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER match_item_append_only BEFORE UPDATE OR DELETE ON match_item FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER command_key_append_only BEFORE UPDATE OR DELETE ON command_key FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER recon_break_guard BEFORE UPDATE OR DELETE ON recon_break FOR EACH ROW EXECUTE FUNCTION break_guard();
