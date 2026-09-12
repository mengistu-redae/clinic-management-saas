# Clinic Management SaaS

A multi-tenant clinic management platform: clinics (tenants) manage their own
providers, rooms and appointment calendar; patients book through a patient
portal; front-desk staff book/reschedule/check-in walk-in patients at the
counter; providers document visits. A lab-orders module is planned as a
later, separately-scoped addition.

Modeled on the architecture and conventions of a prior bus-ticketing SaaS -
see `CLAUDE.md` for the full rationale behind each convention.

```
 browser --> nginx --> node-bff (session, OIDC, PKCE) --> spring-boot-api (JWT, tenant-aware)
                 \-> keycloak (login only, admin console)      \-> postgres, redis
```

- **spring-boot-api** - the tenant-aware core API. Stateless, validates
  bearer JWTs, never talks to the browser directly. Flyway migrations only,
  `ddl-auto: validate`.
- **node-bff** - the only thing the browser talks to. Runs the OAuth2
  Authorization Code + PKCE flow against Keycloak, holds tokens in a
  server-side (Redis-backed) session, and forwards them to the API as a
  Bearer header. The browser only ever sees a session cookie.
- **keycloak** - identity provider. One realm (`clinic`), five realm roles,
  and the Organizations feature for grouping clinic staff by tenant.
- **postgres / redis** - primary datastore and appointment-slot locking /
  session store, respectively.
- **nginx** - single entry point on `:80` for local dev; routes everything
  to node-bff except `/keycloak/*`, which reaches the admin console.

## How tenant filtering works

`tenant_id` on `clinics` is the internal tenant key - see the comment at the
top of `V1__init.sql` for why it's not just the Keycloak organization id
directly.

There is deliberately **no** blanket Hibernate multi-tenant filter. Instead:

- `TenantContextFilter` runs once per request (after JWT auth), reads the
  `organization` claim off a staff token, resolves it to a `clinics.id` via
  `ClinicRepository.findByKeycloakOrgId`, and stashes it in the
  request-scoped `TenantContext`. It's also the clinic-deactivation
  enforcement point: a resolved clinic with `status != "active"` gets a
  plain `403` here, before any controller runs.
- Every staff-scoped repository method takes the tenant id **explicitly** as
  a parameter (`findByTenantIdAndId(...)`, `findAllByTenantId(...)`), rather
  than a query implicitly reading `TenantContext` deep inside some shared
  base repository. You can tell whether an endpoint is tenant-scoped or
  cross-tenant just by reading its method signature.
- `TenantContext` is empty (`null`) for `patient` tokens (patients aren't a
  member of any Organization) and for `platform_admin` tokens (acting across
  every tenant). Code that legitimately needs a tenant on the request calls
  `TenantContext.require()`, which throws a clear error instead of silently
  proceeding with `null`.

See `CLAUDE.md`'s "Tenancy model" section for the full write-up, including
the planned marketplace-style exception (cross-tenant appointment-
availability search) once that's built.

## Running it

```bash
docker compose up --build
```

First boot takes a couple of minutes (Keycloak's `start-dev` + realm import
is slow cold). `node-bff` retries its OIDC discovery call with backoff for
exactly this reason - see `discoverWithRetry` in `node-bff/src/auth/oidc.js`.

Then, one-time setup:

1. **Create the demo clinic's Keycloak Organization and grab its id:**
   ```bash
   ./infra/keycloak/create-demo-clinic.sh
   ```
   It prints the org id and the `INSERT INTO clinics (...)` statement to run
   against `clinic_management` (e.g. via `docker compose exec postgres psql
   -U clinicops -d clinic_management`).
2. **Fetch the BFF client secret** from the admin console
   (`http://localhost:8080` -> `clinic` realm -> Clients -> `clinic-bff` ->
   Credentials) and put it in `.env` as `BFF_CLIENT_SECRET`, then
   `docker compose up -d --force-recreate node-bff`.

Then log in at `http://localhost:3000/auth/login` as `demo-clinic-admin` or
`demo-patient` (both `changeme`, both forced to reset on first login - see
`infra/keycloak/realm-export.json`).

Service URLs:

| Service | URL |
|---|---|
| App (via nginx) | http://localhost |
| node-bff directly | http://localhost:3000 |
| Keycloak admin console | http://localhost:8080 |
| spring-boot-api (for debugging) | http://localhost:8081 |

Each service also has its own `start-local.ps1` for running against local
infra instead of `docker compose up` - see the script header comments.

**Note:** this machine also runs `bus-ticketing-saas`, and both projects
default to the exact same ports by deliberate convention (mirrored
architecture) - `:80`, `:3000`, `:5432`, `:6379`, `:8080`, `:8081`. Only one
of the two stacks can be up at a time as-is; stop the other one first
(`docker compose stop` in its directory, and/or stop any natively-running
`start-local.ps1` processes) if `docker compose up` fails with "address
already in use" / "ports are not available".

## What's built so far

Phase 1 only: the infra/auth skeleton (this scaffolding). Live-verified
2026-09-12 against a real `docker compose up` stack and a real browser
login as both `demo-clinic-admin` (lands on a page showing their role and
`GET /api/clinic/me`'s resolved clinic name) and `demo-patient` (lands on
the same page with no clinic panel, since a patient token carries no org
claim). See `CLAUDE.md` for the phase plan, two real infra bugs found and
fixed while getting that login working, and a running log of what's
verified and how.

## Known gaps

- No patient booking flow yet (appointment types, slots, the booking
  channels) - phase 2.
- No PHI-access audit log in v1 (deferred by decision - see `CLAUDE.md`).
- No lab-orders module yet - scoped as its own later session per the
  original kickoff spec (`clinic-management-kickoff-prompt.md`).
- Email is a stub (`LoggingEmailSender`, once the notification outbox is
  built in a later phase).
