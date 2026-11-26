-- The creditor's bank as the pacs.008 names it (CdtrAgt). Without it the tracker could only guess the
-- creditor's bank from the last hop, which is wrong while the payment is still on its way, e.g. held for
-- compliance at an intermediary (M6). Null for other message types, and for events recorded before.
ALTER TABLE tracker_event ADD COLUMN creditor_agent_bic CHAR(11);
