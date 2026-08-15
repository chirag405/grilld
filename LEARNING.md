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

## What's next

Continuing into auth (Google login + JWT tokens) now, then Phase 2 (the "memory layer" — how the app remembers an in-progress interview). Both will get their own sections appended below as they're built.
