-- =============================================================================
-- Payment hub schema. See docs/adr/ADR-003-payment-hub.md.
--   * payment               = one credit transfer, identified end to end by its UETR
--   * payment_state_history = every status change, append-only (the audit trail)
--   * idempotency_key       = Idempotency-Key header -> the payment it created
--   * outbox_event          = events to publish to Kafka, written in the business transaction
-- =============================================================================

-- Instruction ids are unique per sending bank: "<BIC4><yyMMdd><seq>".
CREATE SEQUENCE instruction_seq;

CREATE TABLE payment (
    id                          UUID          PRIMARY KEY,
    -- Unique End-to-end Transaction Reference (SWIFT gpi): one per payment, never reused, carried
    -- unchanged by every bank on the route.
    uetr                        UUID          NOT NULL UNIQUE,
    -- Set by the debtor (e.g. invoice number). Not unique: "NOTPROVIDED" when absent (ISO convention).
    end_to_end_id               VARCHAR(35)   NOT NULL,
    -- Set by us for the next agent (pacs.008 PmtId/InstrId). Unique per sending bank.
    instruction_id              VARCHAR(35)   NOT NULL UNIQUE,
    direction                   VARCHAR(8)    NOT NULL CHECK (direction IN ('OUTGOING', 'INCOMING')),
    status                      VARCHAR(16)   NOT NULL CHECK (status IN (
                                    'RECEIVED', 'VALIDATED', 'SCREENED', 'HELD_COMPLIANCE', 'FUNDS_RESERVED',
                                    'SENT', 'ACKED', 'SETTLED', 'REJECTED', 'RETURNED')),
    -- Instructed amount: what the debtor asked to send (pacs.008 InstdAmt).
    amount                      NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency                    CHAR(3)       NOT NULL,
    -- Amount actually moved between banks (pacs.008 IntrBkSttlmAmt): amount minus our fee under CRED.
    interbank_settlement_amount NUMERIC(19,4) CHECK (interbank_settlement_amount > 0),
    -- Our (debtor agent's) charge for this payment.
    charges_amount              NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (charges_amount >= 0),
    -- DEBT (debtor pays all), CRED (creditor pays all), SHAR (each side pays its own bank).
    charge_bearer               CHAR(4)       NOT NULL CHECK (charge_bearer IN ('DEBT', 'CRED', 'SHAR')),
    debtor_name                 VARCHAR(140)  NOT NULL,
    debtor_account              VARCHAR(34)   NOT NULL,
    debtor_agent_bic            VARCHAR(11)   NOT NULL,
    creditor_name               VARCHAR(140)  NOT NULL,
    creditor_account            VARCHAR(34)   NOT NULL,
    creditor_agent_bic          VARCHAR(11)   NOT NULL,
    -- Next bank on the route (pacs.008 InstdAgt) — chosen by routing when the payment is sent.
    instructed_agent_bic        VARCHAR(11),
    remittance_info             VARCHAR(140),
    -- Date the debtor wants the payment executed. A future date = warehoused until that day.
    requested_exec_date         DATE          NOT NULL,
    -- Interbank settlement date, fixed when the payment is sent.
    value_date                  DATE,
    -- ISO 20022 ExternalStatusReason1Code (AM04, AC01 ...) or return reason (AC04 ...).
    rejection_reason            VARCHAR(4),
    rejection_detail            VARCHAR(500),
    -- References into the ledger, by id only (the hub never reads ledger tables).
    reservation_id              UUID,
    settlement_account_id       BIGINT,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Optimistic locking (JPA @Version): two writers of the same payment cannot both win.
    version                     BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX payment_status_idx ON payment (status, updated_at);
CREATE INDEX payment_created_idx ON payment (created_at DESC);
CREATE INDEX payment_e2e_idx ON payment (end_to_end_id);

CREATE TABLE payment_state_history (
    id          BIGSERIAL    PRIMARY KEY,
    payment_id  UUID         NOT NULL REFERENCES payment (id),
    -- NULL for the first row (the payment came into existence as RECEIVED).
    from_status VARCHAR(16),
    to_status   VARCHAR(16)  NOT NULL,
    reason_code VARCHAR(4),
    detail      VARCHAR(500),
    -- Who caused it: a service ("svc/validator") or a person / simulator.
    actor       VARCHAR(64)  NOT NULL,
    -- clock_timestamp(), not now(): the real time of the change, not the transaction start.
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX payment_state_history_payment_idx ON payment_state_history (payment_id, id);

CREATE TABLE idempotency_key (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    -- SHA-256 of the parsed request: same key + different request = HTTP 422.
    request_hash    CHAR(64)     NOT NULL,
    operation       VARCHAR(32)  NOT NULL,
    payment_id      UUID         NOT NULL REFERENCES payment (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- Transactional outbox. Business code inserts here IN THE SAME TRANSACTION as the state change;
-- the relay publishes to Kafka afterwards and marks the row published. Either both the state
-- change and its event exist, or neither does — no "updated the DB but lost the message".
-- -----------------------------------------------------------------------------
CREATE TABLE outbox_event (
    id             UUID          PRIMARY KEY,
    -- Insertion order. The relay publishes in this order.
    seq            BIGSERIAL     NOT NULL UNIQUE,
    aggregate_type VARCHAR(32)   NOT NULL,
    aggregate_id   UUID          NOT NULL,
    event_type     VARCHAR(64)   NOT NULL,
    topic          VARCHAR(128)  NOT NULL,
    -- Kafka key = UETR, so all events of one payment land in one partition, in order.
    message_key    VARCHAR(128)  NOT NULL,
    payload        JSONB         NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    published_at   TIMESTAMPTZ,
    attempts       INT           NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000)
);

CREATE INDEX outbox_event_unpublished_idx ON outbox_event (seq) WHERE published_at IS NULL;

-- History is evidence: append-only, like ledger postings.
CREATE FUNCTION forbid_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'payment.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER payment_state_history_append_only
    BEFORE UPDATE OR DELETE ON payment_state_history
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
