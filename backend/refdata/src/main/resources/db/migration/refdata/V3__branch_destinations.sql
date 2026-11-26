-- M5: a routing rule may target one branch (a full BIC11), not only a whole institution (BIC8 + '*').
-- Banks route branches differently all the time, e.g. one branch is paid by cover, the head office serially.
ALTER TABLE routing_rule DROP CONSTRAINT routing_rule_destination_check;
ALTER TABLE routing_rule ALTER COLUMN destination TYPE VARCHAR(11);
ALTER TABLE routing_rule ADD CONSTRAINT routing_rule_destination_check
    CHECK (destination ~ '^([A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3}|\*)|\*)$');
