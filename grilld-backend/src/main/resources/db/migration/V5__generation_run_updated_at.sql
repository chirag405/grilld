-- Drives the resume sweep's staleness check (SS10.5): a run stuck at
-- IN_PROGRESS whose updated_at hasn't moved in a while is a candidate for
-- re-triggering. Touched on every Run Report rewrite, not just at the end.
ALTER TABLE generation_runs ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
