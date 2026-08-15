# Learning Grilld's Backend — A Running Log

You don't know Java/Spring Boot yet. This doc is updated every time something new gets added to the project — what got built, why, and how it connects to everything else. Read it top to bottom for the story so far, or jump to a section when you want to understand a specific file.

---

## The big picture first

Two separate programs make up Grilld's backend (see `docs/README.md` for the full reasoning):

- **`grilld-backend/`** — written in **Java**, using a framework called **Spring Boot**. This is what you're learning about below. It handles logins, billing, and is the only thing allowed to write to the database.
- **`grilld-ai-service/`** — written in **Python**, not started yet (that's Phase 3+). It's where all the AI/agent logic lives.

Spring Boot is a framework for building web backends in Java. "Framework" means: instead of writing everything from scratch (how to listen for web requests, how to talk to a database, how to log a user in), you write much smaller pieces and Spring Boot wires them together and runs the plumbing for you. Almost everything you'll see below is either (a) telling Spring Boot "here's a piece of my app" or (b) telling it "here's how to configure a piece it already provides."

---

## Repo layout so far

```
/
├── README.md               — quick pointer into docs/
├── LEARNING.md              — this file
├── docs/                    — the full spec, decisions, and (later) per-phase docs
├── docker-compose.yml       — starts a local database for development
├── .gitignore                — tells git which files NOT to track (build output, secrets, etc.)
└── grilld-backend/           — the Java/Spring Boot app (everything below lives here)
```

---

## `grilld-backend/pom.xml` — the project's ingredient list

Every Java project needs a build tool that knows how to compile the code and fetch any external libraries it depends on. Grilld uses **Maven**. `pom.xml` ("Project Object Model") is Maven's config file — it's XML, which just means everything is wrapped in `<tags>` like HTML.

Three things live in it:

1. **Which version of Spring Boot / Java we're using** (the `<parent>` block at the top — `4.1.0`, Java `26`). Spring Boot's "parent" POM is a trick that lets us list dependencies below *without* specifying their exact version — Spring Boot has already tested a compatible set of versions for us and picked them.
2. **Dependencies** — external libraries the app needs. Each `<dependency>` block is one library. For example `spring-boot-starter-data-jpa` gives us the ability to talk to a database using Java objects instead of raw SQL. A "starter" is Spring's bundling convention — one starter dependency actually pulls in several related libraries that work together.
3. **`mvnw`** (in the project folder) — a small script so you don't need Maven installed globally on your machine; running `./mvnw <command>` downloads the right Maven version automatically the first time.

**Current dependencies and why each is there:**

| Dependency | Plain-English purpose |
|---|---|
| `spring-boot-starter-data-jpa` | Lets Java classes represent database rows ("entities"), so you write `user.getEmail()` instead of SQL |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | Runs our database migration files automatically on startup (see below) |
| `spring-boot-starter-security-oauth2-client` | The "log in with Google" flow |
| `spring-boot-starter-security-oauth2-resource-server` | Checks that a login token (JWT) presented on an API request is genuine |
| `spring-boot-starter-validation` | Lets us mark fields as `@NotNull` etc. and get automatic input validation |
| `spring-boot-starter-webmvc` | The actual "listen for HTTP requests and respond" part — this is what makes it a web server at all |
| `spring-boot-starter-actuator` | Adds production-standard endpoints like `/actuator/health` for free, so hosting platforms can check if the app is alive |
| `springdoc-openapi-starter-webmvc-ui` | Auto-generates API documentation (a webpage at `/swagger-ui.html`) from our code |
| `postgresql` | The actual database driver — the low-level code that speaks Postgres's network protocol |
| Testcontainers (`spring-boot-testcontainers`, `org.testcontainers:*`) | Lets our automated tests spin up a *real* temporary Postgres database in Docker, instead of testing against a fake |
| Everything ending in `-test` | Same library, but only included when running tests, not in the real app (`<scope>test</scope>`) |

---

## `GrilldBackendApplication.java` — the front door

```java
@SpringBootApplication
public class GrilldBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(GrilldBackendApplication.class, args);
    }
}
```

This is the file Java actually runs to start the whole app. `@SpringBootApplication` is an **annotation** — annotations are Java's way of attaching metadata to code that a framework reads and acts on (they start with `@`). This particular one tells Spring Boot three things at once: "scan this package and everything below it for more Spring pieces," "auto-configure things based on what's on the classpath" (e.g. it sees the Postgres driver dependency and sets up a database connection automatically), and "this is the entry point."

You will basically never need to touch this file again — new features get added as new files elsewhere, and Spring finds them automatically because of where they live (package scanning) or what they're annotated with.

---

## `docker-compose.yml` — a real database, running locally

Grilld needs Postgres (a real database) to run against, even on your own laptop. Rather than installing Postgres directly on Windows, we use **Docker**: a tool that runs a lightweight, pre-packaged copy of Postgres in an isolated "container," without touching anything else on your machine.

`docker-compose.yml` describes what container(s) to run. Ours has one: `postgres:18-alpine` (Postgres version 18, `alpine` = a smaller/lighter base image). Run `docker compose up -d postgres` from the repo root and it starts in the background; `docker compose down` stops it.

One real gotcha we hit: Postgres 18 changed *where inside the container* it expects to store its data compared to older versions — our first attempt pointed at the old location and the container crash-looped on startup. Fixed now; noted here because it's a good example of "the internet's advice from a year ago can be subtly wrong" — always worth checking current docs rather than assuming.

---

## Flyway migrations — how the database schema is created

**The problem Flyway solves:** a database's structure (which tables exist, what columns they have) needs to change over time as the app grows, and every developer's local database — plus every real deployment — needs to end up with the *exact same* structure, applied in the *exact same order*. Doing this by hand (typing SQL commands manually) doesn't scale and is easy to get wrong.

**How it works:** you write plain `.sql` files under `grilld-backend/src/main/resources/db/migration/`, named `V1__something.sql`, `V2__something_else.sql`, etc. Every time the app starts, Flyway checks a special table it maintains (`flyway_schema_history`) to see which migration files it has *already* run against this specific database, and runs any new ones it finds, in order. Once a migration has run, you never edit that file again — if you need to change something, you write a *new* migration (`V2__...`) that alters what `V1` created.

**`V1__init_schema.sql`** is our first (and so far only) migration. It creates all 16 tables from the data model described in `docs/product-and-architecture.md` §9 — everything from `users` down to `credit_transactions` — in one file, since we deliberately front-loaded the entire schema instead of adding tables piecemeal per phase (see that doc's §12 for why).

A few Postgres/SQL ideas worth knowing if you're new to databases:
- **`UUID`** — a randomly-generated unique ID (looks like `a1b2c3d4-...`), used instead of a simple counting number (1, 2, 3...) as the primary key for every table. Harder to guess than sequential numbers, which matters once these IDs show up in URLs.
- **`JSONB`** — a column that stores a whole JSON object, when the data's shape is flexible/nested rather than fitting neatly into normal columns. Used for `project_briefs.brief_json`, for example.
- **Foreign keys** (`REFERENCES users(id)`) — a column that must match an ID that exists in another table, enforced by the database itself. `ON DELETE CASCADE` means "if the parent row gets deleted, delete this row too" (e.g. deleting a user deletes their sessions).
- **`CHECK` constraints** — restrict a column to a fixed set of values (e.g. a session's `status` can only ever be `'ACTIVE'`, `'READY_FOR_GENERATION'`, `'COMPLETED'`, or `'ABANDONED'` — anything else is rejected by the database itself, not just by app code).

---

## Production-grade foundations (added before writing any real features)

Before building actual login/business logic on top of the bare skeleton, a few standard "every real Spring Boot app has this" pieces were added:

### `application.properties` and `application-local.properties`
Configuration files — settings the app reads at startup (database address, port number, etc.), kept *out* of the Java code so they can change per environment without recompiling anything.

We split into two files on purpose:
- `application.properties` — the "default" settings. It has **no fallback database password** — if you run the app without telling it where the database is, it fails immediately with a clear error, rather than silently trying to guess.
- `application-local.properties` — extra settings that only apply when you explicitly opt into the `local` **profile** (`SPRING_PROFILES_ACTIVE=local`). This is where the convenient defaults matching `docker-compose.yml` live. Profiles are Spring's mechanism for "here's a named bundle of settings you can switch on."

Why bother splitting them? So a hardcoded development password can never accidentally end up being what a real deployment uses just because nobody remembered to override it.

### `common/exception/` — one shape for every error
Three small files:
- `ErrorResponse.java` — a `record` (a Java shorthand for "a simple data holder with no behavior") describing exactly what fields an error response contains: timestamp, HTTP status code, an error label, a message, and the URL path that failed.
- `ResourceNotFoundException.java` — a custom exception type we throw ourselves, e.g. "no session with that ID."
- `GlobalExceptionHandler.java` — annotated `@RestControllerAdvice`, which means "catch exceptions thrown anywhere in any controller, application-wide." Instead of every single API endpoint needing its own error-handling code, this one file catches known problem types (not-found, validation failure, access denied) and turns them into the same consistent `ErrorResponse` shape, and catches anything *unexpected* too, logging it and returning a safe generic message instead of leaking a raw stack trace to whoever called the API.

### `config/OpenApiConfig.java`
Configures `springdoc-openapi` (the dependency mentioned above) — mostly just the title/description shown on the auto-generated API documentation page, plus telling it that most endpoints require a Bearer token (see JWT, below).

### The rewritten test (`GrilldBackendApplicationTests.java`)
The very first thing Spring Initializr generates is a placeholder test that just checks "does the app start without crashing." We replaced it with something that actually proves something useful:
- `@Testcontainers` + `@Container` + `@ServiceConnection` — this trio tells the test "start a real, temporary Postgres in Docker before running, and automatically point the app at it." No manual setup, and it's thrown away after the test finishes.
- The test then checks that after Flyway runs, the database contains *exactly* the 16 tables we expect — not just "some tables," the precise list. If a future migration accidentally drops a table or a typo creates a wrong one, this test fails immediately instead of the bug surfacing later.

This matters because it's a genuinely different kind of test than "trust me, I ran it manually once." Anyone (including a future you, or me in a later session) can run `./mvnw test` and get a real answer about whether the schema is still correct.

---

## Terms you'll keep seeing

| Term | What it means |
|---|---|
| **Annotation** (`@Something`) | Metadata attached to a class/method/field that a framework reads and reacts to. Doesn't run itself — it's a signal Spring looks for. |
| **Bean** | Any object that Spring creates and manages for you (instead of you writing `new Thing()` yourself). Usually created by putting `@Bean`, `@Service`, `@Component`, `@Repository`, or `@Configuration` on something. |
| **Dependency Injection** | Instead of a class creating the objects it needs itself, Spring hands ("injects") them in — usually via the constructor. This is *why* Spring apps are easy to test: you can hand in a fake version of something during a test. |
| **Repository** | A Spring Data interface that gives you database read/write methods (`save`, `findById`, ...) for free, just by declaring the interface — no SQL required for the common cases. |
| **Controller** | A class that handles incoming web requests (`GET /api/v1/me`, etc.) and returns a response. |
| **Entity** | A Java class that represents one row of a database table. |
| **DTO** (Data Transfer Object) | A plain class used to shape what actually goes over the network in a request/response — kept separate from entities so internal database structure isn't directly exposed to the outside world. |

*(This table grows as new concepts show up.)*

---

## Auth: logging in with Google, and how the app recognizes you afterward

This is the last piece of Phase 1. Two different things are happening, and it's worth keeping them mentally separate:

1. **Proving who you are** — happens once, when you log in, via Google.
2. **Proving it on every later request** — happens on every single API call after that, via a token, *without* asking Google again.

### The two new packages

- **`user/`** — `User.java` is the entity (one row = one user). `UserRepository.java` is the Spring Data interface for reading/writing that table. `UserService.java` holds the one real piece of logic so far: "log in with this Google account → find the matching user, or create one if this is their first time." `MeController.java` + `UserResponse.java` are the first real API endpoint: `GET /api/v1/me`, which returns your own account info — but *only* if you're logged in.

- **`auth/`** — everything about *how* login and tokens work, kept separate from the `User` data itself:
  - **`SecurityConfig.java`** — the single file that decides, for every incoming request, "does this need login at all, and if so, which method proves it." `/actuator/health`, the API docs, and the login URLs are left open (`permitAll()`); everything else requires a valid token.
  - **`JwtConfig.java`** — generates a cryptographic keypair (RSA) used to *sign* tokens. Signing means: nobody can forge a valid token without this private key, and anyone holding the matching public key can verify a token is genuine without being able to create fake ones themselves.
  - **`TokenService.java`** — turns a `User` into an actual token string (a **JWT** — JSON Web Token. It's a compact, signed, self-contained bundle of claims like "this is user X, issued at time Y, expires at time Z" that the client presents on every request afterward, instead of a database-backed session).
  - **`OAuth2LoginSuccessHandler.java`** — the bridge between the two halves. Runs exactly once, right when Google confirms your identity: looks up/creates your `User` row, mints a token via `TokenService`, and sends it back.

### The actual flow

```
1. Browser visits /oauth2/authorization/google
2. Spring Security redirects to Google's real login page
3. You log in on Google's site (we never see your password - Google handles it entirely)
4. Google redirects back to our app with proof of who you are
5. OAuth2LoginSuccessHandler runs: find-or-create the User, issue a JWT
6. You get back: {"token": "eyJhbGc..."}
7. Every API call after this includes: Authorization: Bearer eyJhbGc...
8. SecurityConfig checks that token's signature against our public key on every request
```

Steps 1–6 happen once per login. Step 7–8 happen on literally every API call, and *don't* need to talk to Google again — that's the entire point of a self-issued token instead of asking Google to vouch for you every single time.

### Why "OAuth2" and "JWT" are two different things, not one

It's easy to conflate these since they show up together constantly. **OAuth2** is the *login handshake protocol* — the back-and-forth redirect dance in steps 1–4 above, which could end with Google, GitHub, or any other provider. **JWT** is just a *token format* — a way of packaging "here's who this is and when it expires" that can be verified without a database lookup. We use OAuth2 to talk to Google once, and JWT for everything our own app hands out afterward. You could have either one without the other.

### A known, deliberate gap

`JwtConfig` generates a fresh signing key every time the app starts, instead of loading a saved one. That means every time the app restarts, every previously issued token stops working (everyone has to log in again). This is fine for a project with no real users yet, and called out explicitly rather than silently shipped — see the comment at the top of `JwtConfig.java`.

---

## Phase 2: the memory layer

Phase 1 built the platform (auth, schema). Phase 2 is about *remembering an interview between turns* — the actual thing that makes Grilld's interrogation different from a normal chatbot, per `docs/product-and-architecture.md` §1: state lives in Postgres, never in a growing conversation.

### New packages

- **`session/`** — `DiscoverySession` (one interrogation), `Turn` (one question/answer exchange), `ExpertiseProfile` (not populated yet — that's Phase 4's job).
- **`slot/`** — `Slot` (one atomic fact the interview needs), `SlotWaive` (a record of a slot being skipped and why), `RubricEvaluation` (not populated yet — also Phase 4).
- **`brief/`** — `ProjectBrief`, the single canonical "what we know about this project so far" record.
- **`memory/`** — the new part this section is really about.

### Why some JSON/array columns aren't Java classes

A few entity fields (`Turn.factsExtracted`, `Slot.unlocks`, `ProjectBrief.briefJson`) are stored as raw JSON text or a plain list, rather than as a proper nested Java class the way you might expect. This is deliberate: their real shape is defined by *the Python AI service's* output contract, which doesn't exist yet (Phase 3+) and will keep evolving. If Java modeled every field of a brief as its own class, every time Python's output format grows a new field, someone would need to update Java too. Storing it as JSON keeps that contract owned by one side (Python) without Java needing to know its internals — Java just stores and forwards it.

### `ProjectBrief`'s `@Version` field — a neat trick worth understanding

Two browser tabs open on the same project could, in theory, both try to save an edit to the same brief at the same time. Without protection, the second save would silently overwrite the first person's changes without anyone knowing. `@Version` is a one-line JPA annotation that fixes this: Hibernate tracks a counter on the row, and if you try to save based on stale data (someone else already changed it since you read it), your save fails loudly instead of overwriting silently. This is called **optimistic locking** — "optimistic" because it doesn't lock anything upfront; it just checks at save time whether anyone else got there first.

### `WorkingContextAssembler` — the piece the whole "no context bloat" idea depends on

This is the class described in `docs/product-and-architecture.md` §8 as "the critical class." Every single turn of the interview, instead of the AI seeing the *entire* conversation history so far (which is how a normal chatbot works, and why long chats get slow/expensive/confused), it gets handed a freshly-assembled `WorkingContext`: the session's raw idea, a compacted brief summary, only the last 3 exchanges verbatim, the still-open questions (ranked, see below), and a list of topics already covered (so nothing gets asked twice). Turn 40 of an interview costs exactly the same to process as turn 4, because nothing accumulates — it's rebuilt from the database every time, not carried forward in memory.

### `SlotPrioritizer` — deciding what to ask about next

With potentially dozens of open questions ("slots") at once, something has to decide which one matters most right now. `SlotPrioritizer` implements the formula from `interrogation-engine.md` §6: a slot's priority is its importance, multiplied by how much unlocking it (filling it in) would unblock, multiplied by how many *other* pending questions are waiting on it specifically. The Java side does this ranking math; the Python side (once it exists) only has to decide *how to phrase* the highest-priority question, not figure out which one to ask.

---

## The stub AI service, and proving the whole loop works before Python exists

Phase 3 (a separate Python program) is where the real AI logic lives — but that's not built yet, and Phase 2 needs to prove the *Spring* side of the pipeline actually works. The trick: define an **interface** (`AiServiceClient`) describing exactly what Spring needs from "whatever answers a question" — one method, `nextTurn(context) → result` — and write a fake implementation (`StubAiServiceClient`) that returns believable canned responses instead of calling a real AI.

This is a very common pattern, worth knowing by name: **programming to an interface**. Nothing in `SessionService` (the class that actually orchestrates a turn) knows or cares whether `AiServiceClient` is the stub or a real Python-backed implementation — it just calls `nextTurn()` and works with whatever comes back. When Phase 3 builds the real thing, only one small file changes (a new class implementing the same interface); `SessionService`, `SessionController`, and everything downstream stays untouched. This is *why* it's safe to build "the plumbing" before "the brain" exists — the seam between them is explicit and narrow.

### The new pieces

- **`aiservice/`** — `AiServiceClient` (the interface), `StubAiServiceClient` (the fake), `InterrogatorTurnResult` (the shape of a response — matches the real contract the Python Interrogator will eventually return, so nothing here needs to change later either).
- **`session/SessionService.java`** — the actual orchestration: start a session (seed the 8 universal questions, ask the AI service for an opening question), and answer a turn (record the answer, ask the AI service what's next, save whatever facts/new questions came back, ask the next question — or conclude).
- **`session/SessionController.java`** — the two HTTP endpoints (`POST /api/v1/sessions`, `POST /api/v1/sessions/{id}/answer`) that make this reachable at all.

### A real bug the test caught (worth understanding, not just noting)

Writing `SessionFlowIntegrationTest` immediately caught a genuine bug: the stub's example "new question" reused a slot key (`scale_expectation`) that was already one of the 8 seeded-at-start questions, so the database rejected it as a duplicate. This is exactly what integration tests are *for* — not proving the happy path works, but surfacing the seams where two pieces of code (seeding logic and stub logic, written separately) made incompatible assumptions about the same data. Fixed two ways: the stub now asks about a genuinely new topic, and `SessionService` itself now defensively ignores a "new slot" if that key already exists — because a *real* AI service could hit the same situation, not just this test's fake one.

### A genuinely surprising Spring Boot 4 discovery

While wiring up JSON handling for merging brief data, a piece of code that should have worked (`@Autowired ObjectMapper`) failed with "no such bean." The reason turned out to be significant: **Spring Boot 4 has quietly moved to a new, different JSON library internally, called Jackson 3** (its classes live under a new name, `tools.jackson`, instead of the classic `com.fasterxml.jackson` everyone's used for over a decade). Spring auto-configures an instance of the *new* one, which is a different Java type than the *old* one — so asking for the old type finds nothing, even though "a Jackson ObjectMapper" conceptually exists. The fix was to define our own bean explicitly (`JacksonConfig.java`), using the classic type — the right call for now since other libraries this project depends on (the API docs generator) haven't moved to Jackson 3 yet either.

---

## The real Google login, end to end — and a genuinely confusing bug along the way

With real Google OAuth credentials in hand, the whole login flow got driven through an actual browser: Google's real account chooser (correctly showing "to continue to grilld," proving the app's registered correctly), a real consent screen, a redirect back with a real signed JWT, and `/api/v1/me` returning the right data. Logging in twice with the same account produced two different tokens but the *same* user id both times — proof `UserService.findOrCreateFromGoogle` is finding an existing row, not quietly creating a second one every login.

Getting there hit a real, worth-understanding bug first: every browser request to the app failed with `HTTP 400 — Request header is too large`, even though the exact same endpoint worked fine when called with `curl`. The difference was cookies — a real browser sends everything it's accumulated for a site, and across many dev-server restarts this session, that had grown past Tomcat's default 8KB header limit; `curl` sends almost none, so it never hit the wall. The fix looked obvious (`server.max-http-header-size`, a real Spring Boot property) but did *nothing* — because that exact property name was quietly deprecated back in Spring Boot 3.0 in favor of `server.max-http-request-header-size`, and the old name is now silently ignored rather than erroring. This is a good example of a broader lesson: when a fix that should obviously work doesn't, and there's no error message telling you why, suspect that the thing you configured and the thing actually being read aren't the same name anymore — frameworks rename things across major versions more often than tutorials get updated.

---

## What's next

Phase 1 (repo, Spring Boot skeleton, full schema, Google login + JWT) and Phase 2 (memory layer + a provably-working session/turn pipeline, backed by a stand-in AI service) are both **fully verified**, including a real end-to-end Google login — see `docs/phases/phase-1/` and `docs/phases/phase-2/` for the complete, all-checked verification checklists. Phase 3 is next: the actual Python AI service, and swapping `StubAiServiceClient` for a real implementation.
