-- =============================================================================
-- Shared reference data, identical in every bank's copy: the simulator's equivalent of the BIC
-- directory, IBAN registry and calendar files a bank loads from its data vendor.
-- Routing rules are bank-specific; each bank's are loaded from its profile (minipay.seed.routing-rules).
--
-- Every institution is fictional. BICs and IBANs are format-valid (IBAN check digits pass mod-97).
-- =============================================================================

INSERT INTO release (version, published_at, published_by, approved_by, description) VALUES
    ('v2024.01.1', '2024-01-02 00:00:00+00', 'r.lim',  'a.wong', 'Initial load: BIC directory, IBAN registry, settlement schemes, routing'),
    ('v2025.05.2', '2025-05-20 01:00:00+00', 'r.lim',  'a.wong', 'Bank C EUR moves from its own EUR account to serial via Bank B'),
    ('v2025.10.1', '2025-10-15 01:00:00+00', 'j.tan',  'a.wong', 'Holiday calendars 2026'),
    ('v2026.10.1', '2026-10-01 01:00:00+00', 'j.tan',  'r.lim',  'Holiday calendars 2027; Berlin branch renamed'),
    ('v2026.11.1', '2026-11-18 01:00:00+00', 'r.lim',  'a.wong', 'MiniPay Bank Singapore joins the directory (RMA pending)'),
    ('v2026.11.3', '2026-11-20 00:00:00+00', 'r.lim',  'a.wong', 'EUR to Paris switches from serial via Bank B to cover from 2026-12-01');

-- -----------------------------------------------------------------------------
-- BIC directory
-- -----------------------------------------------------------------------------
INSERT INTO bic_directory (bic, institution_name, country_code, city, connectivity, currencies, valid, release_version) VALUES
    ('MNPAMYKLXXX', 'MiniPay Bank Malaysia Berhad',              'MY', 'Kuala Lumpur',      'CONNECTED',     '{MYR,EUR,GBP}', '[2024-01-01,)',           'v2024.01.1'),
    ('MNPMMYKLXXX', 'MiniPay Mutual Berhad',                     'MY', 'Kuala Lumpur',      'CONNECTED',     '{MYR}',         '[2024-01-01,)',           'v2024.01.1'),
    ('MNPBDEFFXXX', 'MiniPay Bank Deutschland AG',               'DE', 'Frankfurt am Main', 'CONNECTED',     '{EUR}',         '[2024-01-01,)',           'v2024.01.1'),
    -- Effective dating in action: the branch was renamed on 2026-10-02. Both names stay on record.
    ('MNPBDEFF100', 'MiniPay Bank Deutschland AG - Filiale Berlin', 'DE', 'Berlin',         'CONNECTED',     '{EUR}',         '[2024-01-01,2026-10-02)', 'v2024.01.1'),
    ('MNPBDEFF100', 'MiniPay Bank Deutschland AG - Berlin',      'DE', 'Berlin',            'CONNECTED',     '{EUR}',         '[2026-10-02,)',           'v2026.10.1'),
    ('MNPCGB2LXXX', 'MiniPay Bank UK plc',                       'GB', 'London',            'CONNECTED',     '{GBP,EUR}',     '[2024-01-01,)',           'v2024.01.1'),
    ('MNPCGB2L200', 'MiniPay Bank UK plc - Manchester',          'GB', 'Manchester',        'CONNECTED',     '{GBP}',         '[2024-01-01,)',           'v2024.01.1'),
    ('MNPDBEBBXXX', 'MiniPay Banque Belgique SA',                'BE', 'Brussels',          'CONNECTED',     '{EUR}',         '[2024-01-01,)',           'v2024.01.1'),
    ('MNPEFRPPXXX', 'MiniPay Banque Paris SA',                   'FR', 'Paris',             'CONNECTED',     '{EUR}',         '[2024-01-01,)',           'v2024.01.1'),
    ('MNPUUS33XXX', 'MiniPay Bank USA N.A.',                     'US', 'New York',          'CONNECTED',     '{USD}',         '[2024-01-01,)',           'v2024.01.1'),
    ('MNPSSGSGXXX', 'MiniPay Bank Singapore Ltd',                'SG', 'Singapore',         'PENDING_RMA',   '{SGD,USD}',     '[2026-11-18,)',           'v2026.11.1'),
    ('MNPJJPJTXXX', 'MiniPay Bank Japan KK',                     'JP', 'Tokyo',             'NOT_CONNECTED', '{JPY}',         '[2024-01-01,)',           'v2024.01.1'),
    -- A BIC scheduled to disappear: after 2026-12-31 it no longer exists, and payments to it fail RC01.
    ('MNPXNL2AXXX', 'MiniPay Bank Nederland NV',                 'NL', 'Amsterdam',         'NOT_CONNECTED', '{EUR}',         '[2024-01-01,2027-01-01)', 'v2024.01.1'),
    -- Simulated central banks: operators of the domestic RTGS systems, where our settlement accounts are.
    ('MNCBMYKLXXX', 'Simulated Central Bank - Malaysia (RTGS)',  'MY', 'Kuala Lumpur',      'CONNECTED',     '{MYR}',         '[2024-01-01,)',           'v2024.01.1'),
    ('MNCBDEFFXXX', 'Simulated Central Bank - Euro area (RTGS)', 'DE', 'Frankfurt am Main', 'CONNECTED',     '{EUR}',         '[2024-01-01,)',           'v2024.01.1'),
    ('MNCBGB2LXXX', 'Simulated Central Bank - UK (RTGS)',        'GB', 'London',            'CONNECTED',     '{GBP}',         '[2024-01-01,)',           'v2024.01.1');

