# Grilld — Interrogation Engine Spec

**Companion to:** `product-and-architecture.md`. This document replaces §2 (The Interrogation Engine) with a fully dynamic design.

**Core commitment:** There is no question bank. No script. No fixed rounds. Every question Grilld asks is generated in the moment from what the user has actually said.

---

## 1. The Central Problem

Two failure modes sit on either side of this design, and both are fatal:

**Fully scripted** → feels like a form. Asks irrelevant questions. Ignores what the user just said. Users abandon.

**Fully improvised** → charming but incomplete. The research on dynamic LLM interviewing is blunt about this: purely LLM-driven questioning is typically centered on one question at a time, relies entirely on the model, and produces limited knowledge coverage with no quality control — plus no calibration of question difficulty to the person's actual expertise. You get a lovely conversation that never established the budget, the timeline, or who the user is.

**Grilld's resolution:** the *questions* are fully dynamic; the *coverage model* is not. The system tracks **what must be known** (a growing, partly self-generated set of knowledge slots) while leaving **how to find out** entirely to the LLM in context. Structure lives in the state, never in the script.

---

## 2. The Slot Graph — Core Data Structure

Replace "rounds and questions" with a **dynamic slot graph**. A slot is an atomic piece of knowledge Grilld needs about the project.

```java
record Slot(
  String key,              // "target_user", "peak_concurrent_users", "auth_provider_constraint"
  String description,      // what filling this would establish
  SlotOrigin origin,       // SEED | DERIVED | PROBE
  SlotStatus status,       // OPEN | FILLED | ASSUMED | WAIVED | BLOCKED
  int importance,          // 1-5, dynamically reassessed
  String value,            // the established fact, once filled
  double confidence,       // 0-1, how solid is this answer
  String parentSlotKey,    // for laddering chains
  int depth,               // how deep in a probe ladder
  Set<String> unlocks,     // slot keys this one makes relevant when filled
  String evidenceRef       // which answer(s) established it
)
```

### Slot origins

| Origin | Meaning | Example |
|---|---|---|
| **SEED** | ~8 universal slots present for every project | `problem_statement`, `target_user`, `scale_expectation`, `timeline`, `team_shape`, `builder_skillset`, `success_definition`, `hard_constraints` |
| **DERIVED** | Spawned because another slot got a specific value | User says "handling patient records" → spawns `compliance_regime`, `data_residency`, `audit_requirements`, `encryption_at_rest` |
| **PROBE** | Spawned by the Interrogator mid-conversation because an answer was vague, contradictory, or interesting | User says "should scale well" → spawns `scale_definition_concrete` |

**The seed slots are not questions.** They're *knowledge requirements*. `scale_expectation` never renders as "What scale are you building for?" — the Interrogator decides how to surface it based on everything said so far. For a user who mentioned "my Discord server's 200 people," the natural form is "Is this mainly for that 200-person server, or aiming wider?"

**This is the whole trick.** A fixed set of things-to-know, an unbounded set of ways-to-ask.

### Slot graph growth

```
   TURN 0                TURN 3                      TURN 7
   ══════                ══════                      ══════
   8 seed slots     →    14 slots                →   23 slots
                         (6 derived from             (some filled,
                          "healthcare" +              some waived as
                          "solo dev")                 irrelevant,
                                                      3 probe slots open)
```

Slots can also be **WAIVED** — the Interrogator kills slots that became irrelevant. User says "personal tool, just me, never shipping it" → every monetization, competition, and GTM slot is waived immediately, never asked about. This is as important as spawning: not asking dead questions is what makes it feel intelligent.

---

## 3. Per-Turn Loop

Every turn is a fresh LLM call assembled server-side in Spring. Nothing accumulates in a context window.

