# Clinic Management SaaS

A multi-tenant clinic management platform: clinics (tenants) manage their own
providers, rooms and appointment calendar; patients book through a patient
portal; front-desk staff book/reschedule/check-in walk-in patients at the
counter; providers document visits and order labs; a lab-orders module
covers rate configuration, the full order lifecycle, and payments.

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

See `CLAUDE.md`'s "Tenancy model" section for the full write-up. (Phase 1
sketched a cross-tenant "marketplace" availability search here; phase 2
dropped that in favor of picking a clinic first, then browsing its own
availability - clinics define their own appointment types independently,
with no shared cross-clinic identity to search by.)

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

To try the booking flow, seed a demo provider/room/appointment type/
working-hours first (or create them yourself via the phase-5 CRUD
endpoints - `POST /api/providers`, `/api/rooms`, `/api/appointment-types`,
`/api/fee-policies` - as `demo-clinic-admin`):
```bash
docker compose exec -T postgres psql -U clinicops -d clinic_management < infra/postgres/seed-demo-scheduling-data.sql
```

To try the provider clinical flow (phase 4), you also need a demo provider
login, and to try platform-admin onboarding (phase 6) you need a demo
`platform_admin` login - neither is seeded by default alongside
`demo-clinic-admin`/`demo-patient` in `realm-export.json`. On a fresh
environment, add the user there (`realmRoles: ["provider"]` or
`["platform_admin"]`) before first boot - `start-dev --import-realm` only
imports a realm that doesn't already exist yet, so this only works before
the realm has ever been created. Against an already-running instance,
create it live instead (same shape as `create-demo-clinic.sh`'s own
admin-API calls): create the user via Keycloak's admin REST API and assign
the role. For a provider, also log in once (to auto-provision its
`app_users` row) then `POST /api/providers/{id}/link-login` (as
`demo-clinic-admin`) to link it to the seeded "Dr. Demo Provider" row - or,
now that phase 6 exists, `platform_admin` itself never needs an
Organization membership at all (it's cross-tenant by design).

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

**Also note:** this machine has a native Windows `postgresql-x64-17`
service permanently holding `:5432` (unrelated to either project's own run
modes) - `docker-compose.yml`'s postgres service maps to host port `5433`
instead (`5433:5432`). This only affects a host tool connecting from
outside docker (use `localhost:5433`); every container-to-container
connection is unaffected.

## What's built so far

- **Phase 1** - the infra/auth skeleton. Live-verified against a real
  `docker compose up` stack and a real browser login as both
  `demo-clinic-admin` and `demo-patient`.
- **Phase 2** - the patient booking flow: appointment types, provider
  working hours, lazy slot generation, the Redis-lock + DB-write booking
  flow with idempotency, all three channels (`patient_portal`/`front_desk`/
  `guest`), patient auto-provisioning, the public appointment-tracking
  endpoint, and bounded recurring-appointment series. Live-verified end to
  end through a real browser session for every channel plus a full and a
  partial-conflict recurring series (confirmed idempotent on retry).
- **Phase 3** - reschedule/cancel with fee-tier calculation, and the full
  check-in state machine (`booked -> checked_in -> roomed -> with_provider
  -> checked_out`, plus `no_show`). Live-verified end to end: a tiered
  cancellation fee, a reschedule moving an appointment to a new slot, the
  full five-state check-in sequence (idempotent re-calls, out-of-order
  409s), the identity check (mismatch, match, and "no ID on file" all
  behaving as decided), and manual no-show.
- **Phase 4** - the provider clinical flow: a provider-scoped
  `GET /api/my-schedule` worklist, encounter documentation (chief
  complaint/assessment/plan, upsert semantics), and a full-replace
  prescription list. Live-verified end to end: the encounter status gate
  (only `with_provider`/`checked_out`), a create-then-update on the same
  appointment confirmed as one row (not duplicated), prescriptions fully
  replacing rather than merging, the provider-ownership 403 against a
  second real provider account, and the `clinic_admin` override.
- **Phase 5** - clinic-admin config: full CRUD for providers/rooms/
  appointment-types/fee-policies/provider-working-hours, `PATCH`-equivalent
  clinic settings and branding (`ClinicSettingsService.resolve` - the
  merge point every consumer, including `RescheduleService`, now reads),
  and a provider-login link/unlink endpoint. Live-verified end to end:
  create/deactivate/reactivate for each resource, a real fee-policy
  delete, the new public `GET /api/clinics/{id}/appointment-types` proven
  genuinely anonymous through `node-bff` with zero cookies, a live clinic
  -settings override that actually changed the reschedule notice-hour gate
  (not just present in code), and re-linking a provider's login to a
  different provider row and back.
- **Phase 6** - platform-admin onboarding: `POST /api/platform/clinics`
  creates a real Keycloak Organization (via a plain `RestClient`, not the
  `keycloak-admin-client` library) then the local `clinics` row, plus
  deactivate/reactivate. Live-verified end to end: a brand-new clinic
  really appeared in Keycloak's own organization list and locally with the
  right alias; a repeated alias 409'd and created zero orphaned orgs;
  deactivating the real demo clinic immediately 403'd its own admin's
  staff calls and 409'd a guest booking against it (both pre-existing
  enforcement, reached for the first time via a real toggle), and
  reactivating restored both.
