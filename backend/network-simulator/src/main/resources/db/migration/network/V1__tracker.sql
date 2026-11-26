-- =============================================================================
-- The UETR tracker: every message seen on the network, one row each. See ADR-006.
-- Like SWIFT's gpi Tracker it observes the whole route, not just one bank's side of it. The raw text
-- stays with the banks that sent and received it (messaging schema); the tracker keeps what it read.
-- =============================================================================
CREATE TABLE tracker_event (
    -- The network event id (outbox row id of the sender). A redelivery has the same id: stored once.
    event_id          UUID          PRIMARY KEY,
    uetr              UUID          NOT NULL,
    message_type      VARCHAR(16)   NOT NULL,
    msg_id            VARCHAR(35)   NOT NULL,
    sender_bic        CHAR(11)      NOT NULL,
    receiver_bic      CHAR(11)      NOT NULL,
    original_msg_id   VARCHAR(35),
    amount            NUMERIC(19,4),
    currency          CHAR(3),
    settlement_method VARCHAR(4),
    status            VARCHAR(8),
    reason_code       VARCHAR(4),
    -- [{"agent": "MNPBDEFFXXX", "amount": "15.00"}, ...]: every deduction the message lists
    charges           JSONB         NOT NULL DEFAULT '[]',
    -- When the network carried it (the Kafka record timestamp), not when we read it.
    sent_at           TIMESTAMPTZ   NOT NULL,
    recorded_at       TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX tracker_event_uetr_idx ON tracker_event (uetr, sent_at);
CREATE INDEX tracker_event_recent_idx ON tracker_event (sent_at DESC);

CREATE FUNCTION forbid_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'network.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER tracker_event_append_only BEFORE UPDATE OR DELETE ON tracker_event
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
