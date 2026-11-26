-- =============================================================================
-- Compliance schema: sanctions lists, screenings, cases and decisions. See ADR-007.
--
-- * Sanctions lists are REFERENCE DATA and effective-dated (rule 9): an entry is valid from the day
--   it was listed until the day it was delisted, [listed, delisted). A delisting end-dates the row;
--   nothing is edited or deleted, so "would this payment have hit on 3 March?" can always be answered.
-- * A screening is EVIDENCE: what was screened, against which list snapshots, as of which date, and
--   what matched. It is append-only, like the ledger and the message store.
-- * A case is the work item for a screening with hits. Its status moves only through decisions, and
--   every decision is kept (append-only) with who made it, why, and the comment.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

-- A sanctions list we subscribe to (simulated copies of the real ones).
CREATE TABLE sanctions_list (
    code      VARCHAR(8)  PRIMARY KEY CHECK (code ~ '^[A-Z]{2,8}$'),
    name      VARCHAR(80) NOT NULL,
    authority VARCHAR(80) NOT NULL
);

-- Each load of a list. Real lists are published as files ("the SDN list of 20 Nov"); a load records
-- which file the entries came from.
CREATE TABLE list_snapshot (
    list_code     VARCHAR(8)  NOT NULL REFERENCES sanctions_list (code),
    snapshot_date DATE        NOT NULL,
    loaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (list_code, snapshot_date)
);

CREATE TABLE sanctions_entry (
    id            BIGSERIAL     PRIMARY KEY,
    entry_id      VARCHAR(20)   NOT NULL,                      -- the list's own reference, e.g. SDN.SIM-20931
    list_code     VARCHAR(8)    NOT NULL REFERENCES sanctions_list (code),
    entry_type    VARCHAR(12)   NOT NULL CHECK (entry_type IN ('INDIVIDUAL', 'ENTITY', 'BANK')),
    name          VARCHAR(140)  NOT NULL,
    aliases       VARCHAR(140)[] NOT NULL DEFAULT '{}',
    country       CHAR(2)       CHECK (country ~ '^[A-Z]{2}$'),
    address       VARCHAR(200),
    registration  VARCHAR(80),                                 -- company register / licence number
    date_of_birth DATE,
    bic           CHAR(11)      CHECK (bic ~ '^[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}[A-Z0-9]{3}$'),
    program       VARCHAR(60)   NOT NULL,                      -- legal basis / sanctions programme
    valid         DATERANGE     NOT NULL CHECK (NOT isempty(valid) AND NOT lower_inf(valid)),
    snapshot_date DATE          NOT NULL,
    FOREIGN KEY (list_code, snapshot_date) REFERENCES list_snapshot (list_code, snapshot_date),
    CHECK (entry_type <> 'BANK' OR bic IS NOT NULL),
    EXCLUDE USING gist (entry_id WITH =, valid WITH &&)
);

-- -----------------------------------------------------------------------------
-- Screenings: one per payment, written once. A re-run of the screening step reads it back.
-- -----------------------------------------------------------------------------
CREATE TABLE screening (
    id             UUID          PRIMARY KEY,
    payment_id     UUID          NOT NULL UNIQUE,
    uetr           UUID          NOT NULL,
    direction      VARCHAR(8)    NOT NULL CHECK (direction IN ('OUTGOING', 'INCOMING')),
    kind           VARCHAR(8)    NOT NULL CHECK (kind IN ('CUSTOMER', 'COVER')),
    amount         NUMERIC(20,4) NOT NULL CHECK (amount > 0),
    currency       CHAR(3)       NOT NULL,
    debtor_name    VARCHAR(140)  NOT NULL,
    creditor_name  VARCHAR(140)  NOT NULL,
    route          VARCHAR(200)  NOT NULL,
    request        JSONB         NOT NULL,                     -- every name and BIC that was screened
    as_of          DATE          NOT NULL,                     -- lists as in force on this date
    lists          VARCHAR(200)  NOT NULL,                     -- e.g. "OFAC 2026-11-20, EU 2026-11-20"
    hold_threshold SMALLINT      NOT NULL CHECK (hold_threshold BETWEEN 1 AND 100),
    best_score     SMALLINT      NOT NULL CHECK (best_score BETWEEN 0 AND 100),
    outcome        VARCHAR(5)    NOT NULL CHECK (outcome IN ('CLEAR', 'HIT')),
    screened_at    TIMESTAMPTZ   NOT NULL,
    CHECK ((outcome = 'HIT') = (best_score >= hold_threshold))
);

CREATE TABLE screening_hit (
    id           BIGSERIAL    PRIMARY KEY,
    screening_id UUID         NOT NULL REFERENCES screening (id),
    party_role   VARCHAR(24)  NOT NULL,                        -- DEBTOR, CREDITOR, DEBTOR_AGENT, …
    field        VARCHAR(60)  NOT NULL,                        -- where in the message, e.g. Cdtr/Nm
    party_name   VARCHAR(140) NOT NULL,
    entry_pk     BIGINT       NOT NULL REFERENCES sanctions_entry (id),  -- the exact version that matched
    matched_on   VARCHAR(8)   NOT NULL CHECK (matched_on IN ('NAME', 'ALIAS', 'BIC')),
    matched_name VARCHAR(140) NOT NULL,
    score        SMALLINT     NOT NULL CHECK (score BETWEEN 0 AND 100),
    detail       JSONB        NOT NULL                         -- factors, comparison, matched tokens, hint
);
CREATE INDEX screening_hit_screening ON screening_hit (screening_id);

