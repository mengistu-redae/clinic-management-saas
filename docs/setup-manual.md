# Setup Manual

Practical "get this running on your machine" guide for a new developer.
For *why* things are built the way they are, see `CLAUDE.md`. For the
full service topology and environment-variable reference, see
`docs/deployment-guide.md`.

## 1. Prerequisites

| Tool | Version | Used for |
|---|---|---|
| Docker Desktop | any recent version, with Compose v2 | Running the whole stack |
| Java | 21 | `spring-boot-api` (no `mvnw` wrapper is checked in) |
| Maven | 3.9+ | Building/running `spring-boot-api` locally |
| Node.js | ≥ 20 | `node-bff` and `node-bff/frontend` |
| npm | bundled with Node 20 | Installing/running both Node projects |
| Python 3 | any recent 3.x, with `python3` on PATH | `infra/keycloak/create-demo-clinic.sh` parses Keycloak admin-API JSON responses with it |

Nothing else needs to be installed globally — Postgres, Redis, Keycloak,
and Mailpit all run as containers.

## 2. First-time setup

```bash
git clone <repo-url>
cd clinic-management-saas
docker compose up --build
```

**First boot is slow** — Keycloak's `start-dev --import-realm` cold-starts
and imports `infra/keycloak/realm-export.json` (the `clinic` realm, its 7
roles, and 7 seeded demo users), which can take a couple of minutes.
`node-bff` doesn't fail during this window — its own OIDC discovery call
retries with backoff (`discoverWithRetry` in
`node-bff/src/auth/oidc.js`) specifically so the two services don't need
to win a startup race.

Watch for every container reaching healthy/started:

```bash
docker compose ps
```

### 2.1 Set the real BFF client secret (required before login works)

`realm-export.json` does **not** pin an explicit secret for the
confidential `clinic-bff` client — Keycloak generates a random one on
import, so the `.env.example` placeholder (`changeme-in-keycloak-console`)
will not work as-is. One-time step, after the stack is up:

1. Copy the env file if you haven't already: `cp .env.example .env`
2. Open `http://localhost:8080` → log in to the **master** realm admin
   console (`admin`/`admin` by default) → switch to the **clinic** realm →
   **Clients** → **clinic-bff** → **Credentials** tab → copy the client
   secret shown there.
3. Put it in `.env` as `BFF_CLIENT_SECRET=<the real secret>`.
4. Recreate just `node-bff` so it picks up the new value:
   ```bash
   docker compose up -d --force-recreate node-bff
   ```

`SESSION_SECRET` in the same file can stay as any long random string —
it only needs to be stable for as long as you want existing sessions to
survive a restart.

### 2.2 Create the demo clinic's Organization + local `clinics` row

Keycloak's Organizations feature (used to group clinic staff by tenant)
isn't part of the realm import — it's created live, once, against the
running instance:

```bash
./infra/keycloak/create-demo-clinic.sh
```

This script is **idempotent** (safe to re-run against an already-set-up
environment — it looks the org up by alias instead of failing on a
duplicate-alias 409) and:

1. Creates (or finds) a Keycloak Organization aliased `demo-clinic`.
2. Adds every clinic-scoped demo user (`demo-clinic-admin`,
   `demo-provider`, `demo-front-desk`, `demo-pharmacist`,
   `demo-accountant` — **not** `demo-patient` or
   `demo-platform-admin`, neither of which belongs to an Organization)
   as a member.
3. Prints the `INSERT INTO clinics (...)` statement you still need to run
   by hand — the script only touches Keycloak, never the app's own
   database:
   ```bash
   docker compose exec -T postgres psql -U clinicops -d clinic_management \
     -c "INSERT INTO clinics (keycloak_org_id, name) VALUES ('demo-clinic', 'Demo Clinic');"
   ```
   (`keycloak_org_id` must be the org **alias** — `demo-clinic` —, not the
   internal Keycloak id the script also prints. Keycloak's own
   `oidc-organization-membership-mapper` puts the alias, not the id, in a
   token's `organization` claim — see `TenantContextFilter`'s own
   extraction comment.)

The script's own reliability note is worth keeping in mind: Keycloak's
Organizations REST API is newer than the rest of its admin API and its
exact payload shape has shifted between versions — after running it,
open the Admin Console's **Organizations** section and confirm the org
and its 5 members look right.

### 2.3 Seed demo scheduling data

A real booking flow needs at least one provider, room, appointment type,
and set of working hours. Fastest path — load the bundled seed script
(providers/rooms/appointment-types/fee-policies now have real CRUD too;
this script is just the quickest way to get a first clinic populated
from nothing):

```bash
docker compose exec -T postgres psql -U clinicops -d clinic_management \
  < infra/postgres/seed-demo-scheduling-data.sql
```