-- -----------------------------------------------------------------------------
-- IBAN registry (structure as published in the SWIFT IBAN registry for these countries)
-- -----------------------------------------------------------------------------
INSERT INTO iban_format (country_code, iban_length, bban_parts, valid, release_version) VALUES
    ('DE', 22, '[{"name":"Bank code (BLZ)","role":"BANK","length":8,"charset":"n"},
                 {"name":"Account","role":"ACCOUNT","length":10,"charset":"n"}]', '[2024-01-01,)', 'v2024.01.1'),
    ('GB', 22, '[{"name":"Bank code","role":"BANK","length":4,"charset":"a"},
                 {"name":"Sort code","role":"BRANCH","length":6,"charset":"n"},
                 {"name":"Account","role":"ACCOUNT","length":8,"charset":"n"}]', '[2024-01-01,)', 'v2024.01.1'),
    ('BE', 16, '[{"name":"Bank code","role":"BANK","length":3,"charset":"n"},
                 {"name":"Account","role":"ACCOUNT","length":7,"charset":"n"},
                 {"name":"National check","role":"NATIONAL_CHECK","length":2,"charset":"n"}]', '[2024-01-01,)', 'v2024.01.1'),
    ('FR', 27, '[{"name":"Bank code","role":"BANK","length":5,"charset":"n"},
                 {"name":"Branch","role":"BRANCH","length":5,"charset":"n"},
                 {"name":"Account","role":"ACCOUNT","length":11,"charset":"c"},
                 {"name":"RIB key","role":"NATIONAL_CHECK","length":2,"charset":"n"}]', '[2024-01-01,)', 'v2024.01.1'),
    ('NL', 18, '[{"name":"Bank code","role":"BANK","length":4,"charset":"a"},
                 {"name":"Account","role":"ACCOUNT","length":10,"charset":"n"}]', '[2024-01-01,)', 'v2024.01.1');

