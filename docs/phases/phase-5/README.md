# Phase 5 — The Specialist Roster

An interview no longer just ends - it produces something. Once a session concludes and gets a scale tier, a full generation run through 11 specialist agents (10 Deep Agents subagents + the Scale Calibrator's own graph) turns the brief into the blueprint package from `docs/product-and-architecture.md` §5.

## What this phase added

```
grilld-ai-service/
├── src/grilld_ai_service/
│   ├── tools.py                    — shared Tavily web_search tool
│   ├── scale_calibrator/           — its own graph (not a subagent), like rubric/
│   │   ├── schemas.py, prompt.py, graph.py
│   └── specialists/                — the 10 Deep Agents subagents
│       ├── market.py               — market_analyst, competition_analyst, strategy_agent
│       ├── tech.py                 — tech_architect, infra_agent
│       ├── diagram.py              — diagram_agent
│       ├── delivery.py             — roadmap_agent, skills_curator
│       ├── agent_kit.py            — agent_file_writer
│       └── audit.py                — consistency_auditor
│   └── graph.py                    — build_orchestrator(): registers the full roster,
│                                       replaces Phase 3's placeholder `ping` subagent
└── tests/integration_tests/test_graph.py  — real end-to-end run, all 10 agents, asserts
                                              every expected doc artifact exists

grilld-backend/src/main/java/com/grilld/backend/
├── generation/                     — new package
│   ├── GenerationRun.java, AgentExecution.java   — entities (generation_runs, agent_executions)
│   ├── GenerationRunRepository.java, AgentExecutionRepository.java
│   ├── GenerationService.java      — triggers a run, persists results
│   └── GenerationController.java   — POST /api/v1/sessions/{id}/generate
├── brief/ProjectBrief.java         — +scaleTier/scaleTierReasoning/scaleTierOverridden
├── aiservice/
│   ├── ScaleCalibrationResult.java, GenerationResult.java  — new contracts
│   └── HttpAiServiceClient.java    — +calibrateScale(), +generateBlueprint()
└── session/SessionController.java  — +POST/PUT .../scale-tier

grilld-backend/src/main/resources/db/migration/V2__scale_tier.sql
```

## Architecture

**Scale calibration (a separate step, before generation):**

```
POST /api/v1/sessions/{id}/scale-tier
  → SessionService.calibrateScale(): reads the finalized brief
  → HttpAiServiceClient.calibrateScale() → stateless /runs/wait → "scale_calibrator" graph
  → ProjectBrief.applyScaleCalibration(tier, reasoning) persisted
  → user can override: PUT /api/v1/sessions/{id}/scale-tier { "tier": "T2" }
      → ProjectBrief.overrideScaleTier() - keeps the original reasoning, flags overridden=true
```

**Generation (the full roster, one blocking call):**

```
POST /api/v1/sessions/{id}/generate
  → GenerationService.generate(): requires brief.scaleTier already set
  → creates a GenerationRun row (IN_PROGRESS)
  → HttpAiServiceClient.generateBlueprint(runId, briefJson, scaleTier)
      → ensureThreadExists(runId) - runId doubles as the LangGraph thread id
      → POST /threads/{runId}/runs/wait  {assistant_id: "orchestrator", input: {messages: [...]}}
      → Orchestrator writes /docs/PROJECT_BRIEF.md, then delegates to all 10 specialists
        in a fixed sequential order (market branch, then tech branch, then the join:
        roadmap -> skills -> agent-kit -> consistency audit)
      → every subagent's write_file calls land in the SAME shared graph state
        (state["files"]) - no explicit backend config needed, create_deep_agent's
        default StateBackend already shares it across the Orchestrator and every subagent
  → GenerationResult.files() parsed from the final state's "files" map
  → one AgentExecution row written per specialist (COMPLETED if its expected file exists,
    FAILED otherwise - see "Known limitations")
  → GenerationRun marked COMPLETED (or FAILED, on any exception - the run's mark-failed
    write is deliberately NOT wrapped in the same @Transactional boundary as the AI call,
    so it survives when the method exits via exception - see GenerationService's own comment)
```

## Key files and what they're responsible for

| File | Responsibility |
|---|---|
| `graph.py`'s `ORCHESTRATOR_SYSTEM_PROMPT` | Encodes the exact delegation order (§3.3's flow diagram) as an instruction, since true parallel fan-out orchestration is Phase 6 territory - see "Known limitations." |
| `specialists/*.py` | Each is just a `SubAgent` dict (name, description, system_prompt, tools) - no bespoke Python control flow, unlike interrogator/ or rubric/. |
| `GenerationService.AGENT_PRIMARY_OUTPUT` | The one place Spring "knows" what file each specialist should have produced - used only to populate placeholder `AgentExecution` rows from a single blocking response, not observed live. |
| `ScaleCalibrationResult` / `GenerationResult` | Mirror the Python-side structured contracts, same pattern as `InterrogatorTurnResult`/`RubricResult` from Phases 3-4. |

## Known limitations (deliberate, not oversights)

- **The roster runs fully sequentially, not the parallel fan-out `product-and-architecture.md` §3.3 diagrams.** True parallel dispatch (market branch alongside tech branch) needs coordination machinery (Send-style fan-out, tracking multiple in-flight subagent calls) this phase doesn't build - a latency optimization, not a correctness requirement, and natural to add alongside Phase 6's orchestration robustness work. Every agent that can ask the user must run sequentially anyway (§3.1); running the whole roster sequentially is simply the same rule applied everywhere for now.
- **"Ask the user" degrades to "record an assumption."** Tech Architect and Infra Agent are spec'd to interrupt and ask 1-2 targeted questions. Full interactive interrupt/resume through Spring is Phase 6 scope (Run Report + SSE + resume sweep) - explicitly scoped out this phase (asked and confirmed, not assumed). Both agents instead write reasoned assumptions to `/docs/ASSUMPTIONS.md`.
- **Diagram Agent produces Mermaid source only, not rendered SVG/PNG.** Rendering needs a Mermaid CLI/headless-browser step - a packaging-pipeline concern, natural to build alongside Phase 6's packager (zip/Mermaid/S3).
- **Consistency Auditor detects, it doesn't trigger regeneration.** Per the original build order's own phrasing ("Consistency Auditor (contradiction detection, no auto-regeneration)") - the targeted-regeneration loop is real revision-loop machinery for a later phase.
- **`AgentExecution` rows are inferred, not observed.** Phase 5's generation call is one big blocking HTTP request - Spring only sees the final result, not per-step progress. Real per-step tracking needs the streaming/webhook call pattern from `decisions-and-technical-architecture.md` §11.3 (Phase 6).
- **`credits_charged` is always 0.** Billing doesn't exist yet (Phase 7) - the column is populated with a placeholder, not real charging.
- **Every specialist inherits the Orchestrator's single default model** (`GRILLD_AI_MODEL`) rather than the roster table's per-agent Opus/Sonnet split from §3.2 - that tuning is real but costs real money to validate; deferred until there's a reason to spend on it.
