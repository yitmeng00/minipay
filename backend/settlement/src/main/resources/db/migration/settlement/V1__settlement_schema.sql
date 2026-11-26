-- =============================================================================
-- The clearing and settlement systems' own records (ADR-008). This schema exists in every bank's
-- database, but only the operator (the simulated central bank) writes to it.
--
-- * The money is on the operator's ledger: each participant has an RTGS account (T2 DCA) and an instant
--   account (TIPS DCA), both liabilities of the central bank. This schema records the payment orders,
--   the queue, the DNS cycles and their net positions.
-- * transfer: one row per payment order a participant sent. Its status changes (queued → settled), and
--   every change is kept in transfer_event (append-only), like the hub's payment and history.
-- =============================================================================

CREATE TABLE participant (
    bic             CHAR(11)    PRIMARY KEY,
    name            VARCHAR(80) NOT NULL,
    -- Played by the operator itself (a bank MiniPay does not run): it answers instant payments automatically.
    simulated       BOOLEAN     NOT NULL,
    rtgs_account    VARCHAR(34) NOT NULL UNIQUE,
    instant_account VARCHAR(34) NOT NULL UNIQUE
);

CREATE TABLE dns_cycle (
    id              VARCHAR(32) PRIMARY KEY,                      -- e.g. MPNET-20261126-02
    system          VARCHAR(8)  NOT NULL,
    business_date   DATE        NOT NULL,
    seq             SMALLINT    NOT NULL CHECK (seq > 0),
    status          VARCHAR(8)  NOT NULL CHECK (status IN ('OPEN', 'SETTLED')),
    scheduled_close TIMESTAMPTZ NOT NULL,
    opened_at       TIMESTAMPTZ NOT NULL,
    settled_at      TIMESTAMPTZ,
    entry_id        UUID,                                         -- the operator's ledger entry for the net positions
    unwound         VARCHAR(200),                                 -- participants whose payments were taken out
    UNIQUE (system, business_date, seq),
    CHECK ((status = 'SETTLED') = (settled_at IS NOT NULL))
);
-- At most one cycle collects payments for a system and date.
CREATE UNIQUE INDEX dns_cycle_one_open ON dns_cycle (system, business_date) WHERE status = 'OPEN';

-- The settled net positions of a cycle (written once, when the cycle settles).
CREATE TABLE dns_position (
    cycle_id VARCHAR(32)   NOT NULL REFERENCES dns_cycle (id),
    bic      CHAR(11)      NOT NULL REFERENCES participant (bic),
    pays     NUMERIC(20,4) NOT NULL CHECK (pays >= 0),
    receives NUMERIC(20,4) NOT NULL CHECK (receives >= 0),
    net      NUMERIC(20,4) NOT NULL,
    PRIMARY KEY (cycle_id, bic),
    CHECK (net = receives - pays)
);

CREATE SEQUENCE queue_seq;

CREATE TABLE transfer (
    id               UUID          PRIMARY KEY,
    system           VARCHAR(8)    NOT NULL,                     -- T2, MPNET, MPINST
    kind             VARCHAR(8)    NOT NULL CHECK (kind IN ('RTGS', 'DNS', 'INSTANT')),
    message_type     VARCHAR(16)   NOT NULL,                     -- pacs.008 (a payment) or pacs.004 (a return)
    message_id       UUID          NOT NULL UNIQUE,              -- the stored inbound message
    msg_id           VARCHAR(35)   NOT NULL,
    uetr             UUID          NOT NULL,
    end_to_end_id    VARCHAR(35),
    instruction_id   VARCHAR(35),
    payer_bic        CHAR(11)      NOT NULL,
    payee_bic        CHAR(11)      NOT NULL,
    amount           NUMERIC(20,4) NOT NULL CHECK (amount > 0),
    currency         CHAR(3)       NOT NULL,
    priority         VARCHAR(4)    NOT NULL CHECK (priority IN ('HIGH', 'NORM')),
    settlement_date  DATE          NOT NULL,
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('QUEUED', 'ACCEPTED', 'PENDING', 'SETTLED', 'REJECTED')),
    queue_seq        BIGINT        NOT NULL DEFAULT nextval('queue_seq'),
    cycle_id         VARCHAR(32)   REFERENCES dns_cycle (id),
    reservation_id   UUID,                                       -- instant: the hold on the payer's position
    deadline         TIMESTAMPTZ,                                -- instant: answer by then, or AB05
    auto_answer_at   TIMESTAMPTZ,                                -- instant to a simulated participant
    entry_id         UUID,                                       -- the operator's ledger entry that settled it
    reason_code      VARCHAR(4),
    reason_detail    VARCHAR(200),
    received_at      TIMESTAMPTZ   NOT NULL,
    settled_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ   NOT NULL,
    CHECK ((status = 'SETTLED') = (settled_at IS NOT NULL)),
    CHECK ((status = 'REJECTED') = (reason_code IS NOT NULL)),
    CHECK ((kind = 'DNS') = (cycle_id IS NOT NULL) OR status = 'REJECTED'),
    CHECK ((kind = 'INSTANT') = (deadline IS NOT NULL))
);
CREATE INDEX transfer_queue ON transfer (payer_bic, kind, status) WHERE status IN ('QUEUED', 'PENDING');
CREATE INDEX transfer_payee ON transfer (payee_bic, kind, status);
CREATE INDEX transfer_uetr ON transfer (uetr);
CREATE INDEX transfer_cycle ON transfer (cycle_id);
CREATE INDEX transfer_received ON transfer (received_at);

CREATE TABLE transfer_event (
    id          BIGSERIAL    PRIMARY KEY,
    transfer_id UUID         NOT NULL REFERENCES transfer (id),
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    from_status VARCHAR(10),
    to_status   VARCHAR(10)  NOT NULL,
    detail      VARCHAR(500) NOT NULL,
    actor       VARCHAR(64)  NOT NULL
);
CREATE INDEX transfer_event_transfer ON transfer_event (transfer_id, id);

-- Idempotency-Key for the participants' commands (rule 5): reorder, priority, close a cycle.
CREATE TABLE command_key (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    request_hash    CHAR(64)     NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE FUNCTION forbid_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'settlement.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

-- A settled or rejected transfer is final: nothing about it changes any more (finality, ADR-008).
CREATE FUNCTION transfer_guard() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'settlement.transfer rows are never deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status IN ('SETTLED', 'REJECTED') THEN
        RAISE EXCEPTION 'transfer % is final (%)', OLD.id, OLD.status USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transfer_event_append_only BEFORE UPDATE OR DELETE ON transfer_event
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER dns_position_append_only BEFORE UPDATE OR DELETE ON dns_position
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER command_key_append_only BEFORE UPDATE OR DELETE ON command_key
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER transfer_final BEFORE UPDATE OR DELETE ON transfer
    FOR EACH ROW EXECUTE FUNCTION transfer_guard();