INSERT INTO iban_bank_id (country_code, bank_id, bic, valid, release_version) VALUES
    ('DE', '50010060', 'MNPBDEFFXXX', '[2024-01-01,)', 'v2024.01.1'),
    ('DE', '10010010', 'MNPBDEFF100', '[2024-01-01,)', 'v2024.01.1'),
    ('GB', 'MNPC',     'MNPCGB2LXXX', '[2024-01-01,)', 'v2024.01.1'),
    ('BE', '096',      'MNPDBEBBXXX', '[2024-01-01,)', 'v2024.01.1'),
    ('FR', '30006',    'MNPEFRPPXXX', '[2024-01-01,)', 'v2024.01.1'),
    ('NL', 'MNPX',     'MNPXNL2AXXX', '[2024-01-01,2027-01-01)', 'v2024.01.1');

-- -----------------------------------------------------------------------------
-- Settlement schemes and customer-payment cut-offs (simulator values, modelled on each system's
-- published cut-off for customer payments). Times are local to the scheme.
-- -----------------------------------------------------------------------------
INSERT INTO settlement_scheme (currency, scheme_name, calendar_code, time_zone, cutoff_time, valid, release_version) VALUES
    ('EUR', 'T2 RTGS', 'TARGET', 'Europe/Berlin',      '17:00', '[2024-01-01,)', 'v2024.01.1'),
    ('GBP', 'CHAPS',   'GB-ENG', 'Europe/London',      '17:40', '[2024-01-01,)', 'v2024.01.1'),
    ('MYR', 'RENTAS',  'MY-KL',  'Asia/Kuala_Lumpur',  '18:00', '[2024-01-01,)', 'v2024.01.1'),
    ('USD', 'Fedwire', 'US-FED', 'America/New_York',   '18:00', '[2024-01-01,)', 'v2024.01.1');

