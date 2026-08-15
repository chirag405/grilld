-- Phase 5: Scale Calibrator (docs/product-and-architecture.md §4). The tier
-- is a hard complexity ceiling injected into every downstream specialist's
-- system prompt, and is user-visible/overridable ("We're building this as a
-- T1. Change?") - so it lives on the brief, not buried in a generation run.

ALTER TABLE project_briefs
    ADD COLUMN scale_tier TEXT CHECK (scale_tier IN ('T0', 'T1', 'T2', 'T3')),
    ADD COLUMN scale_tier_reasoning TEXT,
    ADD COLUMN scale_tier_overridden BOOLEAN NOT NULL DEFAULT false;
