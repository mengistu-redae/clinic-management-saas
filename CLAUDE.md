# CLAUDE.md

Running project memory for clinic-management-saas - what this is, the
architecture, the commands, the tenancy model, and a dated log of every
design decision and why. Updated every session; append and revise in place
rather than rewriting wholesale.

## What this is

A multi-tenant clinic management SaaS. Tenants are **clinics**. Each clinic
manages its providers, rooms, and appointment calendar. Patients book through
a patient portal; front-desk staff book/reschedule/check-in walk-in patients
at the counter; providers document visits. A lab-orders module is planned as
a later, separately-scoped addition (see "Phase plan" below).

Modeled deliberately on the architecture and working conventions of a prior
bus-ticketing SaaS at `D:\git-mengistu\SpringBoot\bus-ticketing-saas` -
wherever a convention here looks arbitrary, it's most likely inherited
verbatim from that project's own hard-won fixes. Its own `CLAUDE.md`/code is
worth checking directly if something here is under-explained.

```
browser --> nginx --> node-bff (session, OIDC, PKCE) --> spring-boot-api (JWT, tenant-aware) --> postgres, redis
                ↘ keycloak (login + admin console only)
```

- **`spring-boot-api/`** - Java 21 / Spring Boot 3.3, Maven. Stateless,
  validates bearer JWTs, never talks to the browser directly. Flyway
  migrations only (`V1__init.sql`, ...), `ddl-auto: validate`.
- **`node-bff/`** - Node ≥20 / Express, npm. The only thing the browser
  talks to. Runs OAuth2 Authorization Code + PKCE (S256) against Keycloak,
  keeps tokens in a Redis-backed server-side session, forwards them to the
  API as a Bearer header. The browser only ever holds a session cookie,
  never a token.
- **keycloak** - one realm (`clinic`); realm roles `platform_admin`,
  `clinic_admin`, `provider`, `front_desk`, `patient`; the Organizations
  feature groups clinic staff by tenant. Config under `infra/keycloak/`.
- **postgres / redis** - primary datastore, and appointment-slot locking
  (`SlotLockService`, phase 2) + session store.
- **nginx** - single entry point on `:80` for local dev, under
  `infra/nginx/`.
- `docker compose up --build` runs the whole stack. First boot is slow
  (Keycloak realm import); the BFF retries OIDC discovery with backoff so
  services don't need to win a startup race.

Each service has its own build tooling and its own `start-local.ps1` for
running against local infra instead of `docker compose up`.

## Commands

```bash
docker compose up --build          # whole stack
docker compose up -d --force-recreate node-bff   # after changing .env's BFF_CLIENT_SECRET

# per-service, against already-running infra (see each script's own header comment)
spring-boot-api/start-local.ps1
node-bff/start-local.ps1
infra/keycloak/start-native.ps1

mvn verify                          # in spring-boot-api/ - Testcontainers-backed integration tests
npm test                            # in node-bff/ - node's built-in test runner
npm run build                       # in node-bff/frontend/

# one-time demo seed data (providers/rooms/appointment-types/fee-policies
# now have real CRUD too, phase 5 - this script is just the fastest way to
# get a first clinic populated from nothing)
docker compose exec -T postgres psql -U clinicops -d clinic_management < infra/postgres/seed-demo-scheduling-data.sql
```

Service URLs (via docker compose): app through nginx on `:80`, node-bff
directly on `:3000`, Keycloak admin console on `:8080`, spring-boot-api on
`:8081` for debugging.

## Tenancy model

- `tenant_id` on `clinics` is the internal tenant key, deliberately **not**
  the same as `clinics.keycloak_org_id` - the app must not be hard-wired to
  Keycloak's id format.
- There is **no** blanket Hibernate multi-tenant filter. Instead:
  - `TenantContextFilter` runs once per request (after JWT auth), reads the
    `organization` claim off a staff token, resolves it to a `clinics.id` via
    `ClinicRepository.findByKeycloakOrgId`, and stashes it in a
    request-scoped `TenantContext`. It is **also the clinic-deactivation
    enforcement point**: if the resolved clinic's `status != "active"`, the
    filter writes a plain `403 "Clinic account is deactivated"` and stops the
    chain. Only staff tokens carry an org claim, so `patient` /
    `platform_admin` requests are untouched.
  - Every staff-scoped repository method takes the tenant id **explicitly**
    as a parameter (`findByTenantIdAndId(...)`, `findAllByTenantId(...)`),
    never an implicit read of `TenantContext` deep inside a shared base
    repository.
  - `TenantContext` is `null` for `patient` tokens (not a member of any
    Organization) and for `platform_admin` tokens (acting across every
    tenant). Code that legitimately needs a tenant calls
    `TenantContext.require()`, which throws instead of silently proceeding
    with `null`.
  - Every `/api/**` endpoint carries a `@PreAuthorize` (method security is
    explicitly enabled via `@EnableMethodSecurity` - Spring Security 6 does
    **not** turn it on automatically) except the deliberately-public paths
    listed in `SecurityConfig`'s `permitAll()`.
- A mirror `tenant_id` on the local `app_users` row is written once at
  provision time and **never consulted for authorization** - the per-request
  token is the source of truth.
- **No cross-tenant marketplace search after all** (revised 2026-09-12,
  phase 2): phase 1 sketched a "patient browses open slots across clinics"
  search mirroring the reference project's cross-operator trip search. Built
  differently once the domain was worked through in plan mode: unlike bus
  routes (a natural origin/destination dimension shared across every
  operator), clinics define their own `appointment_types` independently with
  no shared cross-clinic identity to search by - "New Patient Visit" at one
  clinic and "New Patient / 30 min" at another have no linkage. So a patient
  picks a clinic first (`GET /api/clinics`, `permitAll`), then browses that
  one clinic's availability (`GET /api/clinics/{id}/availability`,
  `permitAll`) - both endpoints have no tenant filter in the sense that
  they're reachable pre-auth, but the second is scoped to one clinic by path,
  not a cross-tenant query.
- **`TenantIsolationIntegrationTest`** (planned, grows with each phase): for
  every staff-scoped resource, clinic A seeds it and clinic B's staff is
  refused (404/403) on every read/write/action path, plus the deactivation
  lockout. `ClinicControllerIntegrationTest` (phase 1) and
  `AppointmentControllerIntegrationTest` (phase 2) seed this idea per
  resource; a consolidated `TenantIsolationIntegrationTest` pulling every
  path together is still just a plan, not built.

## Domain decisions pinned so far

- **Patient scope: per-clinic** (decided 2026-09-12, in plan mode before any
  code was written). Each clinic's patients are entirely its own - a person
  seen at two different clinics gets a separate `patients` row at each, with
  no cross-clinic linking/dedup. `patients.tenant_id` is `NOT NULL`.
- **PHI-access audit: deferred** (decided 2026-09-12). No dedicated audit log
  of who viewed/changed patient clinical data in v1 - see "Known gaps".
- **Clinic settings' fee fields vs. `fee_policies`** (design read, flagged at
  plan review, not explicitly reconfirmed): the kickoff spec bundles
  "no-show / reschedule fees" into the settings-override list, but
  separately defines no-show/late-cancel fees as **tiered**
  (`fee_policies`, keyed by hours-before-appointment, optional per-provider
  override). Read as: `clinic_settings`' fee columns are the **flat
  reschedule mutation fee** only; the tiered no-show/late-cancel fee lives
  entirely in `fee_policies`, with no duplication between the two. Revisit
  if this turns out wrong once phase 3 (cancel/reschedule) is built.
- **External lab integration / lab-orders scope**: not yet decided - the
  lab-orders module is explicitly scoped as its own later session with its
  own multiple-choice questions (real HL7/FHIR ingestion vs. manual staff
  entry; whether result values are ever shown to staff pre-review). Not
  touched in this session.
- **Single provider per appointment** (decided 2026-09-12, phase 2 plan
  mode). No multi-provider (joint-consult) appointments in v1 - matches the
  schema (`appointments.provider_id`, `slots.provider_id`, both singular).
- **Recurring appointments: in scope, staff-only, bounded** (decided
  2026-09-12 - a deliberate expansion beyond phase 2's original one-line
  scope). A series is weekly/biweekly/etc. (`interval_weeks >= 1`) with a
  fixed `occurrence_count` (2..`clinicops.appointment.series.max-occurrences`,
  default 26) - no open-ended "until" recurrence. Created only by
  `front_desk`/`provider`/`clinic_admin` via `POST /api/appointments/series`;
  a patient still self-books one appointment at a time through the portal.
  See `AppointmentSeriesService`.
- **A patient-portal login auto-provisions a `patients` row on first booking
  at a clinic** (decided 2026-09-12) - no separate "register as a patient
  here" step. `patients.app_user_id` (added in `V2__appointment_booking.sql`)
  links the two; `PatientProvisioningService.resolveForPortalUser` does the
  lookup-or-create, mirroring `CurrentUserService`'s "create on first login"
  pattern. A second booking at the same clinic by the same portal login
  reuses the same `Patient` row - confirmed live, see "Verified this session".
- **Provider/room/appointment-type/working-hours have no admin CRUD yet**
  (decided 2026-09-12; **superseded by phase 5, 2026-09-13** - the CRUD now
  exists, see "Phase 5: clinic-admin config") - phase 1's plan assigned
  their full CRUD to phase 5, but phase 2's booking flow needs to *read*
  them.
  Resolved by building the entities/read-repos now and seeding data via SQL
  (`infra/postgres/seed-demo-scheduling-data.sql`, same spirit as
  `create-demo-clinic.sh`) rather than building admin CRUD endpoints early.
  Patients are the one exception - `PatientController` create/list/search/get
  is real, ongoing operational surface, not one-time admin setup, so it's
  built now (phase 2), not phase 5.
