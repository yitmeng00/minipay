-- =============================================================================
-- SIMULATED sanctions lists. Every entry here is fictional: the names, addresses, registration
-- numbers and references are invented for this learning project, and the "SIM" in every reference
-- says so. They imitate the shape of the real lists (OFAC SDN, EU consolidated list), not their
-- content. Shared by all banks (Flyway seed), like the BIC directory.
--
-- An entry is valid from the day it was listed; a delisted entry keeps its row, end-dated on the
-- day it was removed, so screenings of earlier dates can still be explained.
-- =============================================================================

INSERT INTO sanctions_list (code, name, authority) VALUES
    ('OFAC', 'OFAC SDN (sim)',        'US Treasury, Office of Foreign Assets Control (simulated)'),
    ('EU',   'EU consolidated (sim)', 'European Union, consolidated financial sanctions list (simulated)');

INSERT INTO list_snapshot (list_code, snapshot_date) VALUES
    ('OFAC', DATE '2026-11-20'),
    ('EU',   DATE '2026-11-20');

INSERT INTO sanctions_entry (entry_id, list_code, entry_type, name, aliases, country, address, registration,
                             date_of_birth, bic, program, valid, snapshot_date) VALUES
    -- OFAC (sim)
    ('SDN.SIM-20931', 'OFAC', 'ENTITY', 'NORTH BRIDGE CAPITAL HOLDINGS LTD', '{"NBC Holdings","Northbridge Cap."}',
        'CY', '14 Arch. Makariou III, Limassol', 'HE 418833', NULL, NULL, 'Program SIM-FIN',
        daterange('2025-09-02', NULL), '2026-11-20'),
    ('SDN.SIM-31877', 'OFAC', 'ENTITY', 'PACIFIC DELTA MINING CORP', '{"PDM Corp"}',
        'PH', 'Ayala Ave, Makati', 'SEC CS2011-0442', NULL, NULL, 'Program SIM-MIN',
        daterange('2023-06-19', NULL), '2026-11-20'),
    ('SDN.SIM-40210', 'OFAC', 'BANK', 'ORONTES TRADE BANK', '{"Orontes Bank for Trade"}',
        'SY', 'Baghdad St 7, Damascus', 'CR 22871', NULL, 'MNPZSYDAXXX', 'Program SIM-SYR',
        daterange('2024-05-15', NULL), '2026-11-20'),
    ('SDN.SIM-15520', 'OFAC', 'INDIVIDUAL', 'ZORAVAN, TEODOR', '{"Teo Zoravan","Teodor Zoravani"}',
        'CY', NULL, NULL, DATE '1968-03-11', NULL, 'Program SIM-FIN',
        daterange('2022-11-04', NULL), '2026-11-20'),
    -- Delisted on 1 Oct 2025: it no longer matches payments screened after that day.
    ('SDN.SIM-11804', 'OFAC', 'ENTITY', 'MERIDIAN SALT TRADING LLC', '{"Meridian Salt"}',
        'AE', 'Al Maktoum Rd, Dubai', 'DED 771203', NULL, NULL, 'Program SIM-GULF',
        daterange('2021-02-01', '2025-10-01'), '2026-11-20'),
    -- EU (sim)
    ('EU.SIM.4417', 'EU', 'ENTITY', 'OAKRIDGE INTERIORS TRADING FZE', '{"Oakridge Trading FZE","ORI Trading FZE"}',
        'AE', 'Plot S22, Jebel Ali Free Zone, Dubai', 'JAFZA licence 18-2231', NULL, NULL, 'Reg. (sim) 2026/118',
        daterange('2026-03-14', NULL), '2026-11-20'),
    ('EU.SIM.3902', 'EU', 'ENTITY', 'BALTIC AMBER SHIPPING CO', '{"BA Shipping","Baltic Amber Freight"}',
        'EE', 'Sadama 9, 10111 Tallinn', 'Reg. 10992817', NULL, NULL, 'Reg. (sim) 2026/233',
        daterange('2026-07-30', NULL), '2026-11-20'),
    ('EU.SIM.5120', 'EU', 'INDIVIDUAL', 'IVANOVA, SVETLANA PETROVNA', '{"Svetlana Ivanova"}',
        'RU', NULL, NULL, DATE '1971-05-02', NULL, 'Reg. (sim) 2024/41',
        daterange('2024-02-23', NULL), '2026-11-20'),
    ('EU.SIM.2288', 'EU', 'ENTITY', 'CRESCENT MARINE SERVICES LTD', '{"CMS Ltd"}',
        'PA', 'Calle 50, Panama City', 'Folio 155623', NULL, NULL, 'Reg. (sim) 2025/310',
        daterange('2025-12-11', NULL), '2026-11-20'),
    ('EU.SIM.6031', 'EU', 'ENTITY', 'HALVORSEN MARITIME AS', '{"Halvorsen Mar."}',
        'NO', 'Havnegata 3, Tromsø', 'Org.nr 987 120 554', NULL, NULL, 'Reg. (sim) 2026/170',
        daterange('2026-05-08', NULL), '2026-11-20');
