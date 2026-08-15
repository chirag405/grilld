-- discovery_sessions.scale_tier (from V1) was a Phase 1/2 placeholder for
-- where the Scale Calibrator's tier would eventually live. When Phase 5
-- actually built the Scale Calibrator, the tier landed on project_briefs
-- instead (V2__scale_tier.sql) - correctly, since ProjectBrief is the
-- single-source-of-truth brief record, not the interview-process record.
-- This column was never set by any code; dropping the dead duplicate rather
-- than leaving two "the" scale tier columns to go stale relative to each other.
ALTER TABLE discovery_sessions DROP COLUMN scale_tier;
