# Phase 1 — Setup

## Prerequisites

Already verified present on this machine this session:

| Tool | Version found | Needed for |
|---|---|---|
| Java (Amazon Corretto) | 26.0.1 | Compiling/running the backend |
| Docker Desktop | 29.5.2 | Local Postgres, later Testcontainers/LangGraph server |
| Git | 2.49.0 | Version control |

**One gotcha to know about:** this machine has multiple JDKs installed (17, 22, and Corretto 26), and `JAVA_HOME` points at the Java 17 install while `java` on PATH resolves to Corretto 26. Maven's wrapper (`mvnw`) uses `JAVA_HOME`, so left alone it builds with the wrong JDK. Either fix `JAVA_HOME` permanently (System Properties → Environment Variables), or prefix every `mvnw` command for this project:

```powershell
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
```

No Maven, Python package manager, or Node install needed yet — the Maven wrapper handles Maven, and Python/Node aren't needed until Phase 3 and Phase 9 respectively.

## Running Phase 1 locally

```powershell
# 1. Start Postgres
docker compose up -d postgres

# 2. Run the backend (from grilld-backend/)
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
$env:SPRING_PROFILES_ACTIVE = "local"
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080`. Without Google OAuth credentials configured (see below), everything **except actually logging in** works: `/actuator/health`, `/swagger-ui.html`, and `/api/v1/me` returning 401 without a token.

## Google OAuth credentials — required for the login flow itself

This is the one piece of Phase 1 that needs something from outside the code — an OAuth client registered with Google. Here's how to get it:

1. Go to [Google Cloud Console](https://console.cloud.google.com/) and create a project (or use an existing one).
2. **APIs & Services → OAuth consent screen** — set it up (External user type is fine for development; you don't need to publish it, just add your own Google account as a test user while it's in "Testing" status).
3. **APIs & Services → Credentials → Create Credentials → OAuth client ID.**
   - Application type: **Web application**
   - Authorized redirect URI: `http://localhost:8080/login/oauth2/code/google` — this exact path is Spring Security's default callback URL for a registration named `google`; nothing in the code needs to change to match it, just register this URI with Google.
4. Copy the generated **Client ID** and **Client Secret**.
5. Set them as environment variables before starting the app:

```powershell
$env:GOOGLE_CLIENT_ID = "<paste client id>"
$env:GOOGLE_CLIENT_SECRET = "<paste client secret>"
```

(Or add them to a `.env` file / your IDE's run configuration — anywhere that ends up as an environment variable. They're deliberately **not** committed anywhere, and `.gitignore` already excludes `.env` files.)

Once set, visiting `http://localhost:8080/oauth2/authorization/google` in a browser will redirect to a real Google login, and on success return `{"token": "..."}`.

## Running the automated tests

```powershell
cd grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
./mvnw test
```

No Google credentials needed for this — the test suite (`GrilldBackendApplicationTests`) spins up its own temporary Postgres via Testcontainers and only checks the schema/context-loading, not the live Google login flow (that's not something a repeatable automated test should depend on external credentials for anyway; it's covered by the manual checklist in `TESTING.md`).