- **Invoicing/billing deferred, not assigned to phase 2** (decided
  2026-09-12) - no domain-list item in the phase plan assigns invoice/
  payment creation anywhere yet; `appointment_types.price_amount` already
  makes a price visible without needing an invoice row. Revisit when billing
  gets its own scoped slice. `invoices`/`payments` tables exist in
  `V1__init.sql`, unused by any code so far.
- **Check-in identity check: no ID on file is allowed through, not a
  mismatch** (decided 2026-09-13, phase 3 plan mode). Only an actual
  value-vs-value mismatch between a presented ID and `patients.national_id`
  raises `IdentityMismatchException`. Deliberately the opposite of both the
  reference project's own boarding check *and* the kickoff spec's lab-module
  check (both treat "nothing on file" as a mismatch too) - confirmed live,
  see "Verified this session - phase 3".
- **Reschedule/cancel guards stay minimal** (decided 2026-09-13) - only what
  the reference project and kickoff spec actually specify (already
  -cancelled guard, notice gate, cross-tenant/cross-owner 404). No extra
  unspecified state guards - e.g. cancelling an already-`checked_in`
  appointment is allowed, matching the reference project's own scope.
- **Encounter documentation requires `with_provider` or `checked_out`**
  (decided 2026-09-13, phase 4 plan mode) - matches the check-in state
  machine's own ordering: a provider can only chart a visit once they've
  actually started (or finished) seeing the patient, not merely `booked`/
  `checked_in`/`roomed`. Reuses `InvalidAppointmentStatusException` (409)
  from `com.clinicops.appointment` rather than inventing a parallel
  exception type for the same "wrong status for this action" shape.
- **Encounters/prescriptions stay editable indefinitely** (decided
  2026-09-13) - no freeze/lock concept once `checked_out`, since nothing in
  the schema or kickoff spec asks for one and providers commonly need to
  amend a note after the fact.
- **Encounter authorship: assigned provider (by their own resolved
  `Provider.id`) or `clinic_admin` only** (decided 2026-09-13) -
  `front_desk` gets no access to encounter endpoints at all (clinical
  content, not a front-desk concern); `clinic_admin` can document on a
  provider's behalf with no ownership check, matching its override role
  elsewhere in the app.
- **"Today's schedule" is a new dedicated endpoint, not a filter on the
  existing tenant-wide one** (decided 2026-09-13) - `GET /api/my-schedule`
  mirrors `GET /api/my-appointments`'s ownership-scoped-by-JWT pattern
  rather than adding `?date=&providerId=` params to `GET /api/appointments`,
  which front_desk/clinic_admin keep using unchanged to see everyone.
- **`rooms`/`appointment_types` deactivate via a `status` string, not a
  boolean `active`** (decided 2026-09-13, phase 5 plan mode) - matches
  `clinics.status`/`providers.status`/`appointments.status` exactly rather
  than the reference project's boolean convention; both tables needed a
  new `V4` column since neither had one at all before this phase.
- **`fee_policies` CRUD uses real hard DELETE**; providers/rooms/
  appointment-types use soft-deactivate (decided 2026-09-13) - directly
  reuses the reference project's own `RefundPolicyController` reasoning:
  fee tiers are configuration with a well-defined "missing = 0%" fallback
  already in `FeeCalculator`, so deleting one leaves nothing inconsistent;
  providers/rooms/appointment-types are referenced by FK from `slots`/
  each other with no `ON DELETE CASCADE`, so a real delete would fail once
  anything references the row - soft-deactivate isn't just stylistic there.
- **The phase-3 "`clinic_settings`' fee fields vs. `fee_policies`" decision
  is now reconfirmed, not just assumed** (2026-09-13) - `RescheduleService`
  was refactored to actually call `ClinicSettingsService.resolve(tenantId)`
  for its flat reschedule fee/notice-hours instead of raw `@Value` fields;
  `CancellationService` still only reads the tiered `fee_policies`/
  `FeeCalculator`, confirming the split holds now that both paths are real.
- **`provider_working_hours` gets CRUD too, nested under `/api/providers/
  {id}/working-hours`** (decided 2026-09-13) - not named in the phase-plan's
  one-line CRUD list, but a provider isn't manageable without it. Rejects
  an overlapping window for the same provider+day (checked in-memory, no
  DB constraint exists for this) rather than allowing a nonsensical one.
- **A `Provider`-to-login link/unlink endpoint** (decided 2026-09-13) -
  closes the gap phase 4 flagged (`Provider.appUserId` was manual SQL +
  Keycloak admin API). Looks up an already-provisioned `AppUser` by email
  (`findFirstByEmail` - no unique constraint on that column) and sets/clears
  the FK; no Keycloak call needed since the account must already exist
  locally (someone has logged in at least once).
- **A new public `GET /api/clinics/{id}/appointment-types` endpoint**
  (decided 2026-09-13) - a real pre-existing gap, not scope creep: nothing
  let a patient/guest discover a clinic's appointment types before booking
  one, even though `POST /api/appointments`/`guest` already require an
  `appointmentTypeId` with no way to look one up. Mirrors
  `GET /api/clinics/{id}/availability`'s `permitAll` shape; active-only.
- **No dedicated Writer-bean for phase 5's CRUD** (decided 2026-09-13) -
  re-confirmed from the reference project's own `BusController`/
  `RouteController`/`OperatorSettingsService`: that split is reserved for
  genuine cross-bean `@Transactional` self-invocation or lock/race handling
  (`AppointmentWriter`, `AppUserWriter`, `PatientWriter`), not plain
  single-row CRUD - controllers call repositories directly, matching
  `PatientController`'s own read-path precedent. `ClinicSettingsService` is
  the one real service this phase, since `resolve(...)` is a genuine
  cross-cutting dependency other code (`RescheduleService`) now has.
- **Deactivate/reactivate are dedicated `POST /deactivate`/`POST /reactivate`
  actions, not fields folded into a generic update** (decided 2026-09-13,
  phase 6 plan mode) - matches the kickoff spec's own framing (deactivation
  is a distinct, consequential action - it locks out an entire tenant, not
  a mere field edit) and this codebase's existing convention for
  significant state transitions (check-in/cancel/reschedule are all
  dedicated action endpoints). Idempotent, matching `CheckInService`'s
  established re-call convention.
- **No initial `clinic_admin` user provisioning in phase 6** (decided
  2026-09-13) - `POST /api/platform/clinics` creates the Keycloak
  Organization + local `clinics` row only, matching both the kickoff
  spec's silence on this and the reference project's identical scope
  (`OperatorProvisioningService` never creates a user either). A real gap -
  a freshly onboarded clinic has nobody who can log in until someone
  creates/assigns a `clinic_admin` user by hand - deliberately left there,
  not solved differently here.

## Phase plan

1. **Infra + auth skeleton** (this session, 2026-09-12) - repo scaffolding,
   Keycloak realm, nginx, docker-compose, the Spring Boot tenancy/security
   skeleton, the BFF's OIDC/session/proxy skeleton, a minimal frontend, CI.
   See "Verified this session" below.
2. **Patient booking flow** (built 2026-09-12) - appointment types, provider
   working hours -> lazy slot generation, `SlotLockService` + a split-bean
   `AppointmentWriter`, all three booking channels (`patient_portal`/
   `front_desk`/`guest`), idempotency, the public appointment-tracking
   endpoint, patient auto-provisioning, and (a scope expansion decided in
   plan mode) bounded recurring-appointment series. See "Phase 2: patient
   booking flow" below for the full write-up and "Verified this session".
3. **Front-desk/counter + check-in** (built 2026-09-13) - reschedule/cancel
   with fee-tier calculation, and the check-in state machine
   (`booked -> checked_in -> roomed -> with_provider -> checked_out`,
   `no_show`/`cancelled`). Front-desk's own search/book/patient-registration
   was already built in phase 2. See "Phase 3: front-desk/counter +
   check-in" below and "Verified this session - phase 3".
4. **Provider clinical flow** (built 2026-09-13) - a provider-scoped
   `GET /api/my-schedule` worklist, encounter documentation (chief
   complaint/assessment/plan), and a full-replace prescription list. See
   "Phase 4: provider clinical flow" below and "Verified this session -
   phase 4".
5. **Clinic-admin config** (built 2026-09-13) - providers/rooms/appointment
   -types/fee-policy/working-hours CRUD, `ClinicSettingsService.resolve`
   (and `RescheduleService` refactored to actually consume it), the
   branding endpoint, and a provider-login link/unlink endpoint. Backend
   only - the frontend settings/branding hub is deferred, same as every
   prior phase's UI. See "Phase 5: clinic-admin config" below and
   "Verified this session - phase 5".
6. **Platform-admin onboarding** (built 2026-09-13) - Keycloak Organization
   creation via `RestClient` (not the `keycloak-admin-client` library - see
   the kickoff spec's reasoning) + local `clinics` insert, and (going
   further than the reference project ever did) a real reactivate endpoint.
   Deactivation enforcement itself needed no new work - `TenantContextFilter`/
   `ClinicInactiveException` have blocked it since phase 1. See "Phase 6:
   platform-admin onboarding" below and "Verified this session - phase 6".
7. **Lab orders module** (separate future session, scoped via its own
   multiple-choice questions - not part of this plan).

