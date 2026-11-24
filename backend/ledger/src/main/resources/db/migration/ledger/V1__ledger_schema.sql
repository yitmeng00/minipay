-- =============================================================================
-- Ledger schema — double-entry, append-only.
--
-- Read docs/adr/ADR-002-ledger-design.md first. In short:
--   * journal_entry  = one business event (reserve funds, settle a payment, charge a fee ...)
--   * posting        = one debit or credit line of an entry. Append-only: never updated,
--                      never deleted. Mistakes are corrected with a REVERSAL entry.
--   * account_balance = running totals per account, updated in the SAME transaction as the
--                      postings, under a row lock. Always re-derivable from postings.
--   * reservation    = a hold on funds (FUNDS_RESERVED in the payment lifecycle).
--
-- Amounts are NUMERIC(19,4): 15 integer digits + 4 decimals. That covers every ISO 4217
-- currency (max 4 minor units, e.g. CLF/UYW) with room for very large balances. The
-- application rounds to the currency's own minor units before writing (see Money).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Accounts
-- -----------------------------------------------------------------------------
CREATE TABLE account (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 34 = maximum IBAN length (ISO 13616). Internal GL/nostro numbers fit too.
    account_number   VARCHAR(34)  NOT NULL UNIQUE,
    -- CUSTOMER   : a client's account. A liability: money we owe the customer.
    -- NOSTRO     : "ours, with you" — our account held at another bank. An asset.
    -- VOSTRO     : "yours, with us" — another bank's account held here. A liability.
    -- SUSPENSE   : technical holding/control accounts (e.g. the holds control account).
    -- FEE_INCOME : profit & loss — fees we earned.
    account_type     VARCHAR(16)  NOT NULL
        CHECK (account_type IN ('CUSTOMER', 'NOSTRO', 'VOSTRO', 'SUSPENSE', 'FEE_INCOME')),
    currency         CHAR(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    -- 140 = ISO 20022 Max140Text, the length allowed for party names in pacs.008.
    owner_name       VARCHAR(140) NOT NULL,
    -- For NOSTRO/VOSTRO: the BIC of the other bank in the relationship.
    counterparty_bic VARCHAR(11)
        CHECK (counterparty_bic ~ '^[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$'),
    status           VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    allow_overdraft  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- A nostro or vostro only makes sense with a counterparty bank.
    CONSTRAINT account_counterparty_required
        CHECK (account_type NOT IN ('NOSTRO', 'VOSTRO') OR counterparty_bic IS NOT NULL)
);

CREATE INDEX account_type_idx ON account (account_type, currency);

-- -----------------------------------------------------------------------------
-- Balances — one row per account. "available" is derived, never written.
--
-- Sign convention: balances are stored in the account's NORMAL direction, so a
-- healthy balance is positive for every account type:
--   credit-normal (CUSTOMER, VOSTRO, FEE_INCOME): booked = credits - debits
--   debit-normal  (NOSTRO, SUSPENSE)            : booked = debits - credits
-- reserved = funds earmarked by active holds; available = booked - reserved.
-- -----------------------------------------------------------------------------
CREATE TABLE account_balance (
    account_id BIGINT        PRIMARY KEY REFERENCES account (id),
    currency   CHAR(3)       NOT NULL,
    booked     NUMERIC(19,4) NOT NULL DEFAULT 0,
    reserved   NUMERIC(19,4) NOT NULL DEFAULT 0,
    available  NUMERIC(19,4) GENERATED ALWAYS AS (booked - reserved) STORED,
    -- Bumped on every change. Lets readers detect a moving balance; writers use row locks.
    version    BIGINT        NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- Journal entries
-- -----------------------------------------------------------------------------
CREATE TABLE journal_entry (
    id                UUID         PRIMARY KEY,
    -- The client's Idempotency-Key. A retry with the same key returns the original result.
    idempotency_key   VARCHAR(128) NOT NULL UNIQUE,
    -- SHA-256 of the canonical request: same key + different request = HTTP 422.
    request_hash      CHAR(64)     NOT NULL,
    -- Payment hub's payment id (M2). Null for non-payment entries (funding, adjustments).
    payment_id        UUID,
    entry_type        VARCHAR(10)  NOT NULL
        CHECK (entry_type IN ('RESERVE', 'RELEASE', 'SETTLE', 'FEE', 'REVERSAL')),
    reverses_entry_id UUID         REFERENCES journal_entry (id),
    -- Value date: the date the money is "worth" being moved (interest/settlement date).
    -- It is a DATE, not a timestamp — it has no time zone.
    value_date        DATE         NOT NULL,
    narrative         VARCHAR(140),
    -- Booking time. now() is the transaction start, so all postings of an entry share it.
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT journal_entry_reversal_link
        CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL))
);

-- An entry can be reversed at most once. This also closes the race where two
-- concurrent reversal requests both pass the "already reversed?" check.
CREATE UNIQUE INDEX journal_entry_reversed_once ON journal_entry (reverses_entry_id)
    WHERE reverses_entry_id IS NOT NULL;