- **Phase 7** - the lab orders module: lab test rate configuration, the
  full order lifecycle (`requested`/`ordered -> specimen_collected ->
  in_transit -> resulted -> reviewed`, or `cancelled`), a patient-initiated
  request -> staff confirm-and-order flow, public two-factor order
  tracking, and a shared `Payment` entity/controllers for both
  appointments and lab orders (closing a gap open since phase 1). This is
  the last phase in the original kickoff spec's phase plan. Live-verified
  end to end: snapshotted multi-test pricing, a missing-rate 400, the full
  status happy path with idempotent re-calls and out-of-order 409s, the
  `collect-specimen` identity check (both a mismatch and no-ID-on-file
  reject it, the deliberate opposite of check-in's own convention), a
  second real provider account reading a resulted-but-unreviewed value, a
  cancellation fee at the clinic's zero-notice tier, public tracking
  through node-bff with zero cookies (status/timestamps only, never result
  values), a recorded lab-order payment, and the full patient
  request -> confirm-and-order -> `GET /api/my-lab-orders` round trip.
- **Frontend phase A** - the backend's 7 phases are all built, so this
  session also built the first real frontend: an actual `react-router-dom`
  routing shell, a role-aware nav bar, per-clinic branding (fetched live,
  applied as CSS custom properties), and one real, live-data dashboard per
  role (patient/front_desk/provider/clinic_admin/platform_admin). No
  booking/CRUD/check-in UI yet - see "Known gaps". Live-verified end to
  end as all five demo roles: each dashboard showed genuinely real counts
  and lists (not placeholders), a clinic's actual branding colour rendered
  for staff and correctly did not for `patient`/`platform_admin`,
  wrong-role redirects and the 404 page both worked, and switching roles
  via the app's own "Log out" button cleanly ended the Keycloak session
  each time.
- **Frontend phase B** - the patient/guest booking flow, full lifecycle:
  browse clinics/providers/appointment types (all newly public - added a
  small `GET /api/clinics/{id}/providers` endpoint, the one thing missing
  to make this possible at all), pick a slot, book (patient or guest),
  view/cancel/reschedule (patient), and public ref+phone tracking for a
  guest with no account. Also added the `startTime`/`endTime` a patient's
  own appointments were missing entirely until now (`GET /api/
  my-appointments`, `/{id}` only - staff endpoints untouched). Live
  -verified end to end as `demo-patient` and as a genuinely anonymous
  guest: real booking, reschedule, cancel, and a real 409-slot-conflict
  race (deliberately reproduced via a raw API call between selecting a
  slot and confirming) - which caught and fixed a real bug live (an error
  banner that a 409 handler was accidentally unmounting before it could
  ever be seen).

Backend is fully built (all 7 kickoff-spec phases); the frontend has a real
shell, one dashboard per role, and the full patient/guest booking lifecycle
- everything else (front-desk/provider/clinic-admin/platform-admin/lab
-order UI) is still unbuilt.

See `CLAUDE.md` for the full phase plan, every design decision and why, and
a running log of what's verified and how.

## Known gaps

- No initial `clinic_admin` user provisioning as part of clinic onboarding
  (deliberate scope decision) - a freshly onboarded clinic needs someone to
  manually create/assign its first `clinic_admin` in Keycloak before it's
  actually usable.
- No PHI-access audit log in v1 (deferred by decision - see `CLAUDE.md`).
- Payments are recordable (phase 7) but purely as a manual staff-entered
  record - no payment gateway, no refund flow, and cancellation/reschedule
  fees are still only computed and recorded, never auto-charged. Email is
  a stub (outbox rows are written, nothing sends them yet).
- The frontend has a real shell, one dashboard per role, and the patient/
  guest booking lifecycle (phases A/B), but still no front-desk booking,
  check-in, encounter-documentation, clinic-admin settings/CRUD, lab-order,
  payment-recording, or platform-admin clinic-creation UI - every one of
  those endpoints is built and live-verified server-side, just not called
  from `node-bff/frontend/` yet. No automated frontend test suite either -
  `npm run build` plus a manual browser walkthrough only.
- **If you edit `node-bff/src/routes/api.js`'s `PUBLIC_ROUTES`, rebuild the
  container** (`docker compose up -d --build --force-recreate node-bff`) -
  a running container keeps serving its old code, which silently breaks
  anonymous access to those routes until it's rebuilt (found the hard way
  in phase 3 - see `CLAUDE.md`).