```
┌─ 1. ASSEMBLE CONTEXT (Java, deterministic) ─────────────────┐
│  • Compacted brief (facts established so far)                │
│  • Open slots, ranked by importance × information_gain        │
│  • Last 3 turns verbatim (for tone and conversational flow)   │
│  • Expertise profile (see §5)                                 │
│  • answered_topics[] — hard guard against repeats             │
│  • Current fatigue/turn count                                 │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌─ 2. INTERROGATOR CALL (single LLM call, structured output) ──┐
│  Returns:                                                     │
│  {                                                            │
│    extracted_facts: [{slot_key, value, confidence}],          │
│    new_slots: [{key, description, importance, parent}],       │
│    waived_slots: [{key, reason}],                             │
│    next_question: {                                           │
│      text, targets_slots[], technique, input_mode,            │
│      options[]?, why_asking                                   │
│    },                                                          │
│    or: {ready_to_conclude: true}                              │
│  }                                                            │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌─ 3. PERSIST (Postgres, immediate) ───────────────────────────┐
│  Apply facts, spawn/waive slots, append to episodic log       │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌─ 4. RUBRIC CHECK (every N turns, cheap model) ───────────────┐
│  Score coverage. Emit open_gaps[]. Verdict: continue/conclude │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
                   Question → user
```

**Critical property:** turn 40's context window is the same size as turn 4's. The conversation grows in Postgres, not in context. Compaction is a non-event because canonical state never lived in the conversation.

---

## 4. Question Generation Techniques

The Interrogator picks a technique per turn — it's a field in the structured output, which makes technique choice *inspectable and tunable*, not a black box.

| Technique | When the Interrogator selects it | Shape |
|---|---|---|
| **Free elicitation** | Opening, or entering a fresh area | "When you picture someone using this, what are they doing right before they open it?" |
| **Laddering** | An answer names a preference or attribute; the *reason* matters more than the stated thing | "You want it real-time — what breaks if it's 30 seconds delayed?" Climbs attribute → consequence → value |
| **Concretization** | Answer contains a vague quantifier ("a lot", "fast", "scalable", "secure") | "Roughly how many people on day one — 10, 100, or 10,000?" |
| **Contrast / triadic** | User can't articulate preference directly | "If you had to cut either the dashboard or the notifications to ship a month earlier — which goes?" |
| **Assumption surfacing** | Interrogator inferred something unstated | "I'm assuming there's no existing system to integrate with. Right?" |
| **Contradiction resolution** | Two facts in the brief conflict | "Earlier you said two weeks, now you're describing about six weeks of features. Which is the real constraint?" |
| **Scenario projection** | Testing whether the user has thought past v1 | "It's a year in and it worked. What does that look like?" |
| **Expertise probe** | Calibrating skill level (§5) | "Have you deployed something with a database before, or would that be new?" |

### Laddering rules

Laddering is the highest-value technique and the easiest to ruin. Enforced constraints:

- **Max depth 3 per chain.** The documented failure of laddering is asking "why is that important" six times in a row — it stops feeling like curiosity and starts feeling like interrogation.
- **Never use the literal word "why" twice in a row.** Vary the form: "what breaks if not?", "what would you lose?", "what happens instead?"
- **Terminal value = stop.** When the user's answer is circular or amounts to "it just is," that thread is done. Mark the chain COMPLETE and move on. That's the signal, and it's reliable.
- **Only ladder on slots with importance ≥ 4.** Depth is expensive; spend it where the answer changes the architecture.

### The vagueness trigger

The single highest-leverage rule in the whole system. Any answer containing an unquantified magnitude — *scalable, fast, secure, a lot, eventually, real-time, high-traffic, enterprise-grade* — automatically spawns a PROBE slot demanding a concrete value.

This is where generic AI project planning dies. "It should scale well" accepted at face value produces a Kubernetes recommendation for a project with 40 users. Grilld refuses to accept the vague form.

---

## 5. Real-Time Expertise Profiling

The system profiles the user's technical sophistication as they talk and adapts both **vocabulary** and **question difficulty**. This solves the difficulty-calibration gap that pure dynamic interviewing suffers from, and it feeds directly into the Scale Calibrator and Skills Curator downstream.

```java
record ExpertiseProfile(
  int overallLevel,               // 1-5, inferred continuously
  Map<String,Integer> domains,    // backend:4, devops:2, frontend:3, product:1
  List<String> namedTechnologies, // things they mentioned unprompted
  List<String> knownGaps,         // things they said they don't know
  boolean prefersJargon           // do they speak in shorthand?
)
```