Alternatively, create all of the above yourself via the real CRUD
endpoints as `demo-clinic-admin` once logged in.

### 2.4 Log in

Visit `http://localhost/` (through nginx) and log in. All 7 demo users
share the same real, non-temporary password:

| Username | Role |
|---|---|
| `demo-patient` | patient |
| `demo-front-desk` | front_desk |
| `demo-provider` | provider |
| `demo-clinic-admin` | clinic_admin |
| `demo-pharmacist` | pharmacist |
| `demo-accountant` | accountant |
| `demo-platform-admin` | platform_admin |

**Password for all of them: `DemoPass123!`**

To switch between roles in the same browser session, use the app's own
**Log out** button (not just closing the tab) — it cleanly ends the
Keycloak SSO session so the next login prompt is genuinely fresh, rather
than silently re-authenticating the previous user.

## 3. Service URLs

| Service | URL |
|---|---|
| App (via nginx — the real browser-facing entry point) | http://localhost |
| node-bff directly | http://localhost:3000 |
| Keycloak admin console | http://localhost:8080 |
| spring-boot-api (for debugging/Swagger-less direct calls) | http://localhost:8081 |
| Mailpit web UI (every email the app sends) | http://localhost:8025 |
| Postgres (host tools only — psql, a GUI client) | localhost:5433 |
| Redis | localhost:6379 |

**Always verify a change through nginx (`:80`) or node-bff (`:3000`)
directly, never only via a raw curl straight to `spring-boot-api:8081`.**
A public/anonymous route that works against the raw API can still be
broken at the BFF layer (see §6, "`PUBLIC_ROUTES` changes").

## 4. Running each service locally (against already-up infra)

Useful for fast edit/reload cycles without rebuilding a Docker image
each time. Start the infra containers you still need in Docker, then run
the service itself natively via its own `start-local.ps1`.

### 4.1 spring-boot-api

```powershell
docker compose up postgres redis keycloak   # or infra/keycloak/start-native.ps1 instead of the keycloak service
./spring-boot-api/start-local.ps1
```

Runs `mvn spring-boot:run` against `localhost:5432`/`localhost:6379`/
`localhost:8080` (all three overridable via script parameters —
`-DbUrl`, `-RedisHost`, `-KeycloakIssuerUri`, etc.). Note: it talks to
Postgres on `5432`, the plain in-container port — only `docker-compose.yml`'s
own host port mapping is remapped to `5433` for this machine's native
Postgres conflict (see §7). If you're running `postgres` via
`docker compose up postgres`, its host-mapped port is still `5433`, so
pass `-DbUrl "jdbc:postgresql://localhost:5433/clinic_management"`
explicitly in that case.

### 4.2 node-bff

```powershell
./node-bff/start-local.ps1
```

Requires the repo-root `.env` to already have a real `BFF_CLIENT_SECRET`
and `SESSION_SECRET` (see §2.1) — the script refuses to start with a
missing file or a leftover placeholder value, with a clear error message
either way. Unlike `docker-compose.yml`, there's no container-network
split here — the browser and `node-bff` both reach Keycloak via the same
`localhost:8080`, so `KEYCLOAK_ISSUER`/`KEYCLOAK_ISSUER_PUBLIC` are set to
the identical value.

### 4.3 Keycloak natively (optional, instead of the Docker service)

Only relevant if you'd rather not run Keycloak in Docker at all (e.g.
your own Postgres is already native):

```powershell
./infra/keycloak/start-native.ps1
```

Defaults to a native install at `C:\keycloak\keycloak-26.7.1` and a
dedicated `clinicops_keycloak` Postgres database — **not** the
`keycloak` database `docker-compose.yml`'s own postgres service owns.
Copies the repo's `realm-export.json` into that install's
`data/import/` directory before starting, so realm edits made here are
always the source of truth. `--import-realm` is idempotent — re-running
against an already-imported realm just logs "already exists. Import
skipped."

### 4.4 Frontend dev server

```bash
cd node-bff/frontend
npm install
npm run dev
```

Runs Vite's own dev server with hot reload, proxying `/api`/`/auth`
calls to `node-bff` on `:3000` (see `vite.config.js`). For a production
-style build served by `node-bff` itself (what `docker compose up` ships
by default — `src/index.js` serves `frontend/dist` as static files),
use `npm run build` instead and rebuild/recreate the `node-bff` container.

## 5. Running the tests

