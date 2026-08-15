# Grilld — Design Decisions (Resolving Open Questions)

**Companion to:** `product-and-architecture.md` and `interrogation-engine.md`. This document resolves all six original open questions plus decisions made in the `/grill-me` review pass, and specifies the resulting designs.

---

## Decision Summary

| # | Question | Decision |
|---|---|---|
| 1 | Voice input? | **Yes — MVP, not v2.** It's the interview's primary input mode, text is the fallback. Audio is never persisted — streamed to Deepgram, discarded after transcription. |
| 2 | Boilerplate code generation? | **Yes — v2.** Blueprint ships first; code gen is the deliberate second act. |
| 3 | "Critique my existing project" mode? | **Yes — Audit Mode.** Ships as v1.5, likely the larger market. |
| 4 | Open with a question or a restatement? | **Restate first.** (My call — reasoning in §4.) |
| 5 | Show the slot graph? | **Yes, expertise-gated.** Three progressive views. |
| 6 | How aggressive should waiving be? | **Conservative with tiered confidence.** Waive only on explicit signals; soft-deprioritize otherwise. (My call — reasoning in §6.) |
| 7 | How should a mid-interview or post-delivery pivot be handled? | **Blast-radius classifier.** SEED-slot changes or >~30% filled-slot invalidation trigger an explicit `MAJOR_REVISION` confirmation and near-full-run pricing (~35–40 credits), instead of silently cascading or falling through to the cheap phase-check-in price. See §7. |
| 8 | How does orchestration handle edge cases, stay interactive, and show progress? | **Enumerated known cases + generic catch-all**, never a silent failure. Askable agents (Tech Architect, Infra) run sequentially, never in parallel, to avoid a question queue. Each agent emits a curated one-line `narration` (no extra LLM call). A deterministically-assembled, read-only **Run Report** side canvas (Codex-plan-style, rewritten in place) shows live progress. See §10. |
| 9 | What runs the AI agents — Spring Boot or a separate service? | **Separate Python service (Deep Agents + LangGraph), called by Spring Boot.** Spring Boot keeps auth/billing/canonical Postgres/API surface; Python owns all LLM calls and agent orchestration. Spring stays the sole schema owner (billing atomicity, single migration path) — Python is invoked via an async start-run/webhook/resume-run pattern, not given direct DB access. See §11. |

---

## 1. Voice Input — Primary Input Mode

Promoting this from "prototype it" to **MVP-critical**, because it compounds with the interrogation design rather than sitting beside it.

### Why it's leverage, not polish

The engine's two best techniques both reward long, unstructured answers. Free elicitation wants rambling. Laddering climbs through consequences that people articulate naturally out loud and truncate brutally when typing. A textarea produces "a tool for tracking expenses." A microphone produces ninety seconds that mentions the spreadsheet they currently use, the two friends who asked for it, and the fact that they gave up on an earlier attempt — three slots filled from one answer instead of a follow-up chain.

Every downstream document is a function of interview richness. This is the cheapest available multiplier on output quality.

### Design

**Hybrid by question type** — the Interrogator already emits `input_mode`; extend the enum:

| `input_mode` | Rendering | Used for |
|---|---|---|
| `voice_primary` | Big mic button, text link below | Free elicitation, laddering, scenario projection — anything open-ended |
| `chips` | Tap options | Enumerable answers (team size, timeline bands) — voice is *slower* here |
| `number` | Numeric input | Concretization probes |
| `text` | Textarea | User preference override, or where exact strings matter (URLs, names) |

Never force voice. A persistent global toggle (`prefer_text`) respects the user who's in a coffee shop or simply hates talking to software.

**Provider: Deepgram.** Native streaming with live partial transcripts and keyword/term boosting (needed for "Postgres"/"Kubernetes" mis-transcription handling below) — a better fit than Whisper's more batch-oriented streaming for this UX.

**Pipeline:**
```
Browser MediaRecorder → chunked stream → Deepgram (streaming STT)
  → live partial transcript shown to user
  → on stop: final transcript → editable text box (correction affordance)
  → user confirms → normal fact-extraction path
  → raw audio discarded, never persisted — only the resulting text is stored
```

**Retention: audio is never persisted.** Chunks are streamed to Deepgram and discarded once transcribed — Grilld never writes raw voice audio to disk, S3, or Postgres. Only the final (user-confirmed, editable) text transcript is stored, going through the same episodic-log path as a typed answer. This is a deliberate scope cut, not an oversight: it sidesteps the harder privacy/retention questions around storing biometric-adjacent voice data before a formal data policy exists. A full privacy policy / retention policy is explicitly deferred — not needed pre-launch, revisit before onboarding real paying users.

The **editable transcript before submit** is non-negotiable. Users watching an AI silently mis-hear them and build a spec on it is the single worst failure this product could have. Show it, let them fix it, then proceed.

**Disfluency handling:** do *not* strip filler and false starts before the LLM sees it. "I want it fast — well, not fast, I mean people shouldn't have to wait around wondering if it saved" is far more informative than the cleaned version. The self-correction *is* the signal. Clean only for the episodic-log display.

**Voice-specific slot extraction:** a spoken answer typically fills 2–4 slots at once. The extraction step must be greedy across all open slots, not just `targets_slots[]`. This has a nice second-order effect — spoken interviews will terminate in fewer turns than typed ones.

