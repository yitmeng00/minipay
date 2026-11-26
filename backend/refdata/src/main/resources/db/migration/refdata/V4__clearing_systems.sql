-- =============================================================================
-- M7: clearing and settlement systems (ADR-008).
--
-- A routing rule says HOW a payment reaches the creditor's bank. Since M7 three of those ways go through
-- a system run by a third party, and that party is on the network:
--
--   RTGS     real-time gross settlement in central bank money, one payment at a time (T2)
--   DNS      deferred net settlement: payments are collected in cycles, netted, and only the net
--            positions settle, on the RTGS (an ACH such as STEP2)
--   INSTANT  instant payments, 24/7, settled at once on prefunded positions in central bank money,
--            or rejected within a timeout (TIPS / RT1)
--
-- One system per kind and currency on a date. The system's operator is the bank our message goes to
-- (AppHdr/To); the creditor's bank stays InstdAgt. A currency whose RTGS is not listed here (MYR, GBP)
-- is not simulated: an RTGS route there still sends the pacs.008 straight to the creditor agent (M5).
-- =============================================================================

CREATE TABLE clearing_system (
    code            VARCHAR(8)   NOT NULL CHECK (code ~ '^[A-Z0-9]{2,8}$'),  -- SttlmInf/ClrSys/Prtry
    name            VARCHAR(40)  NOT NULL,
    kind            VARCHAR(8)   NOT NULL CHECK (kind IN ('RTGS', 'DNS', 'INSTANT')),
    currency        CHAR(3)      NOT NULL,
    operator_bic    CHAR(11)     NOT NULL,
    -- DNS only: the RTGS on which the net positions settle.
    settles_in      VARCHAR(8),
    time_zone       VARCHAR(40)  NOT NULL,
    -- RTGS: customer payment cut-off (wall-clock in time_zone); queued payments not settled by then are rejected.
    cutoff_time     TIME,
    -- DNS: the times at which a cycle closes, is netted and settled.
    cycle_times     TIME[],
    -- INSTANT: the creditor's bank must answer within this many seconds, or the payment is rejected (AB05).
    timeout_seconds SMALLINT     CHECK (timeout_seconds BETWEEN 1 AND 60),
    valid           DATERANGE    NOT NULL CHECK (NOT isempty(valid) AND NOT lower_inf(valid)),
    release_version VARCHAR(16)  NOT NULL REFERENCES release (version),
    CHECK ((kind = 'RTGS') = (cutoff_time IS NOT NULL)),
    CHECK ((kind = 'DNS') = (cycle_times IS NOT NULL AND settles_in IS NOT NULL)),
    CHECK ((kind = 'INSTANT') = (timeout_seconds IS NOT NULL)),
    EXCLUDE USING gist (code WITH =, valid WITH &&),
    EXCLUDE USING gist (kind WITH =, currency WITH =, valid WITH &&)
);

CREATE TRIGGER clearing_system_end_dating_only BEFORE UPDATE OR DELETE ON clearing_system
    FOR EACH ROW EXECUTE FUNCTION allow_end_dating_only();

-- Routing gains the two new ways. DNS and INSTANT go to the currency's system of that kind; RTGS does too
-- when the currency has a simulated RTGS. None of them names a next hop: the system is found by kind.
ALTER TABLE routing_rule DROP CONSTRAINT routing_rule_method_check;
ALTER TABLE routing_rule ADD CONSTRAINT routing_rule_method_check
    CHECK (method IN ('DIRECT', 'SERIAL', 'COVER', 'RTGS', 'DNS', 'INSTANT'));

INSERT INTO release (version, published_at, published_by, approved_by, description) VALUES
    ('v2026.11.9', '2026-11-25 01:00:00+00', 'r.lim', 'a.wong',
     'Euro clearing systems: T2 (RTGS), MiniPay Net (DNS) and MiniPay Instant, operated by the central bank');

-- The simulated euro-area central bank (MNCBDEFFXXX, already in the directory) operates all three. In
-- reality the ACH would be a private company (EBA CLEARING runs STEP2 and RT1) settling through T2's
-- ancillary system interface; one operator keeps the simulator small and changes nothing about netting.
INSERT INTO clearing_system (code, name, kind, currency, operator_bic, settles_in, time_zone, cutoff_time,
                             cycle_times, timeout_seconds, valid, release_version) VALUES
    ('T2',     'T2 (sim)',        'RTGS',    'EUR', 'MNCBDEFFXXX', NULL, 'Europe/Berlin', '17:00', NULL, NULL,
     '[2024-01-01,)', 'v2026.11.9'),
    ('MPNET',  'MiniPay Net',     'DNS',     'EUR', 'MNCBDEFFXXX', 'T2', 'Europe/Berlin', NULL,
     '{09:00,12:00,15:00,17:00}', NULL, '[2024-01-01,)', 'v2026.11.9'),
    ('MPINST', 'MiniPay Instant', 'INSTANT', 'EUR', 'MNCBDEFFXXX', NULL, 'Europe/Berlin', NULL, NULL, 10,
     '[2024-01-01,)', 'v2026.11.9');
