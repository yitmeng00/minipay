-- =============================================================================
-- Reference data schema. See docs/adr/ADR-004-reference-data.md.
--
-- Everything here is EFFECTIVE-DATED: a row says what was true over a range of dates, as a
-- half-open daterange [from, to). Nothing is updated in place. A change is a new row, and the old row
-- is end-dated (its upper bound is set). So every question can be asked "as of" a date, including
-- dates in the past (what did we believe when we sent that payment?) and in the future (which rule will
-- apply to this warehoused payment?).
--
-- EXCLUDE constraints make overlapping versions impossible: for one key there is at most one row on
-- any given day. They need btree_gist so that "=" on plain columns can share a GiST index with "&&"
-- (overlaps) on the date range.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

-- A release of reference data, like a monthly BIC directory file or an approved routing change.
-- Every row below records the release that introduced it.
CREATE TABLE release (
    version      VARCHAR(16)  PRIMARY KEY CHECK (version ~ '^v[0-9]{4}\.[0-9]{2}\.[0-9]+$'),
    published_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_by VARCHAR(64)  NOT NULL,
    -- Four-eyes principle: whoever approves a change must not be whoever made it.
    approved_by  VARCHAR(64)  NOT NULL CHECK (approved_by <> published_by),
    description  VARCHAR(500) NOT NULL
);

-- -----------------------------------------------------------------------------
-- BIC directory (ISO 9362): which institutions exist, and can we exchange messages with them.
-- -----------------------------------------------------------------------------
CREATE TABLE bic_directory (
    id               BIGSERIAL    PRIMARY KEY,
    bic              CHAR(11)     NOT NULL CHECK (bic ~ '^[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}[A-Z0-9]{3}$'),
    institution_name VARCHAR(140) NOT NULL,
    country_code     CHAR(2)      NOT NULL,
    city             VARCHAR(35)  NOT NULL,
    -- CONNECTED     = live on the network and our relationship (RMA) is authorised: we may send to it.
    -- PENDING_RMA   = live, but we have no authorised relationship yet: only reachable via someone else.
    -- NOT_CONNECTED = not on the network at all (a "non-connected BIC"): only reachable via a correspondent.
    connectivity     VARCHAR(16)  NOT NULL CHECK (connectivity IN ('CONNECTED', 'PENDING_RMA', 'NOT_CONNECTED')),
    -- Currencies the institution clears (informational).
    currencies       CHAR(3)[]    NOT NULL DEFAULT '{}',
    valid            DATERANGE    NOT NULL CHECK (NOT isempty(valid) AND NOT lower_inf(valid)),
    release_version  VARCHAR(16)  NOT NULL REFERENCES release (version),
    -- The country is part of the BIC (characters 5–6); the two must agree.
    CHECK (country_code = substr(bic, 5, 2)),
    EXCLUDE USING gist (bic WITH =, valid WITH &&)
);

-- -----------------------------------------------------------------------------
-- IBAN registry (ISO 13616): per country, how long an IBAN is and how its BBAN is structured.
-- bban_parts is an ordered list of {name, role, length, charset}; charset uses the registry's
-- notation n = digits, a = upper-case letters, c = letters or digits.
-- -----------------------------------------------------------------------------
CREATE TABLE iban_format (
    id              BIGSERIAL   PRIMARY KEY,
    country_code    CHAR(2)     NOT NULL,
    iban_length     SMALLINT    NOT NULL CHECK (iban_length BETWEEN 15 AND 34),
    bban_parts      JSONB       NOT NULL CHECK (jsonb_typeof(bban_parts) = 'array'),
    valid           DATERANGE   NOT NULL CHECK (NOT isempty(valid) AND NOT lower_inf(valid)),
    release_version VARCHAR(16) NOT NULL REFERENCES release (version),
    EXCLUDE USING gist (country_code WITH =, valid WITH &&)
);

