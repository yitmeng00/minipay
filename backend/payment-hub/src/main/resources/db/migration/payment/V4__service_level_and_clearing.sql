-- M7 (ADR-008): the customer's service level, and the clearing system a payment went through.
--   service_level    NORM, HIGH (urgent) or INST (instant): which routes it may take
--   clearing_system  T2, MPNET or MPINST when it went (or came) through a clearing system, else null
--   via_bic          that system's operator: our message went to it (AppHdr/To), or came from it; its
--                    status reports and returns are accepted like the next bank's
ALTER TABLE payment
    ADD COLUMN service_level   VARCHAR(4) NOT NULL DEFAULT 'NORM' CHECK (service_level IN ('NORM', 'HIGH', 'INST')),
    ADD COLUMN clearing_system VARCHAR(8),
    ADD COLUMN via_bic         VARCHAR(11),
    ADD CHECK ((clearing_system IS NULL) = (via_bic IS NULL));