**Barge-in for long rambles:** if a spoken answer exceeds ~90 seconds, show a gentle "got it, keep going or wrap up?" — prevents the four-minute monologue that blows the extraction context.

**Cost:** streaming STT runs roughly $0.004–0.01/min. A 20-turn voice interview is maybe 10 minutes of audio — under $0.10. Negligible against the API cost of the run. Charge **0 extra credits for voice.** Making the higher-quality path free is how you get everyone on it.

**Accents:** you're building from India for a global audience. Test Indian English explicitly during evaluation — Deepgram and Whisper both handle it reasonably, but "Postgres" and "Kubernetes" spoken quickly are common mis-transcriptions. Maintain a domain vocabulary boost list (framework names, cloud providers, common tech terms) — most STT APIs support keyword boosting and it measurably improves technical transcription.

---

## 2. Boilerplate Code Generation — v2

**Confirmed as v2, deliberately.** The reasoning is worth stating plainly because it will be tempting to pull forward:

The blueprint's value proposition is *complete* without code. `AGENTS.md` handed to Claude Code produces better-fitting scaffolding than Grilld could generate blind, because the user's agent sees their actual environment. Shipping code gen early means owning a generation pipeline across N stacks, and every bug in it damages trust in the blueprint — which is the part that's actually defensible.

**Sequencing:**
- **v1:** Blueprint + `agent-kit`. Instrument how many users say "generate the code too."
- **v1.5:** Audit Mode (§3) — larger market, more reuse, less risk.
- **v2:** Code gen, scoped to the 3–4 stacks with highest observed demand. Not a universal generator.

**When built, the shape:**
```
/starter-repo/
├── Working skeleton — runs on first command, nothing more
├── One vertical slice implemented end to end (the core loop from ROADMAP P1)
├── Real config: Docker, CI, env template, migration setup
├── Tests for the slice (demonstrates the pattern, not coverage)
└── TODO markers keyed to ROADMAP phases
```

**The design rule:** generate a *skeleton with one vertical slice done well*, not a half-finished app. A running skeleton the user extends beats 60% of an app they must debug. The vertical slice teaches the pattern; their agent replicates it.

**Credits:** ~40, a substantial add-on, because it's genuinely expensive and shouldn't be bundled into every run.

---

## 3. Audit Mode — Critique My Existing Project

**Yes, and you're right that it's potentially the bigger market.** Every greenfield project is a one-time customer; every existing codebase is a recurring one. It reuses the interrogation engine, rubric agent, memory layers, packager, and most specialists.

### The inversion

Greenfield: interrogate the human → produce a plan.
Audit: interrogate **the codebase and the human together** → produce a diagnosis.

Slots get filled from two sources now, and the interesting slots are the ones where the two disagree.

```
Repo connected (GitHub OAuth, read-only)
        ▼
┌─ Repo Scanner (deterministic, no LLM) ──────────────────┐
│ Language/framework detection, dependency manifests +     │
│ versions + known CVEs, directory structure, test         │
│ presence & ratio, CI config, Docker/IaC presence,        │
│ LOC by area, commit cadence, contributor count,          │
│ TODO/FIXME density, absent-file signals (no README,      │
│ no .env.example, no migrations dir)                      │
└────────────────────┬────────────────────────────────────┘
                     ▼
┌─ Codebase Interrogator (subagent, read-only tools) ─────┐
│ Reads selectively — entry points, config, the largest    │
│ files, the data layer. Never the whole repo.             │
│ Fills slots with origin=OBSERVED.                        │
└────────────────────┬────────────────────────────────────┘
                     ▼
        Slot graph pre-populated (~60% filled)
                     ▼
┌─ Human Interrogator ────────────────────────────────────┐
│ Asks ONLY what code cannot reveal: intent, constraints,  │
│ pain, trajectory, team reality, what they already know   │
│ is broken and chose not to fix.                          │
└────────────────────┬────────────────────────────────────┘
                     ▼
         ┌─ Divergence Detector ─┐   ◄── the money
         │ OBSERVED vs STATED     │
         └───────────┬───────────┘
                     ▼
              Audit package
```

### Slot origin gains a fourth value

`OBSERVED` — established from the codebase rather than the human. Carries a confidence score and a file reference.

**The Divergence Detector is the product.** Anyone can list dependency warnings. What no static tool produces is the gap between intent and reality:

- User says "we're scaling to 50k users" / code has no connection pooling, no caching, N+1 queries in the hot path
- User says "security is critical" / secrets committed in `application.properties`
- User says "team of five" / 94% of commits from one author
- User says "we ship weekly" / no CI, no tests, manual deploy script
- User says "the DB is the bottleneck" / code shows the bottleneck is unbatched external API calls

Each divergence becomes a finding with evidence on both sides. That's a genuinely uncomfortable, genuinely valuable document — and it cannot be produced by reading code alone or by talking alone.

### Audit output package