-- -----------------------------------------------------------------------------
-- Holidays (Saturdays and Sundays are never business days and are not listed).
-- -----------------------------------------------------------------------------
INSERT INTO holiday (calendar_code, holiday_date, name, release_version) VALUES
    -- TARGET: the euro area's settlement calendar.
    ('TARGET', '2026-01-01', 'New Year''s Day', 'v2025.10.1'),
    ('TARGET', '2026-04-03', 'Good Friday', 'v2025.10.1'),
    ('TARGET', '2026-04-06', 'Easter Monday', 'v2025.10.1'),
    ('TARGET', '2026-05-01', 'Labour Day', 'v2025.10.1'),
    ('TARGET', '2026-12-25', 'Christmas Day', 'v2025.10.1'),
    ('TARGET', '2026-12-26', 'Christmas Holiday', 'v2025.10.1'),
    ('TARGET', '2027-01-01', 'New Year''s Day', 'v2026.10.1'),
    ('TARGET', '2027-03-26', 'Good Friday', 'v2026.10.1'),
    ('TARGET', '2027-03-29', 'Easter Monday', 'v2026.10.1'),
    ('TARGET', '2027-05-01', 'Labour Day', 'v2026.10.1'),
    ('TARGET', '2027-12-25', 'Christmas Day', 'v2026.10.1'),
    ('TARGET', '2027-12-26', 'Christmas Holiday', 'v2026.10.1'),
    -- England & Wales bank holidays (CHAPS does not settle on them). A holiday on a weekend gets a
    -- weekday "substitute day".
    ('GB-ENG', '2026-01-01', 'New Year''s Day', 'v2025.10.1'),
    ('GB-ENG', '2026-04-03', 'Good Friday', 'v2025.10.1'),
    ('GB-ENG', '2026-04-06', 'Easter Monday', 'v2025.10.1'),
    ('GB-ENG', '2026-05-04', 'Early May bank holiday', 'v2025.10.1'),
    ('GB-ENG', '2026-05-25', 'Spring bank holiday', 'v2025.10.1'),
    ('GB-ENG', '2026-08-31', 'Summer bank holiday', 'v2025.10.1'),
    ('GB-ENG', '2026-12-25', 'Christmas Day', 'v2025.10.1'),
    ('GB-ENG', '2026-12-28', 'Boxing Day (substitute day)', 'v2025.10.1'),
    ('GB-ENG', '2027-01-01', 'New Year''s Day', 'v2026.10.1'),
    ('GB-ENG', '2027-03-26', 'Good Friday', 'v2026.10.1'),
    ('GB-ENG', '2027-03-29', 'Easter Monday', 'v2026.10.1'),
    ('GB-ENG', '2027-05-03', 'Early May bank holiday', 'v2026.10.1'),
    ('GB-ENG', '2027-05-31', 'Spring bank holiday', 'v2026.10.1'),
    ('GB-ENG', '2027-08-30', 'Summer bank holiday', 'v2026.10.1'),
    ('GB-ENG', '2027-12-27', 'Christmas Day (substitute day)', 'v2026.10.1'),
    ('GB-ENG', '2027-12-28', 'Boxing Day (substitute day)', 'v2026.10.1'),
    -- Kuala Lumpur. Only the fixed-date holidays: moveable ones (Chinese New Year, Hari Raya,
    -- Deepavali, Wesak …) follow lunar or Islamic calendars and are gazetted year by year; a real
    -- bank receives them in each year's calendar release.
    ('MY-KL', '2026-01-01', 'New Year''s Day', 'v2025.10.1'),
    ('MY-KL', '2026-02-02', 'Federal Territory Day (replacement)', 'v2025.10.1'),
    ('MY-KL', '2026-05-01', 'Labour Day', 'v2025.10.1'),
    ('MY-KL', '2026-08-31', 'National Day', 'v2025.10.1'),
    ('MY-KL', '2026-09-16', 'Malaysia Day', 'v2025.10.1'),
    ('MY-KL', '2026-12-25', 'Christmas Day', 'v2025.10.1'),
    ('MY-KL', '2027-01-01', 'New Year''s Day', 'v2026.10.1'),
    ('MY-KL', '2027-02-01', 'Federal Territory Day', 'v2026.10.1'),
    ('MY-KL', '2027-08-31', 'National Day', 'v2026.10.1'),
    ('MY-KL', '2027-09-16', 'Malaysia Day', 'v2026.10.1'),
    -- Federal Reserve holidays. When one falls on a Saturday the Fed stays open the Friday before,
    -- so 2026-07-04 and 2027-06-19 / 2027-12-25 have no weekday holiday.
    ('US-FED', '2026-01-01', 'New Year''s Day', 'v2025.10.1'),
    ('US-FED', '2026-01-19', 'Birthday of Martin Luther King, Jr.', 'v2025.10.1'),
    ('US-FED', '2026-02-16', 'Washington''s Birthday', 'v2025.10.1'),
    ('US-FED', '2026-05-25', 'Memorial Day', 'v2025.10.1'),
    ('US-FED', '2026-06-19', 'Juneteenth National Independence Day', 'v2025.10.1'),
    ('US-FED', '2026-09-07', 'Labor Day', 'v2025.10.1'),
    ('US-FED', '2026-10-12', 'Columbus Day', 'v2025.10.1'),
    ('US-FED', '2026-11-11', 'Veterans Day', 'v2025.10.1'),
    ('US-FED', '2026-11-26', 'Thanksgiving Day', 'v2025.10.1'),
    ('US-FED', '2026-12-25', 'Christmas Day', 'v2025.10.1'),
    ('US-FED', '2027-01-01', 'New Year''s Day', 'v2026.10.1'),
    ('US-FED', '2027-01-18', 'Birthday of Martin Luther King, Jr.', 'v2026.10.1'),
    ('US-FED', '2027-02-15', 'Washington''s Birthday', 'v2026.10.1'),
    ('US-FED', '2027-05-31', 'Memorial Day', 'v2026.10.1'),
    ('US-FED', '2027-07-05', 'Independence Day (observed)', 'v2026.10.1'),
    ('US-FED', '2027-09-06', 'Labor Day', 'v2026.10.1'),
    ('US-FED', '2027-10-11', 'Columbus Day', 'v2026.10.1'),
    ('US-FED', '2027-11-11', 'Veterans Day', 'v2026.10.1'),
    ('US-FED', '2027-11-25', 'Thanksgiving Day', 'v2026.10.1');
