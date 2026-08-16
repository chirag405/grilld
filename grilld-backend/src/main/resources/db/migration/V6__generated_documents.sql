-- The actual content a generation run produced (product-and-architecture.md
-- §5's package tree) - previously discarded once a run finished (only the
-- output_ref *path* survived on agent_executions, never the content itself).
-- Needed for the Packager (§10.7) to have anything real to zip up.
CREATE TABLE generated_documents (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id      UUID NOT NULL REFERENCES generation_runs(id) ON DELETE CASCADE,
    path        TEXT NOT NULL,
    content     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_generated_documents_run_path ON generated_documents(run_id, path);