```
/grilld-audit/
├── EXECUTIVE_SUMMARY.md      — top 5 findings, severity-ranked
├── DIVERGENCES.md            — stated intent vs. observed reality, evidenced
├── ARCHITECTURE_ASSESSMENT.md — what's actually there, with a diagram
├── /diagrams
│   ├── current-architecture.mmd   ◄── reverse-engineered from the repo
│   └── target-architecture.mmd    ◄── where it should go
├── TECH_DEBT.md              — ranked by (impact × likelihood) ÷ effort
├── SCALE_READINESS.md        — what breaks first at 10×, and why
├── SECURITY_REVIEW.md        — config, deps, auth patterns, exposure
├── REMEDIATION_ROADMAP.md    — phased, sequenced, effort-estimated
└── /agent-kit
    ├── AGENTS.md              — for THIS codebase: conventions, gotchas,
    │                            what not to touch, current phase
    └── /skills                — remediation skills, phase-gated
```

`current-architecture.mmd` reverse-engineered from a real repo is a strong standalone hook — plenty of teams would pay for that alone.

### Boundaries

- **Read-only, always.** Never write to the user's repo. Trust is the entire product.
- **Never send full source to the API.** Deterministic scanner first, then selective reads. Ship a self-hosted/BYO-key option for teams who need it.
- **Repo size ceiling** on lower tiers — a 500k-LOC monorepo is a different product.
- **Private repos need explicit scope consent** with a clear statement of what's read.

### Why v1.5 rather than v2

Higher reuse than code gen, higher willingness to pay (auditing an existing revenue-generating system is a business expense), and lower risk — a wrong opinion in an audit is a conversation, a wrong line in generated code is a bug.

**Credits:** ~70 for a full audit, ~30 for a scan-only "quick health check" (deterministic scanner + summary, no human interview). The quick check is a strong free-tier hook and a natural upsell.

---

## 4. The Opening Move — Restate First *(my call)*

**Decision: open with a restatement, not a question.** Specifically:

```
User types/speaks their idea
        ▼
Grilld restates it back — structured, slightly sharper than they put it,
  with 2–3 explicit inferences flagged as inferences
        ▼
"Here's what I understood. What did I get wrong?"
```

**Example:**

> You want to build a tool that helps freelancers track which invoices are unpaid, because chasing clients over email is tedious and easy to lose track of.
>
> I'm inferring three things you haven't said:
> - This is for individual freelancers, not agencies with a finance person
> - You'd want it to send reminders automatically, not just show you a list
> - You're building this because you have the problem yourself
>
> What did I get wrong?

**Why this over a question:**

1. **It proves listening before it asks for effort.** The first impression is "this thing understood me," not "this thing wants more from me." Every subsequent question is answered more generously because the user has evidence it's worth answering.
2. **Corrections are cheaper than elicitation.** People are far better at spotting a wrong statement than producing a complete one. "No, it's for small agencies actually" arrives in three seconds; "describe your target user" takes a paragraph and yields less.
3. **Flagged inferences fill slots for free.** Each inference either gets confirmed (slot filled, high confidence) or corrected (slot filled, higher confidence, plus a signal about what the user cares about). Three inferences can fill three slots in one turn — the highest-yield turn in the entire interview.
4. **It sets the register.** The user learns immediately that Grilld makes assumptions and expects to be corrected — which is exactly the posture that makes the "skip → assume" mechanic work later without feeling like it's guessing behind their back.
5. **It demonstrates the product's value proposition in turn one.** The whole pitch is "we turn your vague idea into something specific." Doing that visibly, immediately, before asking for anything, is the strongest possible opening.

**Constraint:** exactly 2–3 flagged inferences, never more. Six inferences reads as presumptuous and gives the user too much to correct at once. And they must be genuinely *non-obvious* — restating what they literally said as an "inference" reads as padding.

**Failure mode to guard:** if the raw idea is one vague line ("an app for fitness"), there's nothing to restate. Fall back to a single free-elicitation question, then restate after their answer. Rule: **restate when the raw idea exceeds ~25 words, otherwise elicit once, then restate.**

---

## 5. Slot Graph Visibility — Expertise-Gated, Three Views

Yes to showing it, tiered by the expertise profile (§5 of the interrogation spec), with a manual override.

| View | Default for | Shows |
|---|---|---|
| **Brief** | Expertise 1–2 | A progress bar and a plain-language list of what's been established: "Who it's for ✓ / How many users ✓ / Timeline — still figuring out." No slot keys, no graph, no jargon. |
| **Structured** | Expertise 3 | A grouped checklist with confidence indicators and assumption flags. Clickable to see what was inferred and correct it. Still no graph topology. |
| **Graph** | Expertise 4–5 | The actual slot graph — nodes, dependency edges, status colors, derived-slot lineage. Hover a node to see the answer that filled it and the turn it came from. |

**Always available regardless of tier:**
- Toggle to switch views manually (never lock someone out of detail they want)
- Click any established fact to correct it — corrections re-run affected downstream slots
- Assumptions visually distinct from confirmed facts

**Why the Graph view matters beyond utility:** for a technical user it's the single most convincing artifact in the product. Watching derived slots spawn from your own answer in real time is the demo that makes "this isn't a ChatGPT wrapper" self-evident, without needing to argue it. Worth building well — animate the spawn, don't just re-render the list.

---

## 6. Waiving Policy — Conservative with Tiered Confidence *(my call)*

**Decision: waive only on explicit signals; use soft deprioritization for everything else.**