**Signals:** unprompted technology names, specificity of constraints, whether they distinguish related concepts, self-reported gaps, and how they answer the first technical question.

**Adaptation:**

| Level | Question style |
|---|---|
| 1–2 (non-technical / early) | Plain language, outcome-framed. "Should people be able to log in with their Google account?" |
| 3 (intermediate) | Mixed. Names technologies but explains implications. "Postgres or something like Firebase? The difference matters for how you query later." |
| 4–5 (experienced) | Peer register, dense. "Postgres with JSONB for the flexible fields, or full document store? And are you set on JPA or would you rather drop to jOOQ?" |

An experienced dev asked level-2 questions abandons immediately. A beginner asked level-5 questions abandons immediately. Same failure, opposite cause. This is a retention feature, not a nicety.

---

## 6. Slot Prioritization — What to Ask Next

The Interrogator receives open slots pre-ranked by Java, so its judgment is spent on *phrasing* rather than triage:

```
priority = importance
         × information_gain      // how many downstream slots does this unlock?
         × blocking_factor       // do other open slots depend on it?
         ÷ estimated_effort      // will this take a paragraph to answer?
```

**Hard ordering constraints:**
- `scale_expectation` and `team_shape` are prioritized early — they waive or spawn the largest number of downstream slots, so answering them early shrinks the whole remaining interview
- Never ask a slot whose parent is unfilled
- Never ask two slots from the same DERIVED cluster back to back (topic-hopping feels erratic; so does drilling)
- **Fatigue-weighted:** as turn count rises, only importance ≥ 4 slots get asked; everything else auto-assumes

---

## 7. Termination — Also Dynamic

No fixed question count. The Interrogator concludes when the Rubric Agent's coverage score passes threshold *and* marginal information gain has flattened.

**Three exits:**

1. **Natural conclusion** — rubric passes, high-importance slots filled. Ideal.
2. **Diminishing returns** — last 3 turns produced low-confidence or low-value fills. Interrogator proposes wrapping: "I think I've got enough. Two things I'm still assuming — want to correct them or shall I build?"
3. **User forces exit** — always available, never buried. Everything unfilled becomes an explicit assumption in `ASSUMPTIONS.md`.

**Never trap the user.** An interview that won't end is worse than one that ends early — early-exit output is still useful because assumptions are flagged.

Typical interviews should land at 12–25 turns. Not enforced, but if telemetry shows consistent 40-turn sessions, the vagueness trigger or laddering depth is misconfigured.

---

## 8. The Rubric Agent Against a Dynamic Graph

Rubric scoring can't check "were all questions asked" — there are no questions. It scores **whether the resulting knowledge is sufficient to build from**:

| Dimension | Passing means | Auto-check |
|---|---|---|
| Problem clarity | A stranger could restate the problem and who has it | LLM restates it; user confirms |
| Scope boundedness | An explicit not-doing list exists | ≥2 waived/excluded features recorded |
| Scale concreteness | Real numbers, not adjectives | No unresolved vagueness-trigger slots |
| Technical grounding | Constraints, integrations, skills known | `builder_skillset`, `hard_constraints` filled |
| Success definition | User can state what "working" means | `success_definition` filled, confidence >0.7 |
| Risk awareness | Top 2 risks named | ≥2 risk slots filled |

Failing dimensions emit `open_gaps[]`, which go straight into the Interrogator's next-turn context — so follow-ups target the *weakest dimension*, not a random open slot. This is the antagonistic loop: one agent generating, one agent refusing to accept.

---

## 9. Guardrails

| Failure | Guard |
|---|---|
| Repeats a question | `answered_topics[]` in every context; slots FILLED are structurally unaskable |
| Wanders off-topic | Every question must declare `targets_slots[]` — no slot, no question. Rejected server-side. |
| Interrogates instead of converses | Laddering depth cap 3; technique variety enforced (no same technique 3× consecutively) |
| Asks over-technical questions | Expertise profile gates vocabulary |
| Never ends | Fatigue weighting + diminishing-returns detection + always-visible exit |
| Accepts vague answers | Vagueness trigger spawns mandatory concretization probes |
| Spawns unbounded slots | Cap on open slots (~25); low-importance ones auto-assume when the cap is hit |
| Contradicts itself | Contradiction detection on every fact write; conflicts spawn a resolution slot |