```bash
# spring-boot-api (from spring-boot-api/)
mvn verify
```
> **Known limitation on Windows**: the Testcontainers-backed integration
> suite (everything extending `AbstractIntegrationTest`) cannot start a
> container on this class of dev machine — a Docker Desktop npipe
> incompatibility, not a code problem. You'll see
> `Could not find a valid Docker environment` for every
> Testcontainers-based test class; the ~88 pure-unit (Mockito-only) test
> classes still run and pass normally in the same `mvn test` invocation.
> The full suite is confirmed green on GitHub Actions' `ubuntu-latest`
> runner (see the CI badge / `.github/workflows/ci.yml`) — treat that as
> the actual pass/fail signal for the Testcontainers-backed tests, not a
> local Windows run.

```bash
# node-bff (from node-bff/)
npm test
```
Node's built-in test runner (`node --test`), no extra framework.

```bash
# frontend (from node-bff/frontend/)
npm test        # vitest run — component/hook tests
npm run build   # vite build — also the fastest way to catch any JSX/import error
```

CI (`.github/workflows/ci.yml`) runs all three as separate parallel jobs
on every push/PR to `main`.

## 6. Known Windows-specific gotchas

- **A native `postgresql-x64-17` Windows service permanently holds port
  `5432`.** `docker-compose.yml`'s own `postgres` service is mapped to
  host port **`5433`** instead (`"5433:5432"`) specifically to avoid
  fighting it. This only matters for a host tool connecting from outside
  Docker (`psql`, a GUI client — use `localhost:5433`); every
  container-to-container connection inside the compose network still
  uses the plain `postgres:5432` hostname/port and is unaffected.
- **nginx caches a stale `node-bff` IP after `--force-recreate`.** After
  `docker compose up -d --build --force-recreate node-bff`, nginx can
  keep returning `502` on every request even once the new container is
  healthy and answers `200` directly on `:3000` — it resolved
  `node-bff`'s container IP once at its own startup and doesn't re-resolve
  on a plain recreate. Fix:
  ```bash
  docker compose restart nginx
  ```
  Always verify a `node-bff`-touching change through `http://localhost/`
  (nginx) after this, not just `:3000` directly.
- **Editing `node-bff/src/routes/api.js`'s `PUBLIC_ROUTES` needs a
  rebuild, not just a restart.** A long-running container keeps serving
  its old code — `docker compose up -d --build --force-recreate node-bff`
  is required for a route's anonymous-access change to actually take
  effect. Verify the specific public endpoint through node-bff's own
  port (or nginx), never only via a direct curl to `spring-boot-api`
  directly — that will pass even when the BFF's own public-route bypass
  is broken or stale.
- **This machine also runs a sibling project, `bus-ticketing-saas`, on
  the exact same default ports** (`:80`, `:3000`, `:5432`→`5433`,
  `:6379`, `:8080`, `:8081` — a deliberate mirrored-architecture
  convention, not an accident). Only one of the two stacks — Docker or
  natively-run — can be up at a time as-is. If `docker compose up` fails
  with "address already in use" / "ports are not available", check for
  the other project's containers first:
  ```bash
  docker ps
  netstat -ano | findstr "LISTENING" | findstr ":80 :3000 :5433 :6379 :8080 :8081"
  ```

## 7. Troubleshooting checklist

| Symptom | Likely cause | Fix |
|---|---|---|
| Login redirects back to the login page in a loop, or the callback errors | `BFF_CLIENT_SECRET` is still the `.env.example` placeholder | §2.1 — fetch the real secret from the Admin Console |
| `403` immediately after a successful-looking login as staff | No `clinics` row for the Organization the token's `organization` claim names | §2.2 — run `create-demo-clinic.sh`, then the `INSERT INTO clinics` it prints |
| A public/guest page 401s through the browser but works via a direct `curl` to `:8081` | `node-bff`'s `PUBLIC_ROUTES` list is stale (container not rebuilt since the route was added/edited) | §6 — rebuild+recreate `node-bff` |
| `node-bff` changes don't seem to take effect at all | nginx is still routing to the old container's cached IP | §6 — `docker compose restart nginx` |
| `docker compose up` fails with a port-in-use error | Native Windows Postgres service, or the sibling `bus-ticketing-saas` stack, already holds that port | §6 |
| `mvn verify` fails every integration test with `Could not find a valid Docker environment` | The documented Windows Testcontainers/npipe limitation — not a real regression | §5 — check CI instead, or run only the pure-unit tests: `mvn test -Dtest='!*IntegrationTest'` |
| Keycloak takes "forever" to come up on first `docker compose up` | Expected — realm import is genuinely slow on a cold start | Wait for `docker compose ps` to show it started; `node-bff` retries discovery meanwhile |
| A brand-new migration doesn't seem to have run | `spring-boot-api` wasn't actually restarted after the new `V<n>__*.sql` file was added | `docker compose up -d --build --force-recreate spring-boot-api`, then check `flyway_schema_history` |