-- National bank identifier inside the IBAN → BIC (what SWIFTRef's "IBAN Plus" directory provides).
-- Lets us check that the IBAN really belongs to the creditor agent the payment is addressed to.
CREATE TABLE iban_bank_id (
    id              BIGSERIAL   PRIMARY KEY,
    country_code    CHAR(2)     NOT NULL,
    bank_id         VARCHAR(12) NOT NULL,
    bic             CHAR(11)    NOT NULL,
    valid           DATERANGE   NOT NULL CHECK (NOT isempty(valid) AND NOT lower_inf(valid)),
    release_version VARCHAR(16) NOT NULL REFERENCES release (version),
    EXCLUDE USING gist (country_code WITH =, bank_id WITH =, valid WITH &&)
);

-- -----------------------------------------------------------------------------
-- Settlement schemes: per currency, the payment system it settles in, the business-day calendar,
-- and our cut-off for customer payments. The cut-off is a wall-clock time IN THE SCHEME'S TIME ZONE
-- (TARGET2 closes on Frankfurt time, whatever the time is in Kuala Lumpur), so daylight saving is
-- handled by converting at the moment of use, never by storing a converted time.
-- -----------------------------------------------------------------------------
CREATE TABLE settlement_scheme (
    id              BIGSERIAL   PRIMARY KEY,
    currency        CHAR(3)     NOT NULL,
    scheme_name     VARCHAR(32) NOT NULL,
    calendar_code   VARCHAR(16) NOT NULL,
    time_zone       VARCHAR(40) NOT NULL,
    cutoff_time     TIME        NOT NULL,
    valid           DATERANGE   NOT NULL CHECK (NOT isempty(valid) AND NOT lower_inf(valid)),
    release_version VARCHAR(16) NOT NULL REFERENCES release (version),
    EXCLUDE USING gist (currency WITH =, valid WITH &&)
);

-- Non-business days other than Saturday and Sunday. A holiday is a fact about one date, so the
-- date itself is the "effective date".
CREATE TABLE holiday (
    calendar_code   VARCHAR(16)  NOT NULL,
    holiday_date    DATE         NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    release_version VARCHAR(16)  NOT NULL REFERENCES release (version),
    PRIMARY KEY (calendar_code, holiday_date)
);

-- -----------------------------------------------------------------------------
-- Routing rules: (creditor agent, currency) → which account we settle on and who gets our message.
--   destination  = 'XXXXXXXX*' (an institution: BIC8 prefix, any branch) or '*' (any institution)
--   method       = DIRECT  we send to the creditor agent; we settle on our bilateral account with them
--                  SERIAL  we send to next_hop (a correspondent), who passes it on; settle with next_hop
--                  COVER   we send the pacs.008 to the creditor agent, and the money separately via
--                          next_hop (pacs.009 COV, from M5); settle with next_hop
--                  RTGS    we send to the creditor agent through the domestic RTGS system; settle on
--                          our central bank account
--   priority     = lowest number wins among the rules that match and are valid on the value date
-- -----------------------------------------------------------------------------
CREATE TABLE routing_rule (
    rule_id            VARCHAR(16)  PRIMARY KEY CHECK (rule_id ~ '^R-[0-9]{3,}$'),
    destination        VARCHAR(9)   NOT NULL CHECK (destination ~ '^([A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}\*|\*)$'),
    currency           CHAR(3)      NOT NULL,
    method             VARCHAR(8)   NOT NULL CHECK (method IN ('DIRECT', 'SERIAL', 'COVER', 'RTGS')),
    next_hop_bic       CHAR(11),
    -- A ledger account number of THIS bank (nostro, vostro or central bank account). Reference data
    -- does not read the ledger; the payment hub resolves and checks the account when it uses the rule.
    settlement_account VARCHAR(34)  NOT NULL,
    priority           SMALLINT     NOT NULL CHECK (priority BETWEEN 1 AND 999),
    valid              DATERANGE    NOT NULL CHECK (NOT isempty(valid) AND NOT lower_inf(valid)),
    release_version    VARCHAR(16)  NOT NULL REFERENCES release (version),
    -- Serial and cover routes need the correspondent; direct and RTGS routes go to the creditor agent.
    CHECK ((method IN ('SERIAL', 'COVER')) = (next_hop_bic IS NOT NULL)),
    -- Two rules with the same key and priority on the same day would make routing ambiguous.
    EXCLUDE USING gist (destination WITH =, currency WITH =, priority WITH =, valid WITH &&)
);

CREATE INDEX routing_rule_lookup_idx ON routing_rule (currency, priority);

-- Idempotency-Key for reference data writes (rule 5): key → the rule it created.
CREATE TABLE idempotency_key (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    request_hash    CHAR(64)     NOT NULL,
    rule_id         VARCHAR(16)  NOT NULL REFERENCES routing_rule (rule_id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- History is never rewritten.
--   * releases, holidays and idempotency records are append-only;
--   * effective-dated rows may only be END-DATED: the upper bound may move earlier (or be set for
--     the first time), nothing else may change, and rows are never deleted.
-- -----------------------------------------------------------------------------
CREATE FUNCTION forbid_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'refdata.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE FUNCTION allow_end_dating_only() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'refdata.% rows are never deleted: end-date them instead', TG_TABLE_NAME
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (to_jsonb(NEW) - 'valid') IS DISTINCT FROM (to_jsonb(OLD) - 'valid')
       OR lower(NEW.valid) IS DISTINCT FROM lower(OLD.valid)
       OR NOT (NEW.valid <@ OLD.valid) THEN
        RAISE EXCEPTION 'refdata.% rows can only be end-dated (shorten the validity), not changed', TG_TABLE_NAME
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER release_append_only BEFORE UPDATE OR DELETE ON release
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER holiday_append_only BEFORE UPDATE OR DELETE ON holiday
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER idempotency_key_append_only BEFORE UPDATE OR DELETE ON idempotency_key
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER bic_directory_end_dating_only BEFORE UPDATE OR DELETE ON bic_directory
    FOR EACH ROW EXECUTE FUNCTION allow_end_dating_only();
CREATE TRIGGER iban_format_end_dating_only BEFORE UPDATE OR DELETE ON iban_format
    FOR EACH ROW EXECUTE FUNCTION allow_end_dating_only();
CREATE TRIGGER iban_bank_id_end_dating_only BEFORE UPDATE OR DELETE ON iban_bank_id
    FOR EACH ROW EXECUTE FUNCTION allow_end_dating_only();
CREATE TRIGGER settlement_scheme_end_dating_only BEFORE UPDATE OR DELETE ON settlement_scheme
    FOR EACH ROW EXECUTE FUNCTION allow_end_dating_only();
CREATE TRIGGER routing_rule_end_dating_only BEFORE UPDATE OR DELETE ON routing_rule
    FOR EACH ROW EXECUTE FUNCTION allow_end_dating_only();