The asymmetry drives this. An under-waived interview asks a mildly irrelevant question — the user shrugs and skips it, minor friction. An over-waived interview silently never asks about something that mattered, and the failure surfaces in the *output documents*, where it's expensive and invisible until too late. A missing question is a bad answer with no evidence of where it came from.

So: **hard waives require explicit user statements. Inference gets deprioritization, not deletion.**

### Three tiers

| Tier | Trigger | Effect |
|---|---|---|
| **HARD_WAIVE** | User explicitly stated something that makes the slot meaningless | Slot removed. Never asked. Logged with the quote that justified it. |
| **SOFT_DEPRIORITIZE** | Strong inference the slot is probably irrelevant | Importance dropped to 1. Only asked if the interview runs long with capacity to spare. Effectively never asked, but recoverable. |
| **KEEP** | Anything else | Normal priority |

### HARD_WAIVE requires all three

1. The user made a **direct statement** (not an inference from tone or adjacent facts)
2. The statement makes the slot **logically meaningless**, not merely unlikely
3. The waive is **traceable to a specific quote**

Examples that qualify:
- "This is just for me, I'm never releasing it" → waive all monetization, GTM, competition, onboarding, multi-tenancy slots
- "It's an internal tool, only employees will use it" → waive public marketing, SEO, signup-flow slots
- "No budget, using free tiers only" → waive paid-infrastructure slots

Examples that do **not** qualify (→ SOFT_DEPRIORITIZE):
- User sounds like a hobbyist → *not a statement*
- User said "solo dev" → doesn't make monetization meaningless; plenty of solo devs charge money
- User hasn't mentioned mobile → absence of mention is not a statement

### Cascade rule

A HARD_WAIVE cascades to descendant slots, but **only one level of inference deep**. `monetization_model` waived → `pricing_tiers` and `payment_provider` waive with it. But it does *not* cascade to `user_accounts`, which has independent justification. Deep cascades are how one wrong waive silently deletes a quarter of the interview.

### Reversibility

Every waive is recorded with its justifying quote and is **reversible**. If a later answer contradicts the basis ("actually, if it works I might sell it"), the Contradiction Detector un-waives the affected cluster and re-queues those slots. This is why waived slots are marked `WAIVED`, never deleted — deletion is the thing that makes over-waiving unrecoverable.

### Visibility

Waived slots appear in the slot panel, greyed, with the reason: *"Skipped — you mentioned this is a personal project."* One click restores them.

This makes the policy self-correcting in a way tuning alone can't. Instead of guessing the right aggressiveness, the user tells you when you got it wrong, one click at a time.

### Instrumentation for tuning

```sql
slot_waives(
  id, session_id, slot_key, tier,          -- HARD_WAIVE | SOFT_DEPRIORITIZE
  justifying_quote, at_turn,
  was_reversed BOOLEAN, reversed_at_turn,
  was_manually_restored BOOLEAN
);
```

**The metric that matters: manual restoration rate.** If users are clicking "add this back" on more than ~5% of waives, waiving is too aggressive. If restoration is near zero *and* interviews run long, it's too timid. Start conservative, loosen with evidence — the reverse order is much harder to recover from because the damage is invisible.

---

## 7. Revision & Pivot Handling — Blast-Radius Classifier

**Decision: the user can correct or reinvent anything, at any point — during the interview or after a package has been delivered — but the system must classify *how much* changed before deciding what to do about it.** Two very different things currently get conflated: a single-fact correction ("actually it's freemium, not free") and a genuine pivot ("forget invoicing, I want to build a scheduling tool instead"). The first is already handled cheaply (click-to-correct, §5; waive reversal, §6). The second invalidates most of the brief and most generated docs, and neither the existing mechanisms nor the existing pricing account for it.

### The Revision Classifier

Extends the `ContradictionDetector` (interrogation-engine.md §11). Runs on every correction — whether it arrives mid-interview as a normal answer, via click-to-correct in the slot panel, or as a brief reopened after delivery:

```
1. Diff the incoming fact against the current brief.
2. Compute blast radius:
   - Which slots does this directly contradict or invalidate?
   - Which already-FILLED descendant slots depend on those (via `unlocks`)?
   - Does it touch a SEED slot (problem_statement, target_user,
     scale_expectation, team_shape, success_definition)?
3. Classify:
   MINOR_CORRECTION  → existing cascade rule applies (§6: one level deep,
                        re-run only directly affected downstream slots/docs)
   MAJOR_REVISION     → SEED slot changed, OR >~30% of currently FILLED
                        slots are invalidated by the cascade
```