Each phase gets its own `TenantIsolationIntegrationTest` coverage extension
and a `CLAUDE.md` update recording what was verified live against the
running stack.

## Auth / BFF details

- `node-bff` does OIDC discovery against the internal Keycloak URL
  (container-to-container) but rewrites `authorization_endpoint` /
  `end_session_endpoint` **and `issuer`** to the browser-reachable public URL
  before handing URLs to the browser / validating tokens. See the comment at
  the top of `node-bff/src/auth/oidc.js` for the full reasoning.
- **Real bug found and fixed live, 2026-09-12, verified against an actual
  browser login**: the initial assumption (copied from the reference
  project's own comments) was that `issuer.metadata.issuer` should stay
  pinned to the *internal* Keycloak URL, since that's what node-bff's own
  server-to-server calls use and reasoning went "the token endpoint stamps
  `iss` regardless of which host the browser used for the authorize step."
  That's **wrong** for this Keycloak version/config
  (`KC_HOSTNAME_STRICT: false`): Keycloak pins the issuer, for a *whole*
  authorization-code flow, to whichever host the **browser's** original
  request to the authorize endpoint used - and reuses that same value both
  for the RFC 9207 `iss` redirect parameter and for the `iss` claim stamped
  into the resulting ID/access tokens, regardless of which host the later
  token-exchange call itself came from. So every token in this flow
  genuinely carries `iss = KEYCLOAK_ISSUER_PUBLIC`
  (`http://localhost:8080/...`), never `KEYCLOAK_ISSUER`
  (`http://keycloak:8080/...`). `openid-client`'s ID-token validation is a
  mandatory OIDC check with no opt-out, so `issuer.issuer` has to be the
  public value; `token_endpoint`/`jwks_uri`/etc. are left as their real
  (internal, network-reachable) discovered values - `issuer` is just an
  identity string checked against claims, it drives no network call of its
  own. **A tempting-looking alternative fix - giving Keycloak a fixed
  `KC_HOSTNAME` so the issuer is Host-independent everywhere - was tried and
  reverted**: it does fix the issuer, but it also makes Keycloak render every
  *in-page* login-form action off that same fixed (internal-only) hostname,
  not just the discovery-document fields node-bff rewrites, breaking browser
  reachability of the login pages themselves.
- **spring-boot-api needs the identical split**, for the identical reason:
  since real access tokens carry `iss = KEYCLOAK_ISSUER_PUBLIC_URI`, a plain
  `issuer-uri` property (which couples "where to fetch JWKS from" and "what
  `iss` to accept" into one string) can't work - `http://localhost:8080` is
  unreachable from inside the container for the JWKS fetch. `JwtDecoderConfig`
  builds the decoder by hand instead: `NimbusJwtDecoder.withJwkSetUri(...)`
  off `clinicops.keycloak.internal-issuer-uri` (network-reachable), with its
  validator built via `JwtValidators.createDefaultWithIssuer(...)` off
  `clinicops.keycloak.public-issuer` (what tokens actually carry). Guarded
  with `@Profile("!test")` so `AbstractIntegrationTest`'s own test-only
  `JwtDecoder` (no real network call at all) is the sole candidate in tests.
- Sessions live in Redis (`connect-redis`), not memory, so the BFF can
  restart or scale to more than one instance without logging everyone out.
  The token set round-trips through Redis as plain JSON - `refreshIfExpired`
  rewraps it via `new TokenSet(...)` before calling any library method that
  expects the real instance.
- `PUBLIC_ROUTES` in `node-bff/src/routes/api.js` is empty for now - filled
  in as public endpoints (appointment tracking, guest booking, later the lab
  order tracking lookup) are built, mounted ahead of the session gate.
  `forwardToApi` attaches no Bearer header when there's no session.
- The BFF refreshes an expired access token before forwarding; an
  `invalid_grant` on refresh (the refresh token itself has lapsed, not just
  the access token) destroys the session and returns the same `401` shape as
  "no session", so the frontend's central 401 handling redirects cleanly to
  login.
- `express.json()` sets `req.body` to `{}` for a request with no body and no
  `Content-Type` - `shouldForwardBody` checks both `!== undefined` and
  `Object.keys(body).length > 0` before forwarding one downstream.
- `clinicops.tenant.org-claim-path` in `application.yml` names the token
  claim `TenantContextFilter` reads for the org id. **Verify this against a
  real token** once the realm is actually running - Keycloak's Organizations
  claim shape can differ by version (decode at jwt.io or the Admin Console's
  "Evaluate" tab).
- Add `.requestMatchers("/error").permitAll()` to `SecurityConfig` -
  otherwise a `@Valid` bean-validation failure gets rewritten by the servlet
  `/error` re-dispatch into a misleading `403 insufficient_scope` instead of
  a `400` (already done, see `SecurityConfig`).

## Phase 2: patient booking flow

Mirrors the reference bus-ticketing-saas project's booking/scheduling code
almost 1:1 (its `SeatLockService`/`BookingService`/`BookingWriter`/
`SeatLayoutGenerator`/`TripCreationService`/`TicketNumberGenerator`/
`BookingController` were read in full before writing any of this) - slots
stand in for seats, appointments for bookings, clinics for operators.

- **`com.clinicops.scheduling`**: `Slot` entity; `SlotGenerator` (pure,
  static, mirrors `SeatLayoutGenerator`) turns a day's
  `provider_working_hours` windows into whole-slot `(start, end)` pairs of an
  appointment type's `durationMinutes` - no partial leftover slot, no
  past-dated slots. Unlike the reference project's one-shot-at-creation seat
  generation, `SlotGenerationService` generates **lazily and idempotently**
  (`ensureSlotsGenerated`/`ensureSlotExists`) since clinic availability is a
  rolling calendar, not a discrete one-off event like a bus trip. Working
  hours are interpreted in **UTC** - no per-clinic timezone concept yet (see
  "Known gaps"). `GET /api/clinics/{id}/availability` (`permitAll`) is the
  patient/guest entry point, generating on demand (14-day default horizon).
- **`com.clinicops.appointment`**: `SlotLockService` (line-for-line port of
  `SeatLockService` - Redis `SETNX`+TTL, Lua-scripted token-checked release,
  keyed by slot id) fronts `AppointmentService` (orchestrator, no
  `@Transactional`) -> `AppointmentWriter` (separate `@Transactional` bean -
  re-fetches the slot via `SlotRepository.findByIdAndProviderId`
  (`@Lock(PESSIMISTIC_WRITE)`), re-checks `status = 'open'`, flips it,
  saves the `Appointment`, writes the `notifications` outbox row). Same
  reasoning as the reference project for the split: calling an
  `@Transactional` method via `this.method(...)` from inside the same class
  silently skips Spring's proxy.
  - **Channels**: `POST /api/appointments` (shared by `patient_portal`/
    `front_desk`, channel derived from the JWT role server-side, never
    client-supplied), `POST /api/appointments/guest` (`permitAll`, no JWT at
    all). `contactEmail` on a guest booking is **never persisted** - it only
    flows through as the transient notification recipient, matching the
    kickoff spec.
  - **Idempotency**: `(tenant_id, idempotency_key)` unique constraint on
    `appointments`, checked before any Redis lock is acquired.
  - **`ClinicInactiveException`** (409) is checked explicitly in
    `AppointmentService`, not left to `TenantContextFilter` alone - a
    `patient_portal`/`guest` token carries no org claim, so that filter's
    lockout never runs for them. Same belt-and-suspenders reasoning as the
    reference project's `BookingService`.
  - **Public tracking**: `GET /api/appointments/track/{ref}?phone=`
    (`permitAll`) - ref + phone two-factor match (against the appointment's
    own contact phone, or its linked patient's phone on file), mismatch and
    unknown ref both 404. `AppointmentTrackingView` is deliberately narrow -
    status/timestamps/clinic/provider/time only.
- **Recurring series** (`AppointmentSeries`, `AppointmentSeriesService`) -
  bounded (`interval_weeks`, fixed `occurrence_count`, capped by
  `clinicops.appointment.series.max-occurrences`), staff-only. Reuses the
  *exact* single-slot `SlotLockService`/`AppointmentWriter` path in a loop -
  no new locking mechanism. Each occurrence gets a per-occurrence idempotency
  key (`"<request key>::<index>"`), so retrying the whole series request is
  itself idempotent - confirmed live (see below). **Partial success by
  design**: a slot that's unavailable for one occurrence is recorded as a
  conflict and the loop continues; `POST /api/appointments/series` returns
  201 if at least one occurrence was created, 409 if the very first one
  wasn't (nothing created at all).
- **`com.clinicops.patient`**: `PatientController` (create/list/search/get,
  `front_desk`/`clinic_admin` write, `+provider` read) for front-desk walk-in
  registration. `PatientProvisioningService.resolveForPortalUser` handles the
  portal-login-to-patient-record link (see "Domain decisions" above) - never
  itself a public endpoint, a side effect of a `patient_portal` booking.
- **`com.clinicops.user`**: `AppUser`/`CurrentUserService`/`AppUserWriter` -
  ports the reference project's JWT-subject-to-local-user provisioning, but
  **fixes a bug found while reading that code**: its `CurrentUserService.
  provision` is `@Transactional` but called via plain `this.provision(...)`
  self-invocation from the same class - the proxy never applies no matter the
  method's visibility, despite the javadoc's claim that public-ness fixes it.
  Here the write lives in a genuinely separate bean, `AppUserWriter`.
- **Provider/room/appointment-type/working-hours**: entities + read-only
  repositories only - no admin CRUD controller yet (phase 5). Seeded for
  testing via `infra/postgres/seed-demo-scheduling-data.sql`.

## Phase 3: front-desk/counter + check-in

Mirrors the reference project's `RefundPolicy`/`RefundCalculator`,
`CancellationService`/`CancellationController`, and
`BookingRescheduleService`/`BookingRescheduleController` (read in full).
**One thing does NOT carry over**: the reference project's "boarding" is a
flat two-value flag (`not_boarded`/`boarded`), not a multi-step state
machine - the check-in state machine below is a fresh design, following the
same conventions used elsewhere (idempotent re-call, a dedicated exception
on an out-of-order call, a live time gate that never trusts the scheduler).

- **`com.clinicops.feepolicy`**: `FeePolicy` (one row per tier - unlike the
  reference project's `refund_policies`, which stores a whole JSON tier
  array in one row per route/policy) + `FeeCalculator.calculate(tenantId,
  providerId, totalCost, dueAt)`, kept generic exactly as the kickoff spec
  asks so a future lab-order cancellation can reuse it unchanged. A specific
  provider's tiers **replace** the clinic-wide default entirely (never
  merged); tiers sort highest-cutoff-first defensively; no tiers configured
  at all -> fee is zero, never blocks the action.
- **Cancellation** (`CancellationService`/`CancellationController`) - same
  two-public-methods-one-private-helper split as the reference project:
  `cancel` (tenant-scoped, `front_desk`/`clinic_admin`) and `cancelAsCustomer`
  (ownership-scoped via `customerUserId`, `patient`) both delegate to
  `applyCancellation` - already-cancelled guard -> fee via `FeeCalculator`
  -> flip `status = "cancelled"` -> free the slot -> write the
  `appointment_cancellations` audit row -> outbox notification if a
  recipient's on file. The fee is **computed and recorded only** - no
  billing integration exists yet to actually charge it (see "Known gaps").
- **Reschedule** (`RescheduleService`/`RescheduleController`) - same split.
  Order: already-cancelled guard -> minimum-notice gate against the
  *current* slot (`clinicops.appointment.reschedule.min-notice-hours`) ->
  new slot's clinic must match (`TenantMismatchException`) -> new slot's
  appointment type must match the original (400) -> lock + re-check the new
  slot (`SlotConflictException`) -> **update the appointment row in place**
  (reassign `slotId`/`providerId`) rather than the reference project's
  insert-new-then-delete-old dance - this schema has no child table keyed by
  `slot_id` the way its `booking_infants` forced that ordering, so the
  simpler in-place update is safe here; revisit if a future migration adds
  one -> free the old slot -> flat fee from
  `clinicops.appointment.reschedule.fee-patient-portal`/`fee-front-desk` ->
  write the `appointment_reschedules` row -> outbox notification.
- **Check-in state machine** (`CheckInService`/`CheckInController`) -
  `booked -> checked_in -> roomed -> with_provider -> checked_out`, plus
  `no_show` (only reachable from `booked`). Re-calling a transition already
  reached is idempotent (200, returns current state, no re-validation);
  calling one out of order throws `InvalidAppointmentStatusException` (409).
  `check-in` alone carries two extra checks: `CheckInWindowClosedException`
  (409) if `Instant.now()` is past the slot's own **end** time - read as the
  whole slot window, not just the start instant, since the kickoff spec
  doesn't pin an exact grace period and inventing one felt worse than using
  a boundary the domain already has - and `IdentityMismatchException` (409)
  for a genuine presented-ID-vs-`patients.national_id` mismatch (see the
  "no ID on file" decision above).
- **`NoShowScheduler`** (`@Scheduled(fixedDelay = 300_000)`, needs
  `@EnableScheduling` on the application class - same "silently inert
  without it" gotcha as `@EnableMethodSecurity`) - a single native bulk
  `UPDATE ... FROM slots` flipping stale `booked` appointments to `no_show`.
  Purely worklist hygiene, same principle as the reference project's
  `TripLifecycleScheduler`: `CheckInService.checkIn`'s live gate check never
  depends on this having already run for a given appointment.

## Phase 4: provider clinical flow

The `encounters`/`prescriptions` tables have existed since `V1__init.sql`
(phase 1's initial migration) but had **zero Java code** until this phase -
no entity, repository, service, or controller. Built entirely fresh; no new
migration needed, the existing columns were already sufficient for the
kickoff spec's "keep minimal in v1" scope (chief complaint/assessment/plan
+ a simple medication/dosage/instructions list - no vitals, ICD codes,
attachments, or e-prescribing anywhere in the spec). The reference project
has no analog at all (no per-instance staff login, no clinical-documentation
concept) - confirmed via a broad grep before writing anything.

- **`com.clinicops.encounter`** (new package): `Encounter` extends
  `BaseTenantEntity` (`appointmentId` unique - exactly one encounter per
  appointment, enforced at the DB level; `providerId` always copied from the
  appointment at creation, never client-supplied; its own `updatedAt` field,
  set to `Instant.now()` on every save). `Prescription` extends
  `BaseTenantEntity` too (`encounterId`, `medicationName` required,
  `dosage`/`instructions` optional). `EncounterWithPrescriptions` is the GET
  read-shape, bundling the two (no JPA relation between them, same plain-FK
  convention as everywhere else) - same "wrapper record" idea as
  `AppointmentSeriesResult`.
- **`EncounterService`** - a single bean, plain `@Transactional` methods (no
  Redis-lock split-bean pattern; an encounter isn't a contended resource the
  way slot booking is - last-write-wins is fine given the "editable
  indefinitely" decision). `upsert(...)` finds-or-creates by
  `appointmentId` (so a second call updates, never duplicates - the DB's own
  unique constraint would reject a duplicate anyway); `replacePrescriptions(...)`
  deletes-all-then-reinserts the given list (full replace, never merged);
  both share the same status-gate + ownership checks.
- **`EncounterController`** - `POST /api/appointments/{id}/encounter`
  (upsert), `GET /api/appointments/{id}/encounter` (read, 404 if none
  written yet), `POST /api/appointments/{id}/encounter/prescriptions`
  (replace). All `@PreAuthorize("hasAnyRole('PROVIDER','CLINIC_ADMIN')")` -
  no `front_desk`. Kept POST for every mutation (confirmed zero
  `@PutMapping`/`@PatchMapping` anywhere in `spring-boot-api` before adding
  these) rather than introducing PUT/PATCH into the codebase's conventions.
- **`com.clinicops.provider.CurrentProviderService`** (new) - resolves the
  calling `provider`-role user's own `Provider.id` from their JWT
  (`CurrentUserService.resolveInternalUserId` -> new
  `ProviderRepository.findByAppUserIdAndTenantId`), mirroring
  `CurrentUserService`/`PatientProvisioningService`'s "one service per
  resolution concern" shape. Unlike patients, a `Provider` row is **never
  auto-provisioned** - linking one to a login is still SQL/admin-API-only
  (no provider admin CRUD until phase 5), so an unlinked provider account
  gets a clear, deliberate 404, never a silent auto-create. Used by both
  `EncounterController` (ownership check) and `GET /api/my-schedule` (below).
- **"Today's schedule"** - `AppointmentRepository.findProviderSchedule`
  (new native query, same join-since-no-JPA-relation pattern as
  `flipStaleBookedToNoShow`) + `GET /api/my-schedule` on the existing
  `AppointmentController`, `@PreAuthorize("hasRole('PROVIDER')")`. Day
  boundaries use `ZoneOffset.UTC` (matching `AvailabilityController`/
  `SlotGenerationService`'s existing day-math convention exactly - no new
  timezone concept introduced), optional `?date=` param defaults to today.

## Phase 5: clinic-admin config

Mirrors the reference project's `com.bustix.operator` (settings/branding)
and `Bus`/`Route`/`RefundPolicy` (CRUD) almost 1:1 - both read in full
before writing anything. New migration: `V4__clinic_admin_config.sql` adds
`status VARCHAR(20) DEFAULT 'active'` to `rooms`/`appointment_types` (the
only schema gap - `clinic_settings`/`fee_policies`/`providers`/
`provider_working_hours` already had everything since `V1__init.sql`).

- **`com.clinicops.clinicsettings`** (new package) - `ClinicSettings` is a
  singleton keyed by `tenantId` itself (no separate id, same shape as the
  reference project's `OperatorSettings`), split into two disjoint column
  groups so one endpoint's full-replace can never wipe the other's fields:
  a *settings* group (tax rate, fees, notice hours, reminder lead, contact
  info) and a *branding* group (logo/colors/display name/footer). `Clinic
  SettingsService.resolve(tenantId)` is the one merge point the kickoff
  spec asks for - holds the platform `@Value` defaults (moved here from
  `RescheduleService`), coalesces each nullable override, safe for a `null`
  tenantId. `ClinicSettingsController`/`ClinicBrandingController`:
  `GET`/`POST /api/clinic/settings` (`clinic_admin` only, full-replace -
  the one place in this codebase that isn't partial-update, matching the
  reference project's own settings-vs-CRUD convention split), `GET
  /api/clinic/branding` (`clinic_admin`+`front_desk`+`provider` - staff
  need it to theme their workspace) / `POST` (`clinic_admin` only,
  hex-color/URL-validated). `displayName` falls back to the clinic's own
  `Clinic.name` when unset; every other branding field stays `null` (no
  server-side fallback) if unset.
- **`RescheduleService` now actually depends on `ClinicSettingsService`** -
  dropped its three `@Value` fields, calls `resolve(tenantId)` for the
  notice-hours/fee instead. Live-verified this isn't cosmetic: overriding
  `rescheduleMinNoticeHours` to 48 on a clinic made a 23h-notice reschedule
  attempt 409 with `"Fewer than 48 hours remain..."`, where the platform
  default (4h) would have allowed it.
- **Provider/Room/AppointmentType/FeePolicy/WorkingHours CRUD** - same
  shape four times over: `GET` (list, tenant-scoped, optional `?status=`;
  3-role read), `GET /{id}`, `POST` (create, `clinic_admin`), `POST
  /{id}/update` (partial - only non-null fields applied; `clinic_admin`).
  No PUT/PATCH/DELETE anywhere (still zero uses of any of those verbs in
  this codebase) - update/remove endpoints get an explicit verb suffix
  (`/update`, `/remove`, `/delete`, `/link-login`) instead. Entities
  returned directly, no response DTOs, matching `PatientController`.
  - `ProviderController` (new) - `roomId` cross-tenant-validated when
    given; `status` allow-listed. Plus `POST .../link-login` /
    `.../unlink-login` (see the pinned decision above).
  - `ProviderWorkingHoursController` (new) - nested under the provider;
    rejects an overlapping window (checked in-memory against
    `findAllByProviderIdAndDayOfWeek`) and a bad time range; hard `/remove`
    (no FK references a working-hours row directly).
  - `RoomController`/`AppointmentTypeController` (new) - same shape.
    `AppointmentTypeController` also has the new public
    `GET /api/clinics/{clinicId}/appointment-types` (`permitAll`, active
    -only, narrow `AppointmentTypeDirectoryView` projection) - confirmed
    genuinely anonymous through `node-bff` with zero cookies, same "verify
    through the BFF, not just spring-boot-api directly" lesson from phase
    3. `node-bff/src/routes/api.js`'s `PUBLIC_ROUTES` updated and the
    container rebuilt accordingly.
  - `FeePolicyController` (new) - `clinic_admin` only on every endpoint
    (financial config, tighter than the other three); rejects a duplicate
    `(providerId, cutoffHours)` tier with 409; `POST /{id}/delete` is a
    real hard delete (see the pinned decision above).

## Phase 6: platform-admin onboarding

Ports the reference project's `com.bustix.platform` near-verbatim (read in
full before writing anything) - `PlatformController`/
`OperatorProvisioningService`/`KeycloakOrganizationClient`/
`KeycloakAdminTokenProvider` map 1:1 to
`com.clinicops.platform.PlatformController`/`ClinicProvisioningService`/
`KeycloakOrganizationClient`/`KeycloakAdminTokenProvider`, just pointed at
the `clinic` realm instead of `bustix`. No migration - `clinics` has had
everything needed (`keycloak_org_id`/`name`/`status`/`created_at`) since
`V1__init.sql`.

- **`KeycloakAdminTokenProvider`**/**`KeycloakOrganizationClient`** - the
  exact two-call flow `infra/keycloak/create-demo-clinic.sh` already did by
  hand: log in as the Keycloak admin (master realm, `admin-cli` client,
  Resource Owner Password Credentials grant) via `RestClient`, then
  `POST /admin/realms/clinic/organizations` with the new org's `name`/
  `alias`/`domains`, reading the new org's id off the `Location` header.
  Deliberately `RestClient`, not the `keycloak-admin-client` library - its
  transitive RESTEasy/Jackson versions risk classpath conflicts with
  Spring's own stack (the kickoff spec's exact reasoning). Both config keys
  (`clinicops.keycloak-admin.*`) and the docker-compose env vars had sat
  unused since phase 1, already commented "phase 6."
- **`ClinicProvisioningService.provisionClinic`** - pre-checks
  `ClinicRepository.findByKeycloakOrgId` (409 `ClinicAlreadyExistsException`
  before ever calling Keycloak - confirmed live: a rejected duplicate
  attempt creates zero orgs in Keycloak, not an orphaned one) -> creates
  the Keycloak org -> saves the local `Clinic` row (`keycloakOrgId` =
  the alias, never the Keycloak-internal id - same "alias, not id, is what
  a token's `organization` claim carries" finding `TenantContextFilter`
  already documented in phase 1). No compensating rollback if the local
  save fails after Keycloak succeeds - the kickoff spec's own documented
  caveat, matching the reference project's identical non-solution.
- **`PlatformController`** (`/api/platform/clinics`, every endpoint
  `hasRole('PLATFORM_ADMIN')`) - `GET` (every clinic, any status - an
  admin needs to see deactivated ones to reactivate them), `GET /{id}`,
  `POST` (create), `POST /{id}/update` (partial, `name` only -
  `keycloak_org_id` isn't editable, it's what `TenantContextFilter`
  matches a staff token's org claim against), `POST /{id}/deactivate` /
  `POST /{id}/reactivate` (idempotent re-call, same convention as
  `CheckInService`'s transitions). `KeycloakAdminException` maps to 502 -
  the failure is genuinely upstream, in Keycloak, not a client error.
- **Deactivation enforcement needed zero new code** - `TenantContextFilter`
  (staff, 403 "Clinic account is deactivated") and `AppointmentService`'s
  `ClinicInactiveException` (guest/patient-portal booking, 409) have
  enforced this since phase 1/2. Phase 6 just builds the toggle. Confirmed
  live end to end: deactivating the real `demo-clinic` immediately 403'd
  `demo-clinic-admin`'s own `GET /api/clinic/me` and 409'd a guest booking
  against it (`"This clinic is not currently accepting bookings"`);
  reactivating restored both paths - proving the wiring, not just the code.
- **A `GET /api/clinics/{id}/availability`-style residual gap, inherited
  deliberately** - a deactivated clinic's availability still shows up via
  the public availability search (only the final booking call and staff
  API access are blocked), matching the reference project's own documented
  choice for its marketplace search. Not solved differently here.

## Frontend

`node-bff/frontend/` - a React + Vite + Tailwind SPA with its own
`package.json`/lockfile/`npm install`/`npm run build`, **not** an npm
workspace of `node-bff`. Nested under `node-bff/` specifically so
`docker-compose.yml`'s existing `node-bff` build context covers it. The
Dockerfile is multi-stage: a `frontend-build` stage runs
`npm ci && npm run build`, its `dist/` is `COPY --from=`'d into the runtime
stage as `./public`; `src/index.js` serves `public/` and falls back to
`public/index.html` for any GET that isn't `/health`, `/auth/*`, or `/api/*`.

Phase 1 only built a placeholder: `App.jsx` renders `PublicShell` (a login
link) or `AppShell` (shows the signed-in user, their roles, and - for staff -
the resolved clinic name via `GET /api/clinic/me`) depending on `GET
/auth/me`. Real per-role routing, `RequireRole` usage beyond the stub
already in place, and the design system (semantic Tailwind tokens for
branding) get built out starting in phase 2.

Role info for UX-only nav/route gating comes from `GET /auth/me`. The OIDC
**ID** token is expected to carry no `realm_access.roles` on this realm
(matching the reference project's own finding) - only the **access** token
does; the BFF callback decodes its payload (no signature check needed, the
OAuth exchange already established authenticity) and merges
`realm_access.roles` into the session user. Re-verify this once real login
is tested against the actual realm. Real authorization stays entirely
server-side (`@PreAuthorize`).

## Testing

- `TenantContextFilterTest` - unit tests locking in the claim-shape parsing
  (list of aliases, plain string, id-keyed map, missing claim).
- `AbstractIntegrationTest` - Testcontainers singleton (Postgres 16 +
  Redis 7, started once per JVM fork, never explicitly stopped), MockMvc
  through the *real* filter chain (Spring Security, `TenantContextFilter`,
  `@PreAuthorize` all run for real), JWTs faked via Spring Security Test's
  `jwt()` post-processor pulling authorities from the app's real
  `JwtAuthenticationConverter` bean. `NoNetworkJwtDecoderConfig` replaces the
  autoconfigured `JwtDecoder` so context startup never tries a real network
  call to `KEYCLOAK_ISSUER_URI`.
- `ClinicControllerIntegrationTest` - phase-1 smoke test: staff roles read
  their own clinic, the clinic-deactivation lockout 403s, a patient token
  (no org claim) and a platform_admin token are both refused on this
  staff-only endpoint.
- `SlotGeneratorTest` (phase 2, pure unit) - working-hours -> slot boundary
  slicing, duration division, dropped partial-leftover slots, past-dated
  exclusion, day-of-week mapping (0=Sunday), multiple same-day windows.
- `AppointmentControllerIntegrationTest` (phase 2) - both authenticated
  channels, guest booking, idempotency, slot conflict (409), cross-tenant
  front_desk 403, missing-patientId 400, clinic-inactive 409, public tracking
  (match/mismatch/unknown-ref all 404 alike), patient auto-provisioning
  (reused on a second booking, not duplicated), availability search, and the
  series flow (full success, partial conflict, idempotent retry of a partial
  series).
- `FeeCalculatorTest` (phase 3, pure unit) - no tiers -> zero; each tier
  boundary; tiers sort correctly regardless of storage order; a provider
  -specific tier set replaces the clinic-wide default entirely (verified via
  `verify(..., never())` that the default is never even queried).
- `CancellationIntegrationTest`/`RescheduleIntegrationTest`/
  `CheckInIntegrationTest` (phase 3) - fee-tier calculation on cancel,
  double-cancel 409, cross-tenant/cross-owner 404, patient self-service
  (both ownership-scoped and staff-endpoint-403-for-a-patient-token);
  notice-gate 409 on reschedule, cross-clinic 403, slot-conflict 409; the
  full five-state check-in happy path, idempotent re-call, out-of-order 409,
  window-closed 409, identity mismatch/match/no-id-on-file, manual no-show,
  and the scheduler's bulk flip (called directly, not by waiting out the
  real 5-minute timer).
- `EncounterIntegrationTest`/`ProviderScheduleIntegrationTest` (phase 4) -
  upsert creates then updates the same row (not a duplicate); the
  `with_provider`/`checked_out` status gate (409 below it, 200 at/after);
  editing after `checked_out` still succeeds; a different provider's 403;
  `clinic_admin`'s override (no ownership check); `GET` before any encounter
  written -> 404; prescriptions fully replace, never merge; prescriptions
  before any encounter exists -> 400; cross-tenant -> 404; `my-schedule`
  returns only the calling provider's own same-day appointments, and 404s
  clearly for a `provider` token with no linked `Provider` row. No new pure
  -unit tests this phase (no calculation logic like `FeeCalculator`'s to
  isolate) - still 20/20 unit tests total, unchanged from phase 3.
- `ClinicSettingsIntegrationTest`/`ProviderControllerIntegrationTest`/
  `ProviderWorkingHoursIntegrationTest`/`RoomControllerIntegrationTest`/
  `AppointmentTypeControllerIntegrationTest`/`FeePolicyControllerIntegrationTest`
  (phase 5) - `resolve`/`GET` with no row returns pure platform defaults;
  full-replace then a `null` field reverting to the default; settings and
  branding confirmed disjoint; branding's 3-role `GET`/`clinic_admin`-only
  `POST` gate; invalid hex-color/URL -> 400; create/list/get/update/
  deactivate-then-reactivate for providers/rooms/appointment-types; a bad
  `roomId`/`status` -> 400; an overlapping working-hours window -> 409, a
  bad time range -> 400; a duplicate fee-policy tier -> 409, delete removes
  the row; the public appointment-types listing returns only `active` rows
  with no auth; cross-tenant 404 for every new resource;
  `link-login`/`unlink-login` (including "nobody's ever logged in with that
  email" -> 404). One new case added to `RescheduleIntegrationTest`: a
  `ClinicSettings` override actually changes the notice-gate boundary,
  proving `RescheduleService`'s refactor is wired, not just present.
- `ClinicProvisioningServiceTest` (phase 6, **pure Mockito unit test - no
  Spring context, no Testcontainers, no HTTP** - actually runs locally,
  first real growth in that suite since `FeeCalculatorTest`) - a fresh
  alias creates the Keycloak org before saving the local row (call-order
  verified); a taken alias throws `ClinicAlreadyExistsException` and
  **never calls Keycloak at all** (`verify(..., never())`, same style as
  `FeeCalculatorTest`); a `KeycloakAdminException` propagates without an
  inconsistent local row being saved.
- `PlatformControllerIntegrationTest` (phase 6) - `@MockBean
  KeycloakOrganizationClient` so `POST` tests don't attempt a real network
  call; create/list/get/update/duplicate-alias-409 (and confirms
  `KeycloakOrganizationClient` is never even invoked for the duplicate);
  deactivate-then-reactivate idempotent round-trip; every non
  -`platform_admin` role (including `patient`) 403s the whole controller.
- CI (`.github/workflows/ci.yml`): three parallel jobs - `mvn verify`
  (spring-boot-api), `npm test` (node-bff), `npm run build` (frontend).

## Verified this session (2026-09-12)

- `mvn -q -DskipTests compile`/`test-compile` in `spring-boot-api/` - clean.
- `mvn test`'s **unit** tests pass for real: `TenantContextFilterTest`, 7/7.
  The **Testcontainers-backed** `ClinicControllerIntegrationTest` fails to
  even start its containers on this machine - the exact Windows Docker
  Desktop npipe incompatibility the reference bus-ticketing-saas project's
  `pom.xml` comment already documents, reproduced here despite the same
  `testcontainers.version` override. Expected to run clean in CI
  (`ubuntu-latest`) per the reference project's own experience - **not yet
  confirmed in CI**, since nothing has been pushed to a remote.
- `npm test` in `node-bff/` - 20/20 pass. `npm run build` in
  `node-bff/frontend/` - builds clean.
- **`docker compose up --build` - full stack live-tested end to end,
  including a real browser login as both roles.** Two local port conflicts
  had to be resolved first: `bus-ticketing-saas`'s own nginx/redis containers
  were up on `:80`/`:6379` (stopped via `docker compose stop` in that repo),
  and natively-running `java`/`node` processes (that project's
  `spring-boot-api`/`node-bff`, started via their own `start-local.ps1`
  scripts, independent of any docker-compose) held `:8081`/`:3000` (stopped
  directly). Two schema-shaping infra bugs were found and fixed live in the
  process of getting a real login to work - see "Auth / BFF details" above
  for the full write-up of both:
  1. `openid-client`'s ID-token `iss` validation failing against the
     internal issuer - fixed by pointing `issuer.issuer` (node-bff) and
     `clinicops.keycloak.public-issuer` (spring-boot-api, via the new
     `JwtDecoderConfig`) at the public URL instead, while JWKS/token/
     discovery calls stay on the internal, network-reachable one.
  2. (Attempted, reverted) giving Keycloak a fixed `KC_HOSTNAME` - fixes the
     issuer but breaks the browser's ability to reach Keycloak's own
     in-page login-form actions.
  - Confirmed live: `demo-clinic-admin` logs in, lands on a page showing
    `Roles: clinic_admin` and the resolved clinic name "Demo Clinic" (proves
    JWT -> `TenantContextFilter` -> `@PreAuthorize` -> `ClinicRepository`
    end to end), and logs out cleanly back to the public landing page.
    `demo-patient` logs in, lands on the same page showing `Roles: patient`
    and no clinic panel (proves a patient token carries no org claim, so
    `TenantContext` correctly stays empty and the staff-only panel doesn't
    render/query).
  - The demo clinic was created via `infra/keycloak/create-demo-clinic.sh`
    (org alias `demo-clinic`) plus the matching `INSERT INTO clinics` it
    printed; the `clinic-bff` client secret was fetched from Keycloak's
    admin API and written to `.env`, then `node-bff` was recreated to pick
    it up - both exactly per the README's documented one-time setup.

## Verified this session - phase 2 (2026-09-12)

- `mvn -q -DskipTests compile`/`test-compile` - clean.
- `mvn test`: `SlotGeneratorTest` 6/6 and `TenantContextFilterTest` 7/7 pass
  for real. `AppointmentControllerIntegrationTest` (and
  `ClinicControllerIntegrationTest`) hit the same Testcontainers/Windows
  Docker Desktop npipe wall as phase 1 - confirmed still the case, not a new
  issue, still expected to run clean in CI only.
- **`V2__appointment_booking.sql` applied cleanly** against the live
  `docker compose up` stack (`spring-boot-api` logs: "Successfully applied 1
  migration to schema public, now at version v2"), Hibernate validated the
  new entity mappings against it with no drift.
- **The whole booking flow was live-verified end to end**, through the real
  browser session (not just `curl` against spring-boot-api directly) for the
  authenticated channels, proving the full `browser -> nginx -> node-bff
  (session) -> spring-boot-api` path, not just the API in isolation:
  - Seeded a demo provider/room/appointment-type/working-hours via
    `infra/postgres/seed-demo-scheduling-data.sql`.
  - `GET /api/clinics/{id}/availability` - generated and returned real open
    slots on first call (lazy generation confirmed).
  - Guest booking (`curl`, no auth) - `channel: "guest"`, `contactEmail`
    correctly came back `null` (never persisted), `appointmentRef`/
    `clinicRef` generated (`5F299A` / `DC-2026-0001`).
  - Public tracking - correct ref+phone match returned the narrow view;
    wrong phone and an unknown ref both 404 identically.
  - Booking the same slot twice - second attempt 409 (`SlotConflictException`).
  - Logged in as `demo-patient` in the browser, called `POST /api/appointments`
    via `fetch()` from the authenticated page (through node-bff's session,
    not a direct bearer token) - `channel: "patient_portal"`, a `Patient` row
    was auto-provisioned (name from the ID token's `given_name`/
    `family_name`), `GET /api/my-appointments` returned exactly that
    appointment. A second portal booking confirmed the *same* `Patient` row
    is reused (not duplicated) - checked directly in Postgres too (exactly
    one `patients` row with `app_user_id` set).
  - Logged in as `demo-clinic-admin` - `POST /api/patients` (walk-in
    registration) then `POST /api/appointments` with that `patientId` -
    `channel: "front_desk"`, correctly `appUserId: null`/`customerUserId:
    null` on the resulting records.
  - `POST /api/appointments/series` (3 weekly occurrences, all slots free) -
    201, `created.length == 3`, `conflicts.length == 0`. Retried the
    identical request (same idempotency key) - same 201, same 3 appointments
    returned, **confirmed in Postgres** exactly 3 rows exist for that
    `series_id` (not 6) - the idempotent-retry design works as intended.
  - `GET /api/appointments` (tenant-scoped, as `clinic_admin`) - all 6
    appointments created above showed up with the right channels.
  - **Not yet live-verified this way** (covered only by the Testcontainers
    suite, which can't run locally - see above): the cross-tenant `front_desk`
    403 and the clinic-inactive 409 path. Low risk (both are small, direct
    conditionals already exercised in the integration test source), but
    don't claim they're confirmed live until either CI runs or someone
    checks them through the browser too.

## Verified this session - phase 3 (2026-09-13)

- `mvn -q clean compile test-compile` - clean. `mvn test`: 20/20 unit tests
  pass for real (`SlotGeneratorTest` 6, `TenantContextFilterTest` 7,
  `FeeCalculatorTest` 7 - new this phase). All integration test classes
  (including the three new phase-3 ones) hit the same Testcontainers/
  Windows Docker Desktop npipe wall as phases 1-2 - still the known,
  documented limitation, not a new issue.
- **A real bug found while writing `AppointmentCancellation`**: it initially
  extended `BaseTenantEntity` (which mandates a `created_at` column), but
  `V3__cancellation_and_checkin.sql` only has `cancelled_at` - Hibernate's
  schema validation caught this immediately at context startup
  (`SchemaManagementException: missing column [created_at]`). Fixed by not
  extending `BaseTenantEntity` (same shape as `AppointmentSeries` from phase
  2) - `cancelledAt` already captures "when this row was created", a
  separate `created_at` would be redundant. `AppointmentReschedule`/
  `FeePolicy` both correctly extend it - their tables do have `created_at`.
- **V3 migration applied cleanly** against the live stack once fixed
  (`spring-boot-api` logs: "Successfully applied 1 migration ... now at
  version v3").
- **A second, more consequential real bug, found live**: `node-bff`'s
  container had been running continuously since phase 1 and was never
  rebuilt after `PUBLIC_ROUTES` was populated in phase 2 - every
  "permitAll" endpoint (`/api/clinics`, `/api/clinics/*/availability`,
  `/api/appointments/guest`, `/api/appointments/track/*`) was silently
  falling through to `requireSession` and 401ing for any genuinely
  anonymous caller, the whole time. Never caught in phase 2 because that
  phase's guest-booking verification went straight to `spring-boot-api:8081`
  directly, bypassing the BFF entirely. Caught this phase only because the
  live check-in verification routed everything through node-bff's real
  session/proxy path on principle. Fixed by rebuilding/recreating the
  `node-bff` container - **lesson for future phases: after editing
  `node-bff/src/routes/api.js`'s `PUBLIC_ROUTES`, always
  `docker compose up -d --build --force-recreate node-bff`, and verify
  anonymous access through node-bff's own port (`:3000` or via nginx),
  never only via a direct `curl` to spring-boot-api, which will pass even
  when the BFF layer is silently broken.**
- **The whole cancel/reschedule/check-in flow was live-verified end to end**
  through the real browser session and, for the anonymous paths, through
  node-bff directly with no cookies at all (confirming the fix above):
  - Seeded three clinic-wide fee tiers via
    `infra/postgres/seed-demo-scheduling-data.sql` (extended this phase):
    24h+ = 0%, 2-24h = 50%, <2h = 100%.
  - Staff-cancelled a guest booking with ~4.6h notice - fee recorded as
    `$25.00` (50% of the $50 appointment type), matching the middle tier;
    slot freed to `open`. Cancelling the same appointment again -> 409.
  - Staff rescheduled an appointment to a different slot (same provider) -
    old slot freed, new slot booked, `appointment_reschedules` row recorded
    with the correct `previous_slot_id` and the flat fee (`$0.00`, the
    platform default).
  - Full check-in sequence on a real appointment: `check-in` ->
    `checked_in`, `room` -> `roomed`, `start` -> `with_provider`,
    `check-out` -> `checked_out` - all 200. Re-calling `check-out` again
    -> 200 unchanged (idempotent). Calling `check-in` again on a
    `checked_out` appointment -> 409 `"Cannot check in an appointment with
    status 'checked_out'"`.
  - Identity check: a patient with `national_id = 'ID-ON-FILE-999'` -
    presenting `WRONG-ID` -> 409; presenting the matching id -> 200. A
    *different* patient with no `national_id` at all - presenting any
    string at check-in -> 200 (confirmed `patientNationalId: null` in the
    response before check-in), exactly the pinned decision.
  - Manual `no-show` from `booked` -> 200, idempotent on repeat.
  - The scheduler's bulk-update SQL (the exact statement
    `AppointmentRepository.flipStaleBookedToNoShow` issues) run directly
    against a seeded stale `booked` appointment (slot ended 2.5h ago) -
    flipped to `no_show` correctly. The live 5-minute `@Scheduled` firing
    itself wasn't observed by waiting out the real timer in this session
    (impractical mid-verification) - only the SQL logic was confirmed this
    way; a future session should confirm the actual timer fires too.
  - Patient self-service: booked as `demo-patient`, confirmed the
    staff-only `/api/appointments/{id}/cancel` 403s for a patient token,
    then successfully self-cancelled via `/api/my-appointments/{id}/cancel`.
  - **Not yet live-verified this way**: patient self-reschedule through the
    browser specifically (covered by `RescheduleIntegrationTest` only, which
    can't run locally) - the mechanism is identical to self-cancel's, which
    *was* verified live, so risk is low but not zero.

## Verified this session - phase 4 (2026-09-13)

- `mvn -q clean compile test-compile` - clean. `mvn test`: still 20/20 unit
  tests pass for real (no new pure-unit tests this phase). All integration
  test classes, including the two new phase-4 ones
  (`EncounterIntegrationTest`, `ProviderScheduleIntegrationTest`), hit the
  same Testcontainers/Windows Docker Desktop npipe wall as phases 1-3 -
  confirmed still the same known limitation, not a new issue (checked the
  actual surefire report: `ExceptionInInitializerError: ... Could not find a
  valid Docker environment`, identical root cause every phase).
- **No new migration needed** - Flyway confirmed "up to date" at `v3` on
  container restart; Hibernate validated the new `Encounter`/`Prescription`
  entities against `V1__init.sql`'s existing columns with no drift.
- **A new `demo-provider` login had to be created for the first time this
  phase** - added to `infra/keycloak/realm-export.json` for a from-scratch
  environment, but since `start-dev --import-realm` only imports a realm
  that doesn't already exist yet (confirmed: the already-running `clinic`
  realm did **not** pick up the new user from a `keycloak` container
  restart alone), the already-running instance needed the user created live
  via the Keycloak admin REST API instead (same pattern
  `create-demo-clinic.sh` already uses for org membership) - realm role
  `provider` assigned, added as a member of the `demo-clinic` Organization.
  Logging in once auto-provisioned its `app_users` row (same lazy
  `CurrentUserService` provisioning every other role already gets); then
  `UPDATE providers SET app_user_id = ...` linked it to the phase-2-seeded
  "Dr. Demo Provider" row - documented here as a one-time manual step, same
  spirit as `create-demo-clinic.sh`'s own copy-paste convention, since
  provider admin CRUD doesn't exist until phase 5.
- **The whole flow was live-verified end to end** through real browser
  sessions (not just direct `curl`), covering both the happy path and every
  guard:
  - `GET /api/my-schedule` as `demo-provider` - returned real appointments
    scoped to that provider for today (confirmed count changes correctly as
    appointments were added), and correctly excluded a different provider's
    and a different day's appointments once a second test provider was
    created for comparison.
  - A provider account with no linked `Provider` row - confirmed the exact
    404 message (`"No provider profile linked to this account..."`) live,
    both on `/api/my-schedule` and (implicitly, before linking) proved the
    `AppUser` auto-provisioning still runs before that check fails.
  - Booked a real appointment (guest channel) on the demo provider, drove it
    through `check-in -> room -> start` as `demo-clinic-admin` to reach
    `with_provider`, then confirmed the encounter status gate 409s a
    still-`booked` appointment with the exact message
    (`"Cannot document an encounter for an appointment with status
    'booked'"`).
  - `POST .../encounter` (create) then a second call (update) on the
    `with_provider` appointment - confirmed via direct Postgres query
    exactly **one** `encounters` row exists for that appointment (same id
    both times, updated chief complaint), not a duplicate.
  - Flipped the appointment to `checked_out` and called `POST .../encounter`
    again - 200, succeeded, confirming the "editable indefinitely" decision
    live, not just as an untested assumption.
  - `POST .../encounter/prescriptions` with 2 items, then again with a
    different single item - confirmed via Postgres exactly 1 row remains
    (the second, `Azithromycin`/`Amoxicillin` swap), proving full-replace
    -not-merge semantics.
  - `POST .../encounter/prescriptions` against an appointment with no
    encounter yet - 400 with the clear message, live.
  - Created a second demo provider account, linked to a different
    `Provider` row in the same clinic, and confirmed `POST .../encounter`
    against the first provider's appointment 403s for the second provider -
    the ownership check works against real distinct provider identities,
    not just the single-provider happy path. (This second account was
    temporary, used only for this check, and removed afterward - not part
    of the documented demo setup.)
  - `clinic_admin` successfully created an encounter on a provider's
    appointment with no ownership check, confirmed live (the override path).

## Verified this session - phase 5 (2026-09-13)

- `mvn -q clean compile test-compile` - clean. `mvn test`: still 20/20 unit
  tests pass for real (no new pure-unit tests this phase). All integration
  test classes, including the six new phase-5 ones, hit the same
  Testcontainers/Windows Docker Desktop npipe wall as phases 1-4 - checked
  a surefire report directly, same `IllegalStateException: Could not find
  a valid Docker environment` root cause every time, not a new issue.
- **`V4__clinic_admin_config.sql` applied cleanly** against the live stack
  (`spring-boot-api` logs: "Successfully applied 1 migration ... now at
  version v4"); Hibernate validated the new `status` columns with no drift.
- **The whole CRUD/settings/branding/linking surface was live-verified end
  to end** against the real running stack, as `demo-clinic-admin` and
  `demo-provider`:
  - Created a provider (with a `roomId`), a room, an appointment type, and
    a fee-policy tier via their respective `POST`s - each showed up via its
    own `GET` list.
  - Deactivated then reactivated the new provider via `/update` -
    `?status=active` correctly excluded it while inactive. Same confirmed
    for the room and appointment type; the new public appointment-types
    listing correctly stopped including the deactivated type.
  - Deleted the fee-policy tier via `/delete` - gone from the list
    afterward (real delete, not soft-deactivate, confirmed).
  - `GET /api/clinics/{id}/appointment-types` confirmed **genuinely
    anonymous** via a bare `curl` through `node-bff` with zero cookies -
    200 with the narrow projection, not just proven from an authenticated
    browser tab. `node-bff` was rebuilt after adding the route to
    `PUBLIC_ROUTES`, per the phase-3 lesson.
  - `GET /api/clinic/settings` before any override showed pure platform
    defaults; a full-replace `POST` (`rescheduleMinNoticeHours: 48`,
    a support phone) showed up in both `overrides` and `effective`; a
    later `POST` with that field omitted (`null`) reverted `effective`
    back to the platform default (4h) - confirmed live, not just via the
    integration test.
  - **The `RescheduleService` refactor's live proof**: booked a guest
    appointment with ~23h notice (comfortably clearing the old 4h default
    but not the new 48h override), then attempted to reschedule it as
    `demo-clinic-admin` - `409 "Fewer than 48 hours remain..."`, where the
    same request would have succeeded before the override. Reverted the
    override back to defaults afterward.
  - `POST`/`GET /api/clinic/branding` as `clinic_admin` - `displayName`
    correctly fell back to "Demo Clinic" before any branding was set, then
    reflected the new logo/colors/display name/footer after `POST`.
    Confirmed `GET` also succeeds as `demo-provider` (staff-read carve-out)
    and settings stayed untouched by the branding write (disjoint columns).
  - **Provider-login linking, live**: unlinked `demo-provider` from
    "Dr. Demo Provider", linked it instead to the newly-created provider by
    email (`findFirstByEmail`) - confirmed via Postgres the FK moved to the
    new row, and `GET /api/my-schedule` (as `demo-provider`) immediately
    started resolving against the new provider (empty list, since it has
    no appointments, instead of the old provider's 7). Restored the
    original link afterward to keep the demo environment's documented
    baseline intact.
  - Working-hours: confirmed the overlap rejection is real by attempting a
    window that collided with the seeded provider's existing 9am-5pm
    -every-day windows (409, correctly caught even though my first test
    attempt picked an already-occupied day/time by mistake); a genuinely
    free window (18:00-19:00) succeeded, then was removed via `/remove`.

## Verified this session - phase 6 (2026-09-13)

- `mvn -q clean compile test-compile` - clean. `mvn test`: **23/23** unit
  tests pass for real (`SlotGeneratorTest` 6, `TenantContextFilterTest` 7,
  `FeeCalculatorTest` 7, `ClinicProvisioningServiceTest` 3 - new this
  phase). `PlatformControllerIntegrationTest` hits the same Testcontainers/
  Windows Docker Desktop npipe wall as every other integration test class -
  checked its surefire report directly, identical
  `IllegalStateException: Could not find a valid Docker environment` root
  cause, not a new issue.
- No migration needed - `clinics` already had everything; Flyway confirmed
  "up to date" at `v4` on container restart.
- **The whole onboarding/deactivate/reactivate flow was live-verified end
  to end** against the real running stack, as a genuine `demo-platform-admin`
  (created fresh this phase, live via the admin API - same
  `--import-realm`-skips-an-existing-realm reasoning as phase 4's
  `demo-provider`):
  - `POST /api/platform/clinics` for a brand-new "Second Demo Clinic" -
    confirmed it's really in Keycloak (`GET .../organizations` listed it
    with the right alias/domain) **and** in the local `clinics` table with
    `keycloak_org_id` = the alias, not Keycloak's internal org id.
  - Repeating the same `orgAlias` - 409, and confirmed via Keycloak's own
    org list that the rejected attempt created **zero** new/orphaned orgs
    (still exactly 2: `demo-clinic` + `second-demo-clinic`) - the local
    pre-check genuinely prevents the wasted admin-API round trip, not just
    in theory.
  - `GET`/`GET /{id}` listed both clinics; `POST .../update` renamed the
    new one.
  - **Reused the real `demo-clinic`/`demo-clinic-admin` for deactivate/
    reactivate**, since a brand-new clinic has no staff user yet:
    `POST .../deactivate` on `demo-clinic` (idempotent re-call confirmed
    too), then confirmed live - via a real browser session, not just an
    integration test - that `demo-clinic-admin`'s `GET /api/clinic/me` now
    403s with the exact pre-existing `"Clinic account is deactivated"`
    message, and a real guest booking against one of `demo-clinic`'s own
    open slots 409s with `"This clinic is not currently accepting
    bookings"`. `POST .../reactivate` restored both - a guest booking
    against the same slot then succeeded (200), confirmed via `curl`.
  - Learned mid-verification: the browser's node-bff session cookie and
    Keycloak's own SSO cookie are both shared **per browser profile, not
    per tab** - opening a second tab and logging in as a different user
    silently replaces the session in every other open tab too, and
    revisiting `/auth/login` after only a node-bff-level logout silently
    re-authenticates via the still-live Keycloak SSO session rather than
    prompting again. Switching users cleanly required a real Keycloak
    logout (`/realms/clinic/protocol/openid-connect/logout`, confirming
    the "Do you want to log out?" prompt) first. Noting this here since
    it's cost real time twice now (this phase and phase 4) - worth
    remembering for any future multi-role live verification.

## Known gaps (don't pretend these are done)

- No initial `clinic_admin` user provisioning as part of clinic onboarding
  (deliberate, see "Domain decisions") - after `POST /api/platform/clinics`,
  someone must still create/assign a `clinic_admin` user in Keycloak by
  hand and add them as an org member before the new clinic is actually
  usable.
- No clinic-admin/settings/branding **frontend UI** yet (phase 5 stayed
  backend-only, same as every phase so far) - the CRUD/settings/branding
  endpoints exist but nothing in `node-bff/frontend/` calls them yet.
- No provider clinical **frontend UI** yet (phase 4 is backend-only, same
  as every phase so far) - `GET /api/my-schedule`/encounter endpoints exist
  but nothing in `node-bff/frontend/` calls them yet.
- No PHI-access audit log in v1 (deferred by decision, 2026-09-12).
- No lab-orders module yet - scoped as its own later session.
- No invoicing/payment creation wired to booking yet (not assigned to any
  phase so far - see "Domain decisions"). Cancellation/reschedule fees are
  **computed and recorded** (`appointment_cancellations.fee_amount`,
  `appointment_reschedules.fee_amount`) but nothing actually charges or
  collects them - `payments`/`invoices` tables exist in `V1__init.sql` but no
  code reads/writes them.
- Email/notifications: the `notifications` outbox table is written to
  (`AppointmentWriter`/`CancellationService`/`RescheduleService`) but there's
  no `NotificationWorker`/real sender yet - rows just accumulate with
  `status = 'pending'`.
- No per-clinic timezone - `SlotGenerator` interprets `provider_working_hours`
  in UTC. Fine for a single-timezone deployment, wrong for one spanning
  multiple.
- Recurring-series cancellation isn't its own concept - cancelling one
  occurrence just cancels that one `Appointment` row via the normal cancel
  endpoint; there's no "cancel the rest of the series too" option. Not
  decided whether one should exist.
- Cross-tenant `front_desk` 403 and clinic-inactive 409 for the booking flow
  (phase 2), and patient self-reschedule through the browser specifically
  (phase 3), are covered by the (locally-blocked) integration suite but not
  yet confirmed live - see the phase 2/3 "Verified this session" sections.
- The `NoShowScheduler`'s actual 5-minute `@Scheduled` firing has only been
  confirmed by running its underlying SQL directly, not by observing the
  real timer fire - low risk (the query itself is verified, `@Scheduled`/
  `@EnableScheduling` are standard, unremarkable wiring) but not literally
  watched happen.
- **Lesson from a real bug this phase**: after editing `node-bff/src/routes/
  api.js`'s `PUBLIC_ROUTES`, the `node-bff` container must be rebuilt/
  recreated (`docker compose up -d --build --force-recreate node-bff`)
  before those routes take effect - a long-running container silently keeps
  serving its old code. Verify anonymous/public endpoints through node-bff's
  own port, never only via a direct curl to spring-boot-api - that will pass
  even when the BFF's public-route bypass is broken or stale.
- The Testcontainers-backed integration suite (`ClinicControllerIntegrationTest`
  and everything built on `AbstractIntegrationTest` in later phases) cannot
  run on this dev machine at all - a Windows Docker Desktop npipe
  incompatibility, the same one already documented in the reference
  bus-ticketing-saas project's `pom.xml`. It should run clean in GitHub
  Actions CI (`ubuntu-latest`) - confirm that once this repo has a remote
  and CI has actually run, don't assume.
- This machine runs `bus-ticketing-saas` too, and both projects default to
  the exact same set of ports (`:80`, `:3000`, `:5432`, `:6379`, `:8080`,
  `:8081`) by deliberate convention (mirrored architecture). Only one of the
  two stacks - docker-compose or natively-run (`start-local.ps1`) - can be up
  at a time on this box; check for port conflicts (`netstat -ano`) before
  assuming a failed `docker compose up` is this project's own bug.