---

## 10. UX

- **Live slot panel** — user watches the brief fill in as they answer. This is the magic moment: they *see* their vague idea becoming a spec in real time.
- **Hybrid input,** decided per question by the Interrogator (`input_mode` field): chips for enumerable answers, free text where texture matters, number input for concretization probes.
- **"Why are you asking?"** on every question, prefilled from `why_asking`. Builds trust and measurably improves answer quality — people answer better when they know what turns on it.
- **"Skip"** on every question → slot becomes ASSUMED with a stated default, visible in the panel and correctable.
- **Resume anywhere.** State is in Postgres; the interview is a project, not a session.

---

## 11. Spring Boot Implementation

```
grilld-interrogation/
├── SlotGraphService        — spawn, waive, fill, prioritize
├── ContextAssembler        — builds per-turn LLM context (the critical class)
├── InterrogatorService     — LLM call + structured output parse
├── VaguenessDetector       — deterministic pre-check before LLM sees the answer
├── ContradictionDetector   — checks new facts against brief
├── ExpertiseProfiler       — updates profile per turn
├── RubricService           — periodic coverage scoring
├── TerminationEvaluator    — diminishing-returns detection
└── CompactorService        — rewrites brief summary at token threshold
```

```sql
slots(
  id, session_id, slot_key, description, origin, status,
  importance, value, confidence, parent_slot_key, depth,
  unlocks TEXT[], evidence_ref, created_at_turn, filled_at_turn
);
CREATE UNIQUE INDEX ON slots(session_id, slot_key);

turns(
  id, session_id, turn_number, question_text, technique,
  targets_slots TEXT[], answer_text, input_mode,
  facts_extracted JSONB, slots_spawned TEXT[], slots_waived TEXT[],
  tokens_in, tokens_out, created_at
);

expertise_profiles(session_id, level, domains JSONB, named_technologies TEXT[], known_gaps TEXT[], updated_at_turn);

rubric_evaluations(id, session_id, at_turn, scores JSONB, open_gaps JSONB, verdict);
```

`turns.technique` and `turns.targets_slots` are what make the interview **debuggable**. When a session goes badly you can replay exactly which technique was chosen at which turn against which slot — and that's your tuning loop.

---

## 12. Prompt Sketch (Interrogator)

```
You are conducting a discovery interview with someone who wants to build a project.

You have NO script. Generate every question from what this specific person has said.

WHAT YOU KNOW SO FAR:
{compacted_brief}

WHAT YOU STILL NEED (ranked):
{open_slots_with_descriptions}

RECENT EXCHANGE:
{last_3_turns}

WHO YOU'RE TALKING TO:
{expertise_profile}

ALREADY COVERED — never revisit:
{answered_topics}

YOUR TURN:
1. Extract every fact from their last answer. Map to existing slots or spawn new ones.
2. Waive slots their answer made irrelevant. Be aggressive — dead questions kill trust.
3. If their answer contained a vague magnitude, spawn a concretization probe.
4. Choose ONE next question. Pick the technique that fits this moment.
5. Match their vocabulary level exactly.

RULES:
- One question. Never stack.
- Reference what they actually said — this must feel like listening, not processing.
- Max 3 levels of "why" on any thread; stop at a terminal value.
- No question without a target slot.
- Vague answers get concretized, never accepted.

Return JSON only: {schema}
```

---

## 13. Open Questions

- **Should the interview open with a question at all,** or with Grilld restating the idea back and asking what it got wrong? Restating first proves it listened and surfaces corrections cheaply — likely the stronger opening.
- **Voice input** matters more here than in the v2 spec suggested. Laddering and free elicitation both reward long, rambling answers, and people ramble far better out loud than into a textarea. Worth prototyping before the text UI is finalized.
- **Should users see the slot graph itself,** or a friendly summary? The raw graph is impressive to a technical user and overwhelming to everyone else — possibly an expertise-gated view.
- **How aggressive should waiving be?** Over-waiving loses information; under-waiving asks dead questions. Instrument `slots_waived` per session early and tune against completion rate.