CREATE INDEX journal_entry_payment_idx ON journal_entry (payment_id) WHERE payment_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- Postings — append-only, partitioned by month of created_at.
--
-- Why partition? Postings are the largest, fastest-growing table in any ledger.
-- Monthly partitions keep indexes small, let old months be detached/archived, and
-- make "statement for date X" touch one partition. The partition key must be part
-- of the primary key, hence PRIMARY KEY (id, created_at).
-- (PG16 does not support identity columns on partitioned tables, so: BIGSERIAL.)
-- -----------------------------------------------------------------------------
CREATE TABLE posting (
    id               BIGSERIAL,
    journal_entry_id UUID          NOT NULL REFERENCES journal_entry (id),
    account_id       BIGINT        NOT NULL REFERENCES account (id),
    direction        CHAR(1)       NOT NULL CHECK (direction IN ('D', 'C')),
    -- Always positive; the direction says which way it goes.
    amount           NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency         CHAR(3)       NOT NULL,
    -- BOOKED   : real movement of money, changes the booked balance.
    -- RESERVED : an earmark (hold); changes only the reserved balance.
    balance_type     VARCHAR(8)    NOT NULL CHECK (balance_type IN ('BOOKED', 'RESERVED')),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX posting_account_time_idx ON posting (account_id, created_at);
CREATE INDEX posting_entry_idx ON posting (journal_entry_id);

-- Creates the partition for the month containing p_month (idempotent). In production
-- a scheduled job would call this ahead of time; the DEFAULT partition is a safety net.
CREATE FUNCTION create_posting_partition(p_month DATE) RETURNS VOID
LANGUAGE plpgsql AS $$
DECLARE
    v_start DATE := date_trunc('month', p_month)::DATE;
    v_end   DATE := (date_trunc('month', p_month) + INTERVAL '1 month')::DATE;
    v_name  TEXT := format('posting_%s', to_char(v_start, 'YYYY_MM'));
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS ledger.%I PARTITION OF ledger.posting FOR VALUES FROM (%L) TO (%L)',
        v_name, v_start::TIMESTAMPTZ, v_end::TIMESTAMPTZ);
END;
$$;

SELECT create_posting_partition(m::DATE)
FROM generate_series(DATE '2026-01-01', DATE '2028-12-01', INTERVAL '1 month') AS m;

CREATE TABLE posting_default PARTITION OF posting DEFAULT;

-- -----------------------------------------------------------------------------
-- Reservations (holds). Mutable status, but the money side is always postings.
-- -----------------------------------------------------------------------------
CREATE TABLE reservation (
    id               UUID          PRIMARY KEY,
    account_id       BIGINT        NOT NULL REFERENCES account (id),
    amount           NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency         CHAR(3)       NOT NULL,
    payment_id       UUID,
    reason           VARCHAR(140),
    -- ACTIVE -> RELEASED (payment failed/cancelled) or ACTIVE -> SETTLED (payment booked).
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED', 'SETTLED')),
    reserve_entry_id UUID          NOT NULL UNIQUE REFERENCES journal_entry (id),
    release_entry_id UUID          UNIQUE REFERENCES journal_entry (id),
    settle_entry_id  UUID          UNIQUE REFERENCES journal_entry (id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT reservation_release_link CHECK ((status = 'ACTIVE') = (release_entry_id IS NULL)),
    CONSTRAINT reservation_settle_link  CHECK ((status = 'SETTLED') = (settle_entry_id IS NOT NULL))
);

CREATE INDEX reservation_active_idx ON reservation (account_id) WHERE status = 'ACTIVE';

-- =============================================================================
-- Database-level guarantees. The application already enforces these rules; the
-- database enforces them AGAIN so that no bug, script or manual fix can break them.
-- =============================================================================

-- Rule: postings and journal entries are append-only.
CREATE FUNCTION forbid_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger.% is append-only: % is not allowed. Post a REVERSAL entry instead.',
        TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER posting_append_only
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER journal_entry_no_truncate
    BEFORE TRUNCATE ON journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- Rule: a posting is in its account's currency.
CREATE FUNCTION check_posting_currency() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_ccy CHAR(3);
BEGIN
    SELECT currency INTO v_ccy FROM ledger.account WHERE id = NEW.account_id;
    IF v_ccy IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'posting currency % does not match account % currency %',
            NEW.currency, NEW.account_id, v_ccy
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER posting_currency_matches_account
    BEFORE INSERT ON posting
    FOR EACH ROW EXECUTE FUNCTION check_posting_currency();

-- Rule: every journal entry balances — per currency and per balance type,
-- sum(debits) = sum(credits) — and has at least two postings.
--
-- The check must run at COMMIT, not per row: while the application is still inserting
-- the postings of an entry, the entry is legitimately half-written. A DEFERRABLE
-- INITIALLY DEFERRED constraint trigger runs at commit time and aborts the whole
-- transaction if any entry it touched is unbalanced.
CREATE FUNCTION assert_entry_balanced(p_entry_id UUID) RETURNS VOID
LANGUAGE plpgsql AS $$
DECLARE
    v_count INT;
    v_bad   RECORD;
BEGIN
    SELECT count(*) INTO v_count FROM ledger.posting WHERE journal_entry_id = p_entry_id;
    IF v_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s); double entry needs at least 2', p_entry_id, v_count
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT currency, balance_type,
           sum(CASE direction WHEN 'D' THEN amount ELSE -amount END) AS diff
      INTO v_bad
      FROM ledger.posting
     WHERE journal_entry_id = p_entry_id
     GROUP BY currency, balance_type
    HAVING sum(CASE direction WHEN 'D' THEN amount ELSE -amount END) <> 0
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: % % debits - credits = %',
            p_entry_id, v_bad.currency, v_bad.balance_type, v_bad.diff
            USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION check_entry_balanced_from_posting() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ledger.assert_entry_balanced(NEW.journal_entry_id);
    RETURN NULL;
END;
$$;

CREATE FUNCTION check_entry_balanced_from_entry() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ledger.assert_entry_balanced(NEW.id);
    RETURN NULL;
END;
$$;

-- Fires for postings (catches unbalanced lines) ...
CREATE CONSTRAINT TRIGGER posting_entry_balanced
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balanced_from_posting();

-- ... and for entries (catches an entry committed with no postings at all).
CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balanced_from_entry();