-- -----------------------------------------------------------------------------
-- Cases and decisions
-- -----------------------------------------------------------------------------
CREATE TABLE screening_case (
    id           UUID        PRIMARY KEY,
    screening_id UUID        NOT NULL UNIQUE REFERENCES screening (id),
    payment_id   UUID        NOT NULL UNIQUE,
    best_score   SMALLINT    NOT NULL,
    four_eyes    BOOLEAN     NOT NULL,                         -- a release needs a second approver
    status       VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'ESCALATED', 'PENDING_APPROVAL', 'RELEASED', 'REJECTED')),
    held_at      TIMESTAMPTZ NOT NULL,
    closed_at    TIMESTAMPTZ,
    CHECK ((status IN ('RELEASED', 'REJECTED')) = (closed_at IS NOT NULL)),
    CHECK (four_eyes OR status <> 'PENDING_APPROVAL')
);
CREATE INDEX screening_case_open ON screening_case (status) WHERE closed_at IS NULL;

CREATE TABLE decision (
    id              BIGSERIAL    PRIMARY KEY,
    case_id         UUID         NOT NULL REFERENCES screening_case (id),
    action          VARCHAR(10)  NOT NULL CHECK (action IN ('RELEASE', 'REJECT', 'ESCALATE', 'APPROVE', 'DECLINE', 'WITHDRAW')),
    reason          VARCHAR(24)  CHECK (reason IN ('FALSE_POSITIVE', 'TRUE_MATCH', 'INSUFFICIENT_INFORMATION')),
    comment         VARCHAR(500) NOT NULL CHECK (length(btrim(comment)) >= 20),
    decided_by      VARCHAR(64)  NOT NULL CHECK (decided_by ~ '^[a-z0-9._-]+$'),
    decided_at      TIMESTAMPTZ  NOT NULL,
    status_before   VARCHAR(16)  NOT NULL,
    status_after    VARCHAR(16)  NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    request_hash    CHAR(64)     NOT NULL
);
CREATE INDEX decision_case ON decision (case_id, id);
CREATE INDEX decision_day ON decision (decided_at);

-- -----------------------------------------------------------------------------
-- Guards in the database, so that no code path can bypass them.
-- -----------------------------------------------------------------------------
CREATE FUNCTION forbid_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'compliance.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE FUNCTION allow_end_dating_only() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'compliance.% rows are never deleted: end-date (delist) them instead', TG_TABLE_NAME
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (to_jsonb(NEW) - 'valid') IS DISTINCT FROM (to_jsonb(OLD) - 'valid')
       OR lower(NEW.valid) IS DISTINCT FROM lower(OLD.valid)
       OR NOT (NEW.valid <@ OLD.valid) THEN
        RAISE EXCEPTION 'compliance.% rows can only be end-dated, not changed', TG_TABLE_NAME
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- A case only moves while open, and a closed case is never reopened or changed.
CREATE FUNCTION case_guard() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'compliance.screening_case rows are never deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.closed_at IS NOT NULL THEN
        RAISE EXCEPTION 'case % is closed (%)', OLD.id, OLD.status USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'closed_at') IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'closed_at') THEN
        RAISE EXCEPTION 'only the status of a case changes' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- Four eyes: whoever approves a release must not be whoever released it.
CREATE FUNCTION four_eyes_guard() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    releaser VARCHAR(64);
BEGIN
    IF NEW.action = 'APPROVE' THEN
        SELECT decided_by INTO releaser FROM compliance.decision
         WHERE case_id = NEW.case_id AND action = 'RELEASE' ORDER BY id DESC LIMIT 1;
        IF releaser IS NULL OR releaser = NEW.decided_by THEN
            RAISE EXCEPTION 'four-eyes: % cannot approve a release made by %', NEW.decided_by, coalesce(releaser, 'nobody')
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER sanctions_entry_end_dating_only BEFORE UPDATE OR DELETE ON sanctions_entry
    FOR EACH ROW EXECUTE FUNCTION allow_end_dating_only();
CREATE TRIGGER list_snapshot_append_only BEFORE UPDATE OR DELETE ON list_snapshot
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER screening_append_only BEFORE UPDATE OR DELETE ON screening
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER screening_hit_append_only BEFORE UPDATE OR DELETE ON screening_hit
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER decision_append_only BEFORE UPDATE OR DELETE ON decision
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER decision_four_eyes BEFORE INSERT ON decision
    FOR EACH ROW EXECUTE FUNCTION four_eyes_guard();
CREATE TRIGGER screening_case_guard BEFORE UPDATE OR DELETE ON screening_case
    FOR EACH ROW EXECUTE FUNCTION case_guard();
