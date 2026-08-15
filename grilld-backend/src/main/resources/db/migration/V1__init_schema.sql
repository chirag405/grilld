-- Grilld — full canonical schema (Phase 1).
-- Spring Boot / Flyway is the sole owner of this schema (decisions-and-technical-architecture.md §11.2).
-- The Python AI service never migrates or writes here directly; it only owns its own
-- LangGraph checkpointer tables (added separately, same DB instance, in the AI service's phase).
--
-- Written in one pass per product-and-architecture.md §12's build-order note: the full
-- data model up front, not incrementally per later phase.

-- ============================================================================
-- Users & auth
-- ============================================================================

CREATE TABLE users (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email            TEXT NOT NULL UNIQUE,
    google_id        TEXT NOT NULL UNIQUE, -- Google OAuth subject; free-credit signup is gated on this (decisions-and-technical-architecture.md, anti-abuse)
    plan             TEXT NOT NULL DEFAULT 'FREE'
                         CHECK (plan IN ('FREE', 'STARTER', 'BUILDER', 'PRO', 'TEAM')),
    credits_balance  INTEGER NOT NULL DEFAULT 60, -- free signup grant (product-and-architecture.md §10)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- Interrogation engine (interrogation-engine.md §2, §11)
-- ============================================================================

CREATE TABLE discovery_sessions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    raw_idea     TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE', 'READY_FOR_GENERATION', 'COMPLETED', 'ABANDONED')),
    scale_tier   TEXT CHECK (scale_tier IN ('T0', 'T1', 'T2', 'T3')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
    -- No `current_round` column: the round-based interrogation design (product-and-architecture.md §2)
    -- was superseded by the dynamic slot graph before any code was written. `turns` (below) replaces it.
);

CREATE INDEX idx_discovery_sessions_user_id ON discovery_sessions(user_id);

CREATE TABLE interview_answers (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id   UUID NOT NULL REFERENCES discovery_sessions(id) ON DELETE CASCADE,
    round        INTEGER, -- retained for episodic-log display only; not used for control flow (superseded by turns)
    question_text TEXT NOT NULL,
    answer_text  TEXT,
    is_assumption BOOLEAN NOT NULL DEFAULT false,
    topic_key    TEXT,
    asked_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_interview_answers_session_id ON interview_answers(session_id);

CREATE TABLE slots (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES discovery_sessions(id) ON DELETE CASCADE,
    slot_key        TEXT NOT NULL,
    description     TEXT NOT NULL,
    origin          TEXT NOT NULL CHECK (origin IN ('SEED', 'DERIVED', 'PROBE')),
    status          TEXT NOT NULL DEFAULT 'OPEN'
                        CHECK (status IN ('OPEN', 'FILLED', 'ASSUMED', 'WAIVED', 'BLOCKED')),
    importance      INTEGER NOT NULL CHECK (importance BETWEEN 1 AND 5),
    value           TEXT,
    confidence      DOUBLE PRECISION CHECK (confidence BETWEEN 0 AND 1),
    parent_slot_key TEXT,
    depth           INTEGER NOT NULL DEFAULT 0,
    unlocks         TEXT[] NOT NULL DEFAULT '{}',
    evidence_ref    TEXT,
    created_at_turn INTEGER NOT NULL,
    filled_at_turn  INTEGER
);

CREATE UNIQUE INDEX idx_slots_session_key ON slots(session_id, slot_key);

CREATE TABLE turns (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES discovery_sessions(id) ON DELETE CASCADE,
    turn_number     INTEGER NOT NULL,
    question_text   TEXT,
    technique       TEXT CHECK (technique IN (
                        'FREE_ELICITATION', 'LADDERING', 'CONCRETIZATION', 'CONTRAST_TRIADIC',
                        'ASSUMPTION_SURFACING', 'CONTRADICTION_RESOLUTION', 'SCENARIO_PROJECTION',
                        'EXPERTISE_PROBE'
                     )),
    targets_slots   TEXT[] NOT NULL DEFAULT '{}',
    answer_text     TEXT,
    input_mode      TEXT CHECK (input_mode IN ('voice_primary', 'chips', 'number', 'text')),
    facts_extracted JSONB NOT NULL DEFAULT '[]',
    slots_spawned   TEXT[] NOT NULL DEFAULT '{}',
    slots_waived    TEXT[] NOT NULL DEFAULT '{}',
    tokens_in       INTEGER,
    tokens_out      INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_turns_session_number ON turns(session_id, turn_number);

CREATE TABLE expertise_profiles (
    session_id         UUID PRIMARY KEY REFERENCES discovery_sessions(id) ON DELETE CASCADE,
    level               INTEGER NOT NULL CHECK (level BETWEEN 1 AND 5),
    domains             JSONB NOT NULL DEFAULT '{}',
    named_technologies  TEXT[] NOT NULL DEFAULT '{}',
    known_gaps          TEXT[] NOT NULL DEFAULT '{}',
    updated_at_turn     INTEGER NOT NULL
);

CREATE TABLE rubric_evaluations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID NOT NULL REFERENCES discovery_sessions(id) ON DELETE CASCADE,
    at_turn     INTEGER NOT NULL,
    scores      JSONB NOT NULL, -- per-dimension FAIL/BORDERLINE/PASS (decisions-and-technical-architecture.md §11.4)
    open_gaps   JSONB NOT NULL DEFAULT '[]',
    verdict     TEXT NOT NULL CHECK (verdict IN ('accept', 'probe_further')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rubric_evaluations_session_id ON rubric_evaluations(session_id);

CREATE TABLE slot_waives (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id              UUID NOT NULL REFERENCES discovery_sessions(id) ON DELETE CASCADE,
    slot_key                TEXT NOT NULL,
    tier                    TEXT NOT NULL CHECK (tier IN ('HARD_WAIVE', 'SOFT_DEPRIORITIZE')),
    justifying_quote        TEXT NOT NULL,
    at_turn                 INTEGER NOT NULL,
    was_reversed            BOOLEAN NOT NULL DEFAULT false,
    reversed_at_turn        INTEGER,
    was_manually_restored   BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_slot_waives_session_id ON slot_waives(session_id);

-- ============================================================================
-- Project brief (canonical interrogation output)
-- ============================================================================

CREATE TABLE project_briefs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID NOT NULL REFERENCES discovery_sessions(id) ON DELETE CASCADE,
    brief_json          JSONB NOT NULL,
    compacted_summary   TEXT,
    rubric_scores       JSONB,
    version             INTEGER NOT NULL DEFAULT 1, -- optimistic-lock column (decisions-and-technical-architecture.md §10.1, concurrent-tab-edit guard)
    finalized_at        TIMESTAMPTZ
);

CREATE INDEX idx_project_briefs_session_id ON project_briefs(session_id);

-- ============================================================================
-- Generation runs & agent orchestration (decisions-and-technical-architecture.md §10, §11)
-- ============================================================================

CREATE TABLE generation_runs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brief_id         UUID NOT NULL REFERENCES project_briefs(id) ON DELETE CASCADE,
    status           TEXT NOT NULL DEFAULT 'IN_PROGRESS'
                         CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    credits_charged  INTEGER NOT NULL,
    run_report_md    TEXT, -- the Run Report side canvas; rewritten in place, never appended (§10.3)
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

CREATE INDEX idx_generation_runs_brief_id ON generation_runs(brief_id);
CREATE INDEX idx_generation_runs_status ON generation_runs(status); -- resume-sweep scan (§10.5)

CREATE TABLE agent_executions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id         UUID NOT NULL REFERENCES generation_runs(id) ON DELETE CASCADE,
    agent_name     TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'RUNNING'
                       CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    input_tokens   INTEGER,
    output_tokens  INTEGER,
    duration_ms    INTEGER,
    error          TEXT,
    output_ref     TEXT,
    narration      TEXT, -- curated 1-2 sentence summary, part of the agent's own structured output (§10.2)
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    heartbeat_at   TIMESTAMPTZ NOT NULL DEFAULT now() -- updated by inbound webhooks; staleness detection for resume-sweep (§10.5)
);

CREATE INDEX idx_agent_executions_run_id ON agent_executions(run_id);
CREATE INDEX idx_agent_executions_run_status ON agent_executions(run_id, status);

CREATE TABLE platform_settings (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Cost circuit breaker defaults (§10.6) - tune once real usage data exists.
INSERT INTO platform_settings (key, value) VALUES
    ('daily_spend_cap_usd', '25.00'),
    ('kill_switch_active', 'false');

-- ============================================================================
-- Packaging & phased delivery to the user
-- ============================================================================

CREATE TABLE packages (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id       UUID NOT NULL REFERENCES generation_runs(id) ON DELETE CASCADE,
    storage_url  TEXT,
    status       TEXT NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_packages_run_id ON packages(run_id);

CREATE TABLE package_documents (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_id    UUID NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
    doc_type      TEXT NOT NULL,
    path          TEXT NOT NULL,
    phase_number  INTEGER
);

CREATE INDEX idx_package_documents_package_id ON package_documents(package_id);

CREATE TABLE project_phases (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_id    UUID NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
    phase_number  INTEGER NOT NULL,
    title         TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'LOCKED'
                      CHECK (status IN ('LOCKED', 'ACTIVE', 'COMPLETE')),
    unlocked_at   TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_project_phases_package_number ON project_phases(package_id, phase_number);

-- ============================================================================
-- Billing (product-and-architecture.md §10; Lemon Squeezy webhooks wired in Phase 7)
-- ============================================================================

CREATE TABLE credit_transactions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    delta       INTEGER NOT NULL,
    reason      TEXT NOT NULL,
    run_id      UUID REFERENCES generation_runs(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_credit_transactions_user_id ON credit_transactions(user_id);