**On `MAJOR_REVISION`:** never silently cascade. Surface it explicitly — "This changes who this is for / what it does. That affects Architecture, Infra, Roadmap, and the Agent Kit — want me to regenerate those?" — mirroring the conservative-waiving philosophy in §6: expensive, hard-to-reverse actions get a confirmation, not an assumption. If confirmed, the affected agents re-run against the revised brief; agents whose inputs are untouched (e.g. Diagram Agent, if the architecture didn't change) are skipped, not blindly re-run.

**Where this can be triggered:**
- Mid-interview, from a normal answer that contradicts a SEED slot
- Slot panel, via click-to-correct on an established fact
- **Post-delivery**, via a "Revise brief" entry point distinct from the phase-check-in flow — the phase check-in (§8 below) is scoped to "did the plan hold up," this is scoped to "the plan itself needs to change"

### Why this matters for pricing

A `MAJOR_REVISION` costs close to a fresh full run in API terms (most agents re-execute), but the only existing post-delivery re-entry price point is the 8-credit phase check-in, which assumes a small delta. Pricing this correctly is what keeps "revise anything, anytime" from being a margin leak — see the updated credit table in §9.

---

## 8. Consolidated Roadmap

| Version | Ships | Notes |
|---|---|---|
| **MVP** | Interrogation engine (dynamic slots), **voice input**, restatement opening, expertise-gated slot panel, conservative waiving, Revision Classifier (§7), Scale Calibrator, Tech Architect + Infra + Diagram + Roadmap + Skills Curator + Agent-File Writer, Consistency Auditor, package download, credits | Voice is in from day one — it's an input mode, not a feature |
| **v1.1** | Market / Competition / Strategy agents, phase check-in loop | The commoditized docs — add once the defensible core is proven |
| **v1.5** | **Audit Mode** (repo scan + divergence detection) + free "quick health check" | Larger market, high reuse, low risk |
| **v2** | **Boilerplate code generation** for the top 3–4 observed stacks | Only after demand is measured |
| **v2.5** | Team workspaces, Notion/Linear export, self-hosted audit for enterprise | |

## 9. Updated Credit Costs

| Action | Credits |
|---|---|
| Interview turn (voice or text) | 1 |
| Full blueprint | ~50 |
| Minor correction (single fact, one-level cascade) | Free — bundled, same as any other answer |
| **Major revision (pivot — blast radius >~30% of filled slots, or a SEED slot changed)** | **~35–40** — priced near a fresh run since most agents re-execute; only untouched agents' outputs are reused |
| Phase check-in + regeneration (plan held, minor drift) | 8 |
| Single-doc regeneration | 3 |
| **Quick health check** (scan only, no interview) | **Free** — 1 per account, then 10 |
| **Full audit** | **~70** |
| **Starter repo generation** (v2) | **~40** |

Free signup grant stays at 60 credits — enough for one complete blueprint. The free quick health check is a separate acquisition path targeting people with existing code, who are a different (and probably higher-value) audience than idea-stage builders.

**Margin note (Aug 2026 Claude pricing — Opus 5 $5/$25 per MTok, Sonnet 5 $2/$10 intro through Aug 31 then $3/$15, Haiku 4.5 $1/$5):** a full T2 blueprint (MVP roster, ~18 interview turns) runs roughly **$1.3–1.5 in API cost** before prompt caching — meaningfully better than the $3–5 estimate in `product-and-architecture.md` §10, which predates current-generation pricing. At 50 credits (~$8–16 retail depending on tier), that's 5–10x margin, with more headroom once caching is applied to the brief context reused across agents. This is why a `MAJOR_REVISION` can be priced near a full run's credit cost without being predatory — it approximately *is* a full run, cost-wise.

---

## 10. Orchestration Robustness — Interrupt Handling, Narration & the Run Report

**"Handle every case" isn't a spec by itself — nothing handles literally every case.** What's buildable: an enumerated set of known situations the Orchestrator explicitly handles (below), plus one generic catch-all for anything unenumerated. The catch-all is itself a hard requirement: pause, persist state, surface a plain-language message, offer retry/skip, log for founder review — never a silent crash, never lost progress, never a stuck run with no way forward.

### 10.1 Known-case taxonomy

**Interview phase** (already specified elsewhere — listed here for completeness of the taxonomy):
- Vague answer → `VaguenessDetector` spawns a concretization probe (interrogation-engine.md §4)
- Contradiction → `ContradictionDetector` spawns a resolution slot (interrogation-engine.md §11)
- Slot no longer relevant → waive, tiered by confidence (§6)
- Correction that invalidates a lot → `MAJOR_REVISION` classification (§7)
- Interview running long / diminishing returns → wrap-up proposal, always-available force-exit (interrogation-engine.md §7)
- User abandons mid-interview → autosave per answer, resumable, email nudge at 60% (spec-v2 §13)
- Off-topic or meta question mid-interview ("what model is this," "just write the code") → Interrogator acknowledges briefly in one line, then redirects to the open slot it was already asking about. Never lets a digression get treated as slot-fillable content, never derails into a general chat session.
- Adversarial input (prompt injection via a free-text answer) → structural, not prompt-based: user text only ever populates slot *values* through the structured-output extraction schema. It is never concatenated into an agent's instruction context in an instruction position — only ever passed as clearly-delimited data. An answer cannot alter what an agent is instructed to do, no matter its content.

**Generation phase** (new, this section — updated for the Python AI-service split, §11):
- Agent execution fails (API error, timeout, malformed structured output) → retried inside the Python service (LangChain/LangGraph's own retry handling) up to a cap; if Spring's call to the Python service itself fails or times out (network/service-down, not an agent-level failure), Spring retries the HTTP call with backoff. If exhausted either way: mark the step `FAILED` in `agent_executions` and the Run Report, pause only that branch of the run, surface "Something unexpected happened while generating {doc} — your progress is saved. Retry, skip this step, or contact support?" Never silently drops output or fails the whole run for one agent's failure.
- Agent wants to ask the user → per the sequential-execution rule (spec-v2 §3.1), enforced by `HumanInTheLoopMiddleware` inside the Python Orchestrator, only one asking-capable agent runs at a time, so there's never a queue to build. Python's webhook reports the pause + question to Spring; the run pauses until Spring calls `resume-run` with the answer (§11).
- Insufficient credits to start a run → checked at pre-authorization before any agent executes (spec-v2 §13); blocked with a clear top-up prompt, nothing charged.
- Insufficient credits mid-run (a `MAJOR_REVISION` fires mid-flow and adds cost) → treated as a fresh pre-authorization checkpoint at the moment of classification, before the revision proceeds — same block-and-prompt behavior, not a mid-run surprise charge.
- User closes the tab / goes offline mid-generation → the run is server-side and does not depend on an open connection except at explicit question-pauses. Reopening shows the current Run Report state immediately (it's persisted, not session state); a notification fires on completion or on a pending question.
- Same brief edited from two open tabs → `project_briefs.version` (already in the data model, spec-v2 §9) is the optimistic-lock check; the second write against a stale version is rejected with "this brief changed elsewhere, refresh to see the latest," never silently overwritten.

### 10.2 Narration — the curated one-liner

Every agent's structured output gains a `narration` field: 1-2 plain-language sentences on what it did and why ("Comparing Postgres vs Firebase given your JSONB needs — going with Postgres since you'll want relational queries on the invoice/client join later.") — the same pattern already used for the Interrogator's `why_asking`.

This is **not** raw chain-of-thought and **not** a separate LLM call. It's one more field in the artifact the agent was already producing, written by the same call. That keeps it free (no added token cost beyond a couple hundred output tokens), fast (no extra round-trip), and safe (nothing to leak — it's a curated summary, not the model's actual reasoning trace or system prompt).

### 10.3 The Run Report (side canvas)

A single document per `generation_runs` row (`run_report_md`), analogous to Codex's plan panel: **rewritten in place, not appended to.** Structure:

```
✓ Interrogation complete — brief finalized (T1, Solo Indie MVP)
✓ Tech Architect — Postgres + Next.js 15 + Spring Boot chosen
  → Comparing Postgres vs Firebase given your JSONB needs...
⏳ Infra Agent — generating deploy config for Railway
  Queued: Diagram Agent, Roadmap Agent, Skills Curator, Agent-File Writer, Consistency Auditor
```

- **Assembled deterministically by the Orchestrator (Java), not an LLM call** — it's a rewrite of the same `narration` + status fields already sitting in `agent_executions`, formatted into the report each time a row is written. Zero marginal cost per update.
- **Read-only for the user during the run** (per your call above) — they watch it update live over SSE/WebSocket, frontend diff-highlights the changed lines (matching the Codex feel you're going for). Any change the user wants to make goes through the normal correction/revision path (§7), which already knows how to classify and re-cascade correctly — the Run Report is a status view, not an editing surface, so there's exactly one place edits can happen.
- **Persisted**, so a reopened tab or a resumed session shows current state instantly, consistent with the rest of Grilld's "state lives outside the conversation" principle (spec-v2 §2).

### 10.4 What this doesn't cover

This taxonomy is deliberately not exhaustive — it's the known cases plus a safety net, not a claim of completeness. Track `agent_executions.error` in production; when the same unenumerated failure shows up more than once or twice, that's the signal to add it as a new known case rather than leaving it to the catch-all indefinitely.

### 10.5 Run Durability — Surviving Restarts and Deploys

**Updated for the Python AI-service split (§11) — this is now durability across *two* services, not one JVM.** A restart of either Spring Boot or the Python service mid-run shouldn't lose progress, and the two services need to agree on what "resumed" means without stepping on each other.

**Decision: two independent durability mechanisms, one per service, each covering only what it owns.**

- **Python side — LangGraph's own Postgres checkpointer** (own tables, same DB instance as Spring's, never the business tables) persists in-flight graph/interrupt state automatically. If the Python service restarts mid-step or mid-interrupt-pause, the Deep Agents Orchestrator and any paused subagent (e.g. Tech Architect waiting on a `resume-run` call) pick back up from the last checkpoint when that run is next invoked. This is what LangGraph's checkpointer is *for* — no custom sweep needed on this side.
- **Spring side — resume sweep over webhook staleness**, since Spring's view of "what's happening" is entirely webhook-driven now: a `@Scheduled` job finds `generation_runs` with `status=IN_PROGRESS` whose latest `agent_executions` row is `RUNNING` with no webhook update past a staleness threshold (a few minutes). Rather than guessing whether the Python side actually finished, Spring calls a `status` endpoint on the Python service for that run handle — if Python reports the step actually completed (webhook was just lost, not the work), Spring reconciles from that response; if Python has no record of it (Python itself restarted mid-step and lost pre-checkpoint work), Spring marks it `FAILED` and re-triggers via `start-run` (or `resume-run` if Python's checkpointer had already captured a mid-graph point) from the last `COMPLETED` step. Already-`COMPLETED` steps are never re-run.
- `agent_executions` keeps its `started_at`/`heartbeat_at` timestamps (already added, spec-v2 §9) for staleness detection — `heartbeat_at` is now updated by incoming webhooks rather than by an in-process Java thread.

This makes "the fix I deployed at 11pm didn't eat someone's half-finished blueprint" true by construction on both sides of the service boundary, not just the Java one.

### 10.6 Cost Circuit Breaker

**Distinct from what's already specced:** per-agent `max_tokens` caps (spec-v2 §10) protect against one agent's runaway output; per-run credit pre-authorization protects against one expensive run. Neither protects against a *systemic* loop — e.g. a bug that lets the Rubric Agent's escape hatch (spec-v2 §7) get bypassed, so the interrogation loop or a regeneration path fires far more LLM calls than intended, across many runs, while nobody's watching (solo, part-time, no 24/7 on-call).

**Decision: a global spend kill-switch, checked before every new run starts.**

- A `platform_settings(key, value, updated_at)` key-value table (simplest possible implementation) holds a `daily_spend_cap_usd` threshold and a `kill_switch_active` boolean.
- A `@Scheduled` job sums recent cost from `agent_executions` (`input_tokens`/`output_tokens` × known per-model rates — the same rates used in the §9 margin math) over a rolling window (start with 24h; add an hourly check too if the daily one ever actually trips, to catch a fast-burning bug sooner). If cumulative spend crosses the threshold, it flips `kill_switch_active = true` and fires an alert (email/push — whatever's cheapest to wire up first).
- **While active:** the Orchestrator refuses to start any new run — checked at the same pre-authorization point where credits are already checked (spec-v2 §13) — and the user sees a plain "Grilld's briefly paused for a check, try again shortly" message, not an error. In-flight runs are allowed to finish rather than killed mid-way.
- **Manually resettable only** — no auto-recovery. Forces you to actually look at `agent_executions` for the anomaly before traffic resumes, which is the point: a threshold that silently resets would defeat the purpose of a circuit breaker.
- Threshold starts as a rough guess (e.g. 3–5x your expected daily API spend at current test volume) and gets tuned once you have real usage data — the mechanism matters more than the exact number at MVP.

### 10.7 Deliberately not built for MVP

**No formal eval/regression harness for the interrogation engine.** Prompt changes get manually walked through by hand rather than replayed against golden fixture transcripts. This is an accepted gap, not an oversight: at solo/pre-validation scale the manual pass is cheap and the eval-harness build cost isn't yet justified. Revisit once real usage data exists to build fixtures from — the risk it's deferring (a prompt tweak silently degrading turn count or slot coverage) is real but currently cheaper to catch by hand than to automate.

**Grilld's own hosting** (Spring Boot + the Python AI service + Postgres + S3/R2 + Mermaid CLI sidecar) is intentionally left undecided here — resolve it at build step 1 (spec-v2 §12), not in the spec docs. One constraint carries forward from §11: whatever's chosen, co-locate the two services on the same private network — the Spring↔Python hop needs to stay low-latency, and both need to reach the same Postgres instance.

---

## 11. AI Services Architecture — Python (Deep Agents + LangGraph) Behind Spring Boot

**Decision: agent/LLM logic moves out of Spring Boot entirely into a separate Python service.** Spring Boot keeps auth, billing, the canonical Postgres schema, and the API surface — genuinely well-matched to Java, per the original reasoning in spec-v2 §8. But agent orchestration, tool calling, and every LLM call move to Python, where the tooling (LangChain, LangGraph, Deep Agents) is materially more mature than anything in the JVM ecosystem — including Spring AI Alibaba Graph, which is a real LangGraph-equivalent for Java but younger and less established than the Python originals. This supersedes the "Spring AI `ChatClient`" decision made earlier in this doc — Spring Boot no longer calls Claude at all.

### 11.1 Framework layering

Per the `framework-selection` skill's decision table, Grilld's requirements (sub-task decomposition, persistent memory, file management) route to **Deep Agents** as the top-level framework, not raw LangChain or LangGraph:

| Grilld design (spec-v2 §3, §5, §6) | Deep Agents middleware |
|---|---|
| Orchestrator delegates to specialists, flat hierarchy (§3.1) | `SubAgentMiddleware` — `task` tool, named subagents |
| Roadmap Agent's phased plan / the Run Report checklist (§10.3) | `TodoListMiddleware` — `write_todos` |
| Infra Agent, Agent-File Writer, Skills Curator, Diagram Agent (`file_write`) | `FilesystemMiddleware` |
| Tech Architect / Infra Agent "can ask user", sequential-only rule (§3.1) | `HumanInTheLoopMiddleware` |
| Three-layer Postgres memory architecture (spec-v2 §2.1) | Deliberately **not** `MemoryMiddleware` — see §11.2, Spring stays the memory owner |

**Exception: the Interrogator is a LangGraph subgraph, not middleware-driven.** Its control flow — vagueness triggers, laddering depth caps, contradiction detection, technique-variety enforcement (interrogation-engine.md §4, §9) — is precise custom graph logic, not generic delegation. Built as its own LangGraph `StateGraph` and registered as a Deep Agents subagent (the "Mixing Layers" pattern: Deep Agents orchestrator → LangGraph subagent for the one piece that needs hand-tuned edges).

### 11.2 Service boundary — Spring owns all canonical state

**Decision: Spring Boot is the sole owner of `project_briefs`, `slots`, `agent_executions`, `generation_runs`, and credits. The Python service never writes to these tables directly** — it receives context, does work, and reports results back over HTTP. This was a deliberate choice against the alternative (Python writing directly via Deep Agents' `MemoryMiddleware`/`Store`), for two reasons:

1. **Billing correctness.** The `@Transactional` atomic credit deduction (spec-v2 §8) needs the run's completion state and the credit ledger written together. Clean and atomic if Spring does both; a distributed-transaction problem if a second service writes half of it.
2. **One schema, one migration owner.** Two codebases (JPA/Java, Python) both migrating and writing the same tables is the kind of coupling that gets progressively more painful for a solo maintainer — exactly the "redesign later" risk to avoid.

**What Python *does* own:** LangGraph's Postgres checkpointer — its own tables, same DB instance, but a completely separate concern (in-flight graph/interrupt durability, §10.5) from the business record. This gets you interrupt-durability across a Python restart without any business-schema coupling.

### 11.3 Call pattern

A single long synchronous HTTP call doesn't work here — a full generation run can take minutes and may pause indefinitely on a human-in-the-loop interrupt.

**Revision after checking the `langgraph-cli` skill (my call, flagged for review):** don't hand-build the webhook/resume endpoints below from scratch. LangGraph has a self-hostable server (`langgraph build` + `langgraph up`, no LangSmith subscription required — just Docker on your own infra) that already exposes threads, runs, streaming, and state-based resume as a REST/SSE API. That's most of what's diagrammed below, given for free instead of hand-implemented. Concretely: Spring creates a thread + run against the LangGraph server, streams it (SSE) instead of waiting on Python-initiated webhooks, and resumes an interrupted run by updating thread state rather than a custom `/resume` endpoint. The shape (Spring owns business state, Python is a called service, LangGraph checkpointer owns in-flight durability) doesn't change — only the transport does. The diagram below still describes the logical flow accurately; treat "webhook" as "the LangGraph server's run/stream API" when it comes time to implement.

```
Spring: POST /runs/start  {brief, scale_tier, ...}
Python: 202 Accepted, {run_handle}
        → Deep Agents Orchestrator begins executing the specialist
          pipeline in the background

Python → Spring, per completed subagent:
  POST /internal/webhooks/step-complete
    {run_handle, agent_name, output_ref, narration, tokens...}
  → Spring writes the agent_executions row, rewrites run_report_md,
    pushes the update over SSE (§10.3)

Python → Spring, when an asking-capable agent interrupts:
  POST /internal/webhooks/interrupt
    {run_handle, agent_name, question, why_asking}
  → Spring surfaces the question to the user, run stays paused
    (LangGraph checkpoint holds the graph state)

Spring → Python, once the user answers:
  POST /runs/{run_handle}/resume  {answer}
  → LangGraph resumes via Command(resume=answer) from checkpoint

Python → Spring, on full completion:
  POST /internal/webhooks/run-complete  {run_handle}
```

Spring's resume sweep (§10.5) covers the case where a webhook is lost or the Python service itself restarts mid-step.

### 11.4 Rubric Agent — use `openevals`, and reconsider the 1–5 scale

**Checked whether LangChain has anything the Rubric Agent (spec-v2 §7) should use instead of a hand-rolled prompt.** It does: `openevals` (LangChain's official evaluator library) ships an LLM-as-judge factory for exactly this shape — custom rubric criteria in, structured `{score, reasoning}` out. Both of Grilld's rubric passes fit it directly:

- **Brief-completeness rubric** (spec-v2 §7's 6 dimensions: problem clarity, scope boundedness, scale specificity, technical constraints, success definition, risk awareness) — build as an `openevals` custom judge with those six criteria instead of hand-writing the scoring prompt and parsing logic.
- **Generated-docs quality rubric** (spec-v2 §7's "second rubric" — specificity, tier-appropriateness, actionability) — same factory, different criteria set.

**One real design change this surfaces:** LangChain's own guidance is that fine-grained numeric scales (1–5) are where LLM-as-judge reliability breaks down — binary or low-precision categorical scoring calibrates far more consistently. The rubric dimensions currently specced as 1–5 (spec-v2 §7) should move to a 3-point categorical scale (`FAIL` / `BORDERLINE` / `PASS`) per dimension instead. `probe_further` fires on any `FAIL`, or on `BORDERLINE` in a dimension the interview hasn't touched recently — same control flow as before, just a more reliable scoring primitive underneath. This is a small change to make now, before the Rubric Agent is built, rather than after you notice the scores are noisy.

**Also relevant to the deferred eval harness (§10.7):** when a formal eval/regression suite becomes worth building post-MVP, LangSmith's dataset store + experiment runner is that infrastructure already — golden fixture transcripts as a versioned dataset, replayed as an experiment run. Not needed now, but it's the tool to reach for rather than building a custom harness later.

### 11.5 Latency

The Spring↔Python hop adds negligible overhead — low single-digit milliseconds on a co-located private network, against LLM calls that take seconds. The only real lever is deployment topology (keep both services in the same region/VPC), not which service owns the database. Shared-schema access would not have meaningfully reduced latency further; it would only have added coupling risk for no real speed gain.
