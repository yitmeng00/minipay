-- M3: reference data decides WHEN a payment goes and WHICH WAY.
--   execution_date  = the business day we execute on: the requested date, moved to the next business
--                     day of the currency's calendar if it is a weekend or holiday. Warehoused payments
--                     wait for this date, not the requested one.
--   routing_rule_id = the routing rule (refdata) that chose the route, kept for audit:
--                     "why did this payment go via Bank B?" must be answerable later.
ALTER TABLE payment
    ADD COLUMN execution_date  DATE,
    ADD COLUMN routing_rule_id VARCHAR(16);

UPDATE payment SET execution_date = requested_exec_date WHERE execution_date IS NULL;

CREATE INDEX payment_due_idx ON payment (status, execution_date);
