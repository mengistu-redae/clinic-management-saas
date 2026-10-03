# CLAUDE.md

Running project memory for clinic-management-saas - what this is, the
architecture, the commands, the tenancy model, and a dated log of every
design decision and why. Updated every session; append and revise in place
rather than rewriting wholesale.

## What this is

A multi-tenant clinic management SaaS. Tenants are **clinics**. Each clinic
manages its providers, rooms, and appointment calendar. Patients book through
a patient portal; front-desk staff book/reschedule/check-in walk-in patients
at the counter; providers document visits and order labs (see "Phase plan").

Modeled deliberately on the architecture and working conventions of a prior
bus-ticketing SaaS at `D:\git-mengistu\SpringBoot\bus-ticketing-saas` -
wherever a convention here looks arbitrary, it's most likely inherited
verbatim from that project's own hard-won fixes. Its own `CLAUDE.md`/code is
worth checking directly if something here is under-explained.

```
browser --> nginx --> node-bff (session, OIDC, PKCE) --> spring-boot-api (JWT, tenant-aware) --> postgres, redis
                â†˜ keycloak (login + admin console only)
```

- **`spring-boot-api/`** - Java 21 / Spring Boot 3.3, Maven. Stateless,
  validates bearer JWTs, never talks to the browser directly. Flyway
  migrations only (`V1__init.sql`, ...), `ddl-auto: validate`.
- **`node-bff/`** - Node â‰¥20 / Express, npm. The only thing the browser
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
- **`TenantIsolationIntegrationTest`** (built 2026-09-20, see "Post-phase-7
  backend additions") - for every staff-scoped resource, clinic A seeds it
  and clinic B's staff is refused (404/403). `ClinicControllerIntegrationTest`
  (phase 1) and `AppointmentControllerIntegrationTest` (phase 2) seeded this
  idea per resource, and every later per-resource controller test kept
  doing the same in more depth (validation edges, role combinations) - the
  consolidated file doesn't replace any of those, it's the one-file
  "did we forget tenant scoping on something new" regression guard that was
  still just a plan through phase 7.

## Domain decisions pinned so far

- **Patient scope: per-clinic** (decided 2026-09-12, in plan mode before any
  code was written). Each clinic's patients are entirely its own - a person
  seen at two different clinics gets a separate `patients` row at each, with
  no cross-clinic linking/dedup. `patients.tenant_id` is `NOT NULL`.
- **PHI-access audit: deferred** (decided 2026-09-12; **superseded 2026-09-20**,
  see "Post-phase-7 backend additions") - `com.clinicops.phiaudit` now logs
  staff access to patient/encounter/lab-order data, reviewable by
  `clinic_admin` at `GET /api/clinic/phi-access-log`.
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
  2026-09-13; **superseded 2026-09-20** by phase 12 - now editable freely
  only until explicitly signed) - no freeze/lock concept once
  `checked_out`, since nothing in the schema or kickoff spec asks for one
  and providers commonly need to amend a note after the fact.
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
  2026-09-13; **superseded 2026-09-19**, see "Post-phase-7 backend
  additions" - `POST /api/platform/clinics` now optionally creates one too)
  - `POST /api/platform/clinics` creates the Keycloak Organization + local
  `clinics` row only, matching both the kickoff spec's silence on this and
  the reference project's identical scope (`OperatorProvisioningService`
  never creates a user either). A real gap - a freshly onboarded clinic
  has nobody who can log in until someone creates/assigns a `clinic_admin`
  user by hand - deliberately left there, not solved differently here.
- **Vitals: writable by `front_desk` too, not just clinical staff**
  (decided 2026-09-20, phase 8 plan mode) - the one deliberate exception to
  this app's "front_desk has zero clinical access" boundary
  (`EncounterController`'s own convention since phase 4). Made because this
  app has no "nurse" role and vitals are typically taken by front-desk/
  nursing staff before a provider ever sees the patient - opening this one
  narrow door felt more honest than pretending front_desk could route
  vitals through a provider-only endpoint. Revisit if this turns out too
  permissive; there's no existing-codebase precedent forcing it either way.
- **Vitals: no appointment-status gate** (decided 2026-09-20) - unlike
  `EncounterController`'s `with_provider`/`checked_out` gate, vitals can be
  recorded at any non-cancelled status. Vitals are typically taken at
  check-in/roomed, well before a clinical note could even legally exist
  under Encounter's own gate - gating vitals the same way would block the
  real workflow. Same "no status gate" precedent `AppointmentPaymentController`
  already set.
- **Vitals hang off `Appointment`, not `Encounter`** (decided 2026-09-20) -
  a fresh design call, not mirrored from anywhere: tying vitals to
  Encounter would either require granting front_desk access to clinical
  note content (a bigger boundary change than intended) or duplicating
  data across two tables. One `Vitals` row per appointment
  (`appointment_id` unique, same "exactly one per visit" shape as
  `encounters.appointment_id`) sidesteps both.
- **Allergies: patient-level, no delete - status transitions only**
  (decided 2026-09-20) - an allergy is part of the patient's standing
  health record, not tied to one visit, so it lives on `patients.id`, not
  an appointment/encounter. `AllergyController` has no delete endpoint;
  correcting a mistaken entry means adding a new row and marking the old
  one `resolved`/`unconfirmed` via its `status` field - matches this app's
  own precedent for safety/history data (providers/rooms soft-deactivate
  rather than hard-delete).
- **Allergies: access gate mirrors `PatientController` exactly** (decided
  2026-09-20, the one call made without an explicit question to the user -
  flagged as revisitable) - `front_desk`+`clinic_admin` write, `+provider`
  read. Reasoned as: allergies extend the patient record itself rather
  than a specific clinical encounter, the same way demographics do, so the
  same three roles that already manage `Patient` rows manage `Allergy`
  rows. No strong existing-codebase precedent forces this either way.
- **Medical history: single evolving row per patient, not a versioned
  log** (decided 2026-09-20, phase 9 plan mode) - mirrors `ClinicSettings`'
  own "singleton row, updated in place" pattern (`patientId` as the
  primary key itself, no separate `id`). No other clinical entity in this
  app keeps version history - even Encounter's own future sign-and-lock
  treatment (phase 12 sketch) was flagged as a deliberate special case,
  not the default this app reaches for. The PHI audit log already records
  that a write happened without needing a field-level diff table.
- **Medical history: writable by `front_desk`+`clinic_admin`+`provider`**
  (decided 2026-09-20) - same three-role gate as Vitals (phase 8) and for
  the same reasoning: this is typically collected as intake/registration
  paperwork (patient self-report transcribed by whoever hands them the
  form), not a clinical judgment call the way an Encounter's own
  assessment is - so front_desk isn't excluded the way it is from
  `EncounterController`.
- **File storage for future EHR phases: local disk + a Docker volume, not
  S3/cloud** (decided 2026-09-20, specified directly by the user ahead of
  the phase that will actually need it) - not consumed by phase 10 after
  all (see below, consent stayed deliberately lightweight/imageless);
  still applies whenever phase 13 (provider digital signatures) or any
  future real e-signature-capture refinement of phase 10 gets built. Mount
  a named volume in `docker-compose.yml` matching the existing `postgres`/
  `redis` pattern when that day comes.
- **Consent: general-treatment + privacy consent only, no procedure
  -specific consent** (decided 2026-09-20, phase 10 plan mode) - covers
  the reference clinical-forms doc's Forms 4 & 6 (both simple
  acknowledgment-style records: consent given, policy version, timestamp).
  Form 5 (procedure-specific - risks explained, alternatives discussed, a
  specific physician) is a genuinely different shape, deferred rather than
  folded into the same entity via awkward nullable fields.
- **Consent: no signature image, even though file storage is now
  available** (decided 2026-09-20) - stays a lightweight acknowledgment
  record (`consentGiven` bool + `policyVersion` + `signedAt` + who
  recorded it). Real signature-image capture (a genuinely separate chunk
  of work - multipart upload, disk read/write, validation) deferred to a
  later refinement if ever needed, not built speculatively now that the
  storage prerequisite happens to be resolved.
- **No "nurse" realm role introduced for consent** (decided 2026-09-20) -
  the user explicitly authorized adding one if the phase needed it, but
  consent recording doesn't: it's intake-collected, not a clinical
  -judgment call, so `front_desk`+`clinic_admin` (matching `Allergy`'s own
  gate) covers it the same way phases 8/9 already covered vitals/allergies/
  medical-history without a nurse role. The underlying gap (no role
  precisely matching "clinical support staff who aren't the provider") is
  still real and unaddressed - just not one this phase happened to need
  solved.
- **Consent records are genuinely immutable - no update/delete endpoint at
  all** (decided 2026-09-20, a fresh design call, not something the user
  was asked about directly) - matches this app's own existing audit-only
  tables (`AppointmentCancellation`, `AppointmentReschedule`,
  `PhiAccessLog`) and the reference doc's own "this matters for legal
  defensibility" reasoning for consent specifically. A patient can
  accumulate multiple rows of the same `consentType` over time (e.g.
  re-consenting after a policy version change) - `GET` returns the full
  list, not a singleton, unlike Allergy/Vitals/MedicalHistory.
- **Prescription stays encounter-scoped, full-replace-list, no per-item
  CRUD** (decided 2026-09-20, phase 11 plan mode) - the new `status` field
  (active/completed/discontinued) describes one specific prescription
  instance within its own encounter's list, not a cross-encounter ongoing
  -medication concept (this table has no `patientId`, only `encounterId` -
  it structurally can't represent "a medication from 3 visits ago"). Kept
  the existing full-replace mechanism rather than inventing per-item
  create/update endpoints just because `status` was added.
- **ICD-10 on Encounter is free text, not a real coded lookup** (decided
  2026-09-20) - staged as a plain `TEXT` column (`icd10Codes`), never
  validated against an actual ICD-10 dataset. Importing/maintaining tens
  of thousands of real codes is a separate, much bigger later decision
  than this phase's own "keep minimal" scope.
- **Prescription `route` is an allow-listed string, not free text** (decided
  2026-09-20, a call made without an explicit question to the user) -
  oral/iv/im/subcutaneous/topical/inhaled/rectal/sublingual/other, same
  "backend-defined bounded set validated in code, not a DB enum type"
  convention as `Allergy.severity`/`Room.status`. `other` is the escape
  hatch for a route this list doesn't name.
- **Sign-and-lock: an explicit `sign` action, not automatic on
  `checked_out`** (decided 2026-09-20, phase 12) - matches this app's
  existing convention of dedicated action endpoints for consequential
  transitions (deactivate/reactivate, check-in's own states) rather than
  an implicit side effect. Idempotent re-call, same convention as
  `CheckInService` - but unlike those, **no unsign/reopen endpoint exists
  anywhere**; once signed, always signed.
- **Locking cascades to prescriptions, not to vitals** (decided
  2026-09-20) - prescriptions were part of that visit's documentation;
  `Vitals` (phase 8) is a structurally separate, `Appointment`-scoped
  table writable by `front_desk` too, and measured values don't need the
  same amendment-vs-edit treatment as clinical narrative.
- **Addenda require the encounter already signed** (decided 2026-09-20) -
  before signing, a provider just edits directly; addenda are the
  post-signing correction case specifically, not a general-purpose
  comment feature.
- **`EncounterLockedException` is its own type, not a reuse of
  `InvalidAppointmentStatusException`** (decided 2026-09-20) - this is the
  *encounter's* lock state, not the *appointment's* status; an appointment
  can be `with_provider`/`checked_out` while its encounter is separately
  locked.
- **Referrals: one entity, not two** (decided 2026-09-20, phase 14) -
  internal vs. external derived from which nullable field group is
  populated, matching `Payment`'s own `appointmentId`/`labOrderId`
  precedent.
- **Referrals: provider + clinic_admin only, no front_desk** (decided
  2026-09-20) - matches `Encounter`'s gate; a clinical judgment, not
  front-desk logistics.
- **Invoices: extended to lab orders, manual generation, immutable once
  issued** (decided 2026-09-21, phase 15) - `invoices` gained `payments`'
  own dual nullable-FK + exactly-one-owner CHECK; `POST .../invoice` is a
  deliberate `front_desk`/`clinic_admin` action with no status gate (not
  an automatic side effect of `checked_out`/`reviewed`), and a repeat
  call 409s rather than replacing - unlike Encounter's upsert-in-place.
  `ClinicSettings.taxRatePercent` (dormant since phase 5) is finally
  consumed here via `ClinicSettingsService.resolve`.

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
7. **Lab orders module** (built 2026-09-13) - lab test rate configuration,
   the full lab-order lifecycle (`requested`/`ordered ->
   specimen_collected -> in_transit -> resulted -> reviewed`, or
   `cancelled`), a patient-initiated request -> staff confirm-and-order
   flow, public two-factor order tracking, and a shared `Payment` entity
   covering both appointments and lab orders (closing a gap left open since
   phase 1). This was the last phase named in the kickoff spec's own
   original phase plan. See "Phase 7: lab orders module" below and
   "Verified this session - phase 7".

**Revised phase plan (2026-09-20)** - after the kickoff spec's own 7 phases
shipped, the user asked what a pivot toward a fuller EHR (vs. this app's
original booking/scheduling-centric scope) would look like. Sketched in
chat as phases 8+, not yet all committed to - phase 8 is the only one
built so far:

8. **Patient-safety fundamentals: allergies + vitals** (built 2026-09-20) -
   persistent, patient-level allergy records and per-appointment vitals.
   Smallest-footprint, highest-real-world-value item from the sketch,
   deliberately chosen first. See "Phase 8: allergies + vitals" below.
9. **Persistent medical history** (built 2026-09-20) - past conditions,
   home medications, family/social history. See "Phase 9: medical
   history" below.
10. **Consent & compliance records** (built 2026-09-20) - general
    -treatment and privacy/data-protection consent, no signature image
    (deliberately kept lightweight even with file storage now available).
    See "Phase 10: consent & compliance records" below.
11. **Prescription + coding depth** (built 2026-09-20) -
    route/frequency/duration/quantity/refills/status on `Prescription`,
    free-text ICD-10 tags on `Encounter`. See "Phase 11: prescription +
    coding depth" below.
12. **Sign-and-lock clinical notes** (built 2026-09-20, explicit user
    sign-off obtained first via a direct question) - reverses the phase-4
    "editable indefinitely" decision. Once signed, an encounter and its
    prescriptions lock; corrections go through a new append-only
    `EncounterAddendum` instead. See "Phase 12" below.
13. **Provider profile hardening** (built 2026-09-20) - license number/
    expiry, employment status, digital signature - the first feature to
    use the file-storage decision below. See "Phase 13" below.

**File storage, decided ahead of need (2026-09-20)** - phases 10 and 13
above were flagged as blocked on a file-storage decision when first
sketched. The user has since specified **local disk + a Docker volume**
(not S3/cloud object storage) for whenever a phase actually needs real
file uploads - no longer an open question, just not yet implemented since
no phase needing it has been built. Mount a named volume in
`docker-compose.yml` (matching the existing `postgres`/`redis` pattern)
when that phase is built.
14. **Referrals** (built 2026-09-20) - internal (provider-to-provider) and
    external, one entity. See "Phase 14" below.
15. **Real billing build-out** (built 2026-09-21) - `invoices` extended to
    cover lab orders too, staff-triggered generation, real tax-rate wiring.
    See "Phase 15" below.

**Deferred phase plan (sketched 2026-09-21, not yet built)** - the user
asked for these three "Known gaps" items to be planned ahead of time, with
the key design forks pinned via direct questions rather than left as
assumptions. All three are now built (backend-only) - phase 17's own
scope was revised when picked up (email-only via a local Mailpit
catcher, SMS deferred - see its own write-up for why). See "Phase 16"/
"Phase 17"/"Phase 18" below for the full write-up of what's pinned.

16. **Real payment gateway + refund flow + invoice PDF + payment-invoice
    linking** (built 2026-09-24) - a pluggable `PaymentGatewayClient`
    (mock-only for now, real vendor deferred), full+partial refunds, and
    an on-demand invoice PDF. See "Phase 16" below.
17. **Real email delivery** (built 2026-09-24, scope revised to email-only
    via a real local Mailpit SMTP catcher - no SendGrid/Twilio account
    available; SMS deferred entirely). See "Phase 17" below.
18. **Per-clinic timezone** (built 2026-09-24) - a `ClinicSettings.timezone`
    override, consumed by slot generation and day-boundary math; existing
    slots are left untouched. See "Phase 18" below.

**Frontend phase N** - viewer-level language (English+Amharic)/
timezone-display/theme (light/dark/system) preferences, localStorage-only,
no backend changes except a phase-18 timezone-exposure reconciliation
(closed now that phase 18 itself is built - see below).
**Theme built 2026-09-22. Language (i18n + English + Amharic, full
per-page content sweep across all 32 relevant files) built 2026-09-22.
Timezone display built 2026-09-24**, once phase 18 unblocked it. See
"Frontend phase N" under "## Frontend" below.

19. **Clinic-admin analytics dashboard** (built 2026-09-24) - a genuinely
    new backend aggregation endpoint (`GET /api/clinic/analytics`) plus
    real charts (Recharts) on the clinic-admin dashboard. Built out of
    numeric order, ahead of the still-sketched phases 16-18, at the user's
    request. See "Phase 19" below and "Frontend phase O" under
    "## Frontend" below.

**New module set (sketched + phase 1 built 2026-09-27)** - the user asked
to add pharmacy, accounting, and finance modules, with the roles to run
them. Two rounds of direct questions were put to the user before designing
anything (full/inventory pharmacy vs. a log-only slice; accounting and
finance merged vs. two distinct modules; how many new realm roles) - all
three resolved with the broader/more capable option. Delivered as three
separate sequential phases against one shared design (see the plan
document referenced below) - Pharmacy first (this session), Accounting
next (Finance reads its ledger, so it has to come after), Finance last.

20. **Pharmacy - medication catalog, batch-tracked stock, dispensing**
    (built 2026-09-27) - a new `pharmacist` realm role; `com.clinicops.pharmacy`
    (`Medication`/`StockBatch`/`DispenseRecord`) dispenses against the
    existing `Prescription` (phase 4/11) without touching billing at all.
    See "Phase 20: pharmacy" below.
21. **Accounting** (built 2026-09-27) - chart of accounts + a real
    double-entry journal, the `accountant` realm role (already added to
    Keycloak in phase 20, shared with Finance below), auto-posts a journal
    entry when an existing `Payment`/`Refund` happens (the ledger picks up
    real cash events, it doesn't create new ones). See "Phase 21:
    accounting" below.
22. **Finance** (built 2026-09-28) - budgets, minimal payroll (salary + a
    monthly pay-run action), and P&L/budget-vs-actual reporting, built on
    top of phase 21's ledger. See "Phase 22: finance" below.
23. **Recurring-series cancellation** (built 2026-09-28) - "cancel this and
    the rest of the series," staff-only, closing a "Known gaps" item left
    open since the original phase-2 series expansion. See "Phase 23:
    recurring-series cancellation" below.

**Full-EHR-breadth backlog, pulled forward 2026-09-28** - previously
"lower priority, only if the product genuinely wants it"; the user
confirmed they do, and chose sequential phases over one combined build:

24. **Immunizations** (built 2026-09-28) - a simple per-patient vaccine
    administration log, no due-date/schedule tracking. See "Phase 24:
    immunizations" below.
25. **Physical-exam findings** (built 2026-09-28) - a fixed 9-system
    review-of-systems checklist added directly to `Encounter`, locking
    with the rest of the encounter when signed. See "Phase 25:
    physical-exam findings" below.
26. **Visit/encounter summary document** (built 2026-09-28/29) - a
    real on-demand PDF (encounter note, exam findings, prescriptions,
    vitals, active allergies, visit-linked immunizations), reachable by
    staff and, for the first time in this app, by the patient viewing
    their own past visit. See "Phase 26: visit/encounter summary
    document" below.

The full-EHR-breadth backlog (immunizations, physical-exam findings,
visit summary) is now fully built, closing out everything the user asked
to pull forward 2026-09-28.

**Pharmacy expansion + stock management module, sketched 2026-09-29, not
yet built**: ten more phases (27-34) turning phase 20's minimal pharmacy
module into a full-featured one, plus a *general* stock/inventory
management module (medications are just one category) covering clinical
supplies, PPE, office supplies, and equipment/assets - clinical safety
checks, controlled-substance dual-sign-off, a general stock/inventory
foundation (suppliers/purchase orders/reorder alerts, superseding the
original pharmacy-only supply-chain sketch), equipment/asset tracking
with a simple maintenance log, pharmacy billing integration, smarter
dispensing suggestions, patient-facing prescription/refill features, and
a unified reporting dashboard. See "Pharmacy expansion + stock
management module" below for the full write-up and the pinned
architecture decisions.

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
- **A real concurrent-refresh race found and fixed 2026-09-27**, evaluating
  `demo-front-desk`'s own `AppointmentDetail.jsx` live - that page fires six
  (now more, with the phase-8/9/10 `PatientChart` panel: a dozen) parallel
  queries on mount, so that many requests could hit `refreshIfExpired` at
  once right as the access token expired. Each used to call `getClient()
  .refresh()` independently with the same `refresh_token` - if Keycloak
  rotates refresh tokens on use, only the first of those concurrent calls
  succeeds and every other one gets `invalid_grant` for a token a sibling
  request had just successfully refreshed a moment earlier, destroying a
  perfectly good session and forcing a surprise re-login. Live-observed
  exactly this: navigating to an appointment detail page 401'd 5 of 7
  concurrent requests, silently destroyed the session, and Keycloak's own
  browser SSO cookie silently completed a fresh login - landing the user
  back on the dashboard instead of the page they were on, and on
  `localhost:3000` directly rather than through nginx (an artifact of
  `BFF_BASE_URL` pointing at the BFF's own dev-only host-mapped port,
  itself a pre-existing local-dev-only quirk, not touched here). Fixed by
  making the refresh single-flight per session -
  `node-bff/src/routes/api.js`'s `refreshIfExpired` now keys an in-flight
  refresh `Promise` by `req.sessionID` so concurrent requests sharing a
  session coalesce onto one Keycloak call instead of racing it, the same
  "share the one in-flight thing" idea `SlotLockService`'s Redis lock
  already applies to a different resource. `test/api.test.js` gained a
  dedicated regression case (two concurrent requests sharing a session ->
  exactly one `refresh()` call, both end up with the refreshed token) -
  `npm test` 25/25. Live re-verified post-fix: a fresh (non-expired) token
  now lets all dozen of that same page's parallel requests through cleanly
  on the first try.
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

Built 2026-09-12. Mirrors the reference bus-ticketing-saas project's
booking/scheduling code almost 1:1 (SlotLockService/BookingService/
BookingWriter/SeatLayoutGenerator ported to this domain). Lazy, idempotent
slot generation (UTC-only, no per-clinic timezone yet); SlotLockService
(Redis SETNX+TTL) fronting a split-bean AppointmentWriter; three booking
channels (patient_portal/front_desk/guest); idempotency via a
`(tenant_id, idempotency_key)` unique constraint; a public two-factor
tracking endpoint; patient auto-provisioning on first portal booking; and
a scope expansion decided in plan mode - bounded recurring-appointment
series, reusing the same single-slot lock/writer path in a loop.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 3: front-desk/counter + check-in

Built 2026-09-13. Mirrors the reference project's RefundPolicy/
RefundCalculator and Cancellation/Reschedule services. `FeePolicy`/
`FeeCalculator` (tiered, a provider-specific tier set replaces the
clinic-wide default entirely, never merged). `CancellationService`/
`RescheduleService` (staff + patient self-service paths, same
two-public-methods-one-private-helper split as the reference project).
The check-in state machine (`booked -> checked_in -> roomed ->
with_provider -> checked_out`, plus `no_show`) with a live time-window
gate and an identity-mismatch check that deliberately lets "nothing on
file" through rather than treating it as a mismatch. `NoShowScheduler`
bulk-flips stale bookings via a native SQL update.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 4: provider clinical flow

Built 2026-09-13. First code ever written against the `encounters`/
`prescriptions` tables (present since `V1__init.sql` but untouched).
`EncounterService.upsert` finds-or-creates by `appointmentId`;
`replacePrescriptions` is a full delete-and-reinsert, never a merge.
`CurrentProviderService` resolves a `provider`-role JWT to its own
`Provider.id` - unlike `Patient`, a `Provider` row is never
auto-provisioned, so an unlinked account gets a clear 404. A new
`GET /api/my-schedule` gives providers a day-scoped worklist.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 5: clinic-admin config

Built 2026-09-13. Mirrors the reference project's operator settings/
branding plus its Bus/Route/RefundPolicy CRUD almost 1:1. New
`V4__clinic_admin_config.sql` (a `status` column on rooms/appointment
-types - the only real schema gap left by then). `ClinicSettingsService
.resolve(tenantId)` is the one merge point for tax/fee/notice-hour
platform defaults vs. tenant overrides, split into two disjoint settings/
branding column groups. Provider/Room/AppointmentType/FeePolicy/
WorkingHours all get the same CRUD shape (`GET`/`GET {id}`/`POST`/
`POST {id}/update`, no PUT/PATCH/DELETE anywhere in this codebase).

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 6: platform-admin onboarding

Built 2026-09-13. Ports the reference project's `com.bustix.platform`
near-verbatim, pointed at the `clinic` realm. `KeycloakAdminTokenProvider`/
`KeycloakOrganizationClient` use `RestClient` directly (not the
`keycloak-admin-client` library - a deliberate classpath-conflict-risk
avoidance, the kickoff spec's own reasoning). `ClinicProvisioningService
.provisionClinic` pre-checks for a duplicate org **before** ever calling
Keycloak (confirmed live: a rejected duplicate creates zero orphaned
orgs). A real reactivate endpoint - going further than the reference
project, which only ever soft-deactivates with no way back.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 7: lab orders module

Built 2026-09-13. Drafted directly from the reference project's
`com.bustix.cargo` (waybills) module - order+line-items split, a manual
staff-driven status machine, snapshot pricing with a missing-rate 400
(the deliberate opposite of `fee_policies`' own "missing = zero"
fallback), generic `FeeCalculator` reuse for cancellation, two-factor
public tracking, and a patient-request -> staff-confirm two-phase flow.
Four scoping questions pinned in plan mode (manual-only result entry,
resulted-but-unreviewed visibility, the cancellation-fee tier, and a
brand-new shared `com.clinicops.payment` package closing the
long-standing "no invoicing wired to booking" gap). `collect-specimen`'s
identity check deliberately inverts phase 3's own check-in convention -
here, no ID on file **is** treated as a mismatch.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 8: allergies + vitals

Built 2026-09-20. First phase of the revised, EHR-leaning plan - no
reference-project precedent at all. `Allergy` (patient-level, standing
health record, no delete - correcting an entry means a new row +
`status` flip) and `Vitals` (per-appointment, `appointmentId` unique,
writable by `front_desk` too since this app has no "nurse" role, `getBmi
()` a derived non-persisted getter, no appointment-status gate since
vitals are typically taken before a provider ever sees the patient).
Both PHI-audited.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 9: medical history

Built 2026-09-20. `MedicalHistory` is a singleton row keyed by
`patientId` itself (mirrors `ClinicSettings`' own shape) - past
conditions/surgeries/medications/family+social history, all free text.
`upsert` is a genuine full-replace on every call (confirmed live that an
omitted field actually clears rather than just staying as it was), not a
partial-update the way most other resources here work.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 10: consent & compliance records

Built 2026-09-20. `ConsentRecord` - general-treatment + privacy/data
-protection consent only (the kickoff spec's Forms 4 & 6; the
procedure-specific Form 5 was deferred as a genuinely different shape).
No signature image even though file storage was already decided by this
point (a deliberately lightweight acknowledgment record). Genuinely
immutable - no update/delete endpoint at all, matching this app's own
audit-only-table precedent - and a patient can accumulate multiple rows
of the same `consentType` over time rather than the row being replaced.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 11: prescription + coding depth

Built 2026-09-20. A straightforward extension of two existing entities,
not a new resource. `Prescription` gained `route` (allow-listed)/
`frequency`/`duration`/`quantityDispensed`/`refillsAllowed`/`status`;
`Encounter` gained free-text `icd10Codes` (deliberately not validated
against a real ICD-10 code set - "keep minimal in v1").

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 12: sign-and-lock clinical notes

Built 2026-09-20 - the one phase flagged from its first sketch as
reversing existing behavior (phase 4's "editable indefinitely" decision),
so explicit user sign-off was obtained before writing any code, not just
a generic "proceed." `Encounter.sign` (idempotent) locks both the
encounter and its prescriptions; `EncounterAddendum` is the post-signing
correction path - only creatable once the parent encounter is signed,
and itself genuinely immutable once created. `EncounterLockedException`
is its own type, not a reuse of the appointment-status exception -
they're two independent lock concepts.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 13: provider profile hardening

Built 2026-09-20. License number/expiry, employment status (allow
-listed), and a digital signature image on `Provider` - the first
feature needing real file storage
(`com.clinicops.filestorage.FileStorageService`, local disk + a Docker
volume, per the phase-9 decision). A real, previously-latent `node-bff`
bug was found and fixed here: `forwardToApi` always JSON-re-serialized
`req.body`, silently dropping multipart file bytes before they ever
reached spring-boot-api.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 14: referrals

Built 2026-09-20. One `Referral` entity covers both internal
(provider-to-provider) and external referrals - the internal-xor
-external rule is enforced as a cross-field check (400 otherwise), since
a validation annotation alone can't express it. `provider`+`clinic_admin`
only, matching `Encounter`'s own clinical-judgment gate (no
`front_desk`).

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 15: real billing build-out

Built 2026-09-21. Closes "invoicing/billing deferred" (pinned since
phase 1). `Invoice` extends `BaseTenantEntity`, `appointmentId`/
`labOrderId` nullable with an exactly-one-owner CHECK (finally applied
to the table it was originally meant for, mirroring `Payment`'s own
phase-7 shape). Immutable once issued - a second generate for the same
owner throws a 409 rather than replacing, unlike `Encounter`'s
upsert-in-place. `ClinicSettings.taxRatePercent` (dormant since phase 5)
is finally consumed here.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 20: pharmacy

First phase of the new pharmacy/accounting/finance module set (see the
phase-plan entry above for the two rounds of scoping questions this whole
set was built against - full plan document referenced there). New realm
role `pharmacist`; `clinic_admin` overrides every endpoint here with no
ownership check, same as everywhere else. New migration `V17__pharmacy.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 21: accounting

Second phase of the pharmacy/accounting/finance module set (built
2026-09-27, same session as the demo-pharmacist bug fix above). Three
scoping questions were put to the user before writing any code - chart-of
-accounts model, journal-entry mutability, and whether phase 21 includes
any reporting - all three answered with the recommended option, matching
this project's own "ask before building" convention for every new module.
No new migration needed for the `accountant` realm role - it (and its
demo user) already exist from phase 20. New migration `V18__accounting.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 22: finance

Third and last phase of the pharmacy/accounting/finance module set (built
2026-09-28) - budgets, minimal payroll, and P&L/budget-vs-actual
reporting, built on top of phase 21's ledger. Three scoping questions were
put to the user before writing any code - payroll's employee model, what
the monthly pay-run action actually does, and budget granularity - all
three answered with the recommended option, same "ask before building"
convention every new module in this set has followed. No new realm role -
`accountant` (added phase 20) covers finance too, exactly as phase 21's
own write-up already anticipated. New migration `V19__finance.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 23: recurring-series cancellation

Closes the long-standing "Known gaps" item - cancelling one occurrence of a
recurring series used to just cancel that one `Appointment` row, with no
"cancel the rest of the series too" option. Scoped with one direct question
to the user before writing any code (whether the action should exist at
all, and who can use it) - answered staff-only, the recommended option,
matching this project's own "ask before building" convention. No new
migration - `appointments.series_id` has existed since the original
recurring-series phase 2 expansion; this phase is pure new behavior on top
of already-existing schema.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 24: immunizations

First of three sequential phases pulling the "lower priority, only if the
product genuinely wants full-EHR breadth" backlog forward (immunizations,
structured physical-exam findings, discharge/visit summary) - the user
confirmed they want it, and chose sequential phases over one combined
build, matching how every other module set in this project (pharmacy/
accounting/finance, the original EHR-leaning phases 8-15) was built.
Scoped with one direct question first: a simple per-patient administration
log, no due-date/schedule tracking, no reference immunization-schedule
data. New migration `V20__immunizations.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 25: physical-exam findings

Second of the three sequential full-EHR-breadth phases. Three scoping
questions were put to the user before writing any code - access gate,
storage shape, and sign-and-lock interaction - all three answered with
the recommended option. Body-system list taken from the user's own
`my-notes/clinical-forms-templates.md` ("Physical Examination Form"
section), restructured to the user's own "normal/abnormal flag + a
free-text note" shape rather than that template's plain free-text-per
-system fields. New migration `V21__physical_exam_findings.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 26: visit/encounter summary document

Third and last phase of the full-EHR-breadth backlog. Four scoping
questions were answered directly before any code was written, this time
mostly with the broader/fuller option rather than the recommended
minimal one (unlike phases 24/25): broad data scope (visit metadata,
encounter note + phase-25 exam findings, prescriptions, vitals,
allergies, immunizations), a real PDF (reusing the phase-16
`InvoicePdfService`/OpenPDF pattern), staff **and** patient self-view
access, and no sign requirement. New migration `V22__immunizations_visit_link.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Pharmacy expansion + stock management module - sketched 2026-09-29, not yet built

The user asked to turn phase 20's pharmacy module (catalog + batch stock
+ manual dispensing, deliberately minimal - no drug-name matching, no
billing integration, no supply chain) into a "full featured" module,
then - in the same planning session, before anything was built - asked
for a general stock management module too. Three rounds of direct
questions were put to the user before sketching anything, matching this
project's own "ask before building" convention for every new module set:

**Round 1 - pharmacy capability areas**: which areas to include (answered:
essentially all of them - clinical safety, supply chain, billing,
controlled substances, patient-facing, reporting, smarter dispensing).

**Round 2 - three pharmacy architecture forks**, all answered with the
recommended option:
- **Drug-interaction checking**: a self-maintained interaction-pairs
  table, not a licensed external drug database this app has no access to.
- **Pharmacy billing**: a new `Payment`/`Invoice` owner type tied to a
  dispense, reusing the existing appointment/lab-order owner pattern.
- **Controlled substances**: a real dual-sign-off workflow gate before a
  controlled-substance dispense completes, not just enhanced logging.

**Round 3 - the stock management module itself, asked once the pharmacy
set was already sketched**: is this the same thing as the
already-sketched pharmacy "supply chain" phase, or something broader?
Answered **broader** - one general stock/inventory system where
medications are just one category, not a pharmacy-only concept. Which
non-medication categories to cover: clinical/medical consumables, PPE &
safety supplies, office/admin supplies, **and** equipment/assets
-answered all four, with equipment explicitly flagged as structurally
different (not consumed, individually tracked, sometimes needs
maintenance history) rather than quantity-based stock like the other
three. A follow-up pinned how deep equipment maintenance tracking should
go: **a simple log, no automated reminders** - matches this app's
existing bias toward staff-initiated actions over a second scheduling
system alongside appointments.

**This reconciliation revises the original phase 29 sketch** ("Supply
chain management," pharmacy-scoped) - superseded by the general stock
module below rather than built as pharmacy-only, since building
near-identical batch/supplier/purchase-order machinery twice (once for
medications, once for everything else) would be pure duplication. The
whole phase list is renumbered accordingly (nothing here has been built
yet, so renumbering costs nothing - see this file's own "revise in
place" practice).

Ten new sequential phases total, building on phase 20's existing
`com.clinicops.pharmacy` package (`Medication`/`StockBatch`/
`DispenseRecord`) plus a new `com.clinicops.inventory` package for
everything general-stock. Migrations start at `V23` (highest existing is
`V22__immunizations_visit_link.sql`). None of this is built yet - full
field-by-field design (and any smaller remaining forks, flagged inline
below) happens when each phase is actually picked up, same as every
other "sketched ahead of need" plan in this file (see phases 16-18's own
precedent).

**27. Clinical safety checks** (pharmacy) - **built 2026-09-29**. See
"Phase 27: pharmacy clinical safety checks" below for the full write-up.

**28. Controlled substance tracking** (pharmacy) - **built 2026-09-29**.
See "Phase 28: controlled substance tracking" below for the full
write-up.

**29. Stock/inventory foundation** (new `com.clinicops.inventory`
package) - **built 2026-09-29**. See "Phase 29: stock/inventory
foundation" below for the full write-up.

**30. Equipment & asset tracking** (`com.clinicops.inventory`) - **built
2026-09-29**. See "Phase 30: equipment & asset tracking" below for the
full write-up.

**31. Pharmacy billing integration** - **built 2026-09-29**. See "Phase
31: pharmacy billing integration" below for the full write-up.

**32. Smarter dispensing workflow** - **built 2026-09-29**. See "Phase
32: smarter dispensing workflow" below for the full write-up.

**33. Patient-facing pharmacy features** - **built 2026-09-29**. See
"Phase 33: patient-facing pharmacy features" below for the full
write-up.

**34. Unified reporting & analytics** - **built 2026-09-29**. See "Phase
34: unified reporting & analytics" below for the full write-up.

Each phase gets the same treatment every other phase in this project
does once actually built: its own migration, its own
`TenantIsolationIntegrationTest` case(s), pure-unit tests for anything
calculation-heavy (the FEFO sort, the fuzzy-match scoring, inventory
valuation), Testcontainers integration tests for the rest, a live
-verification pass against the real running stack, and its own dated
CLAUDE.md write-up - this section will be replaced by those real
per-phase write-ups as each one lands, not left standing alongside them.

## In-house laboratory module - sketched 2026-10-02, not yet built

The user asked what it would take to make this a "fulfledged clinic
system" with its own pharmacy and lab. Pharmacy is already there (phases
20/27-34 above). Lab is not - phase 7's own module has stayed a thin,
generic order-tracking shape since 2026-09-13 (`requested -> ordered ->
specimen_collected -> in_transit -> resulted -> reviewed`, or `cancelled`)
with no dedicated lab-staff role at all (`provider`+`clinic_admin` own
every lab endpoint today, confirmed against `docs/api-reference.md`'s own
"Lab orders" section) and an essentially free-text result
(`lab_order_tests.result_value`/`result_unit`/`reference_range`/
`abnormal_flag` are flat columns, one per test line, not a per-analyte
breakdown). Phase 7's own write-up flagged this exact fork as
"not yet decided" and it was never revisited.

Four direct questions were put to the user before sketching anything,
matching this project's own "ask before building" convention - all
answered with the recommended option, plus both optional capability
areas taken:

- **Lab staff role**: a new `lab_technician` realm role owns specimen
  collection/processing/result entry; `provider` keeps the final
  clinical "reviewed" sign-off that already exists today - the exact
  same split pharmacy already uses between `pharmacist` (dispensing) and
  `provider` (prescribing).
- **Result structure**: real structured per-analyte results (value +
  unit + reference range + auto-computed flag), not the current flat
  one-result-per-test-line shape - each test's own catalog entry defines
  its expected analytes, the way a CBC's own panel (WBC/RBC/Hgb/etc.)
  actually works in a real lab.
- **Specimen granularity**: a specimen becomes its own entity under
  `LabOrder` (one order, 1+ specimens, each with its own
  collection/accessioning/status timeline) rather than the order itself
  being the single status machine - needed for a real multi-specimen
  panel (e.g. blood + urine on one order) and mirrors the
  `Prescription`-vs-`DispenseRecord` split pharmacy already uses for a
  similar "one clinical intent, multiple physical fulfillment events"
  shape.
- **Capability scope**: both optional areas taken alongside the two
  recommended ones - critical-value alerting, basic QC logging, external
  reference-lab send-outs, and equipment/analyzer integration readiness
  (no real instrument integration gets built - there's nothing to
  integrate with in this dev environment - just a result-entry shape
  that wouldn't need reworking if a real HL7/ASTM feed existed later).

Eight phases sketched, building on the existing `com.clinicops.laborder`
package rather than replacing it - `LabOrder`/`lab_test_rates` stay, the
new work is additive (a new `Specimen` entity, a new analyte/result
layer, a new role). Migrations start at `V30` (highest existing is
`V29__clinic_domain_column.sql`). Nothing here is built yet - full
field-by-field design happens when each phase is actually picked up,
same as every other "sketched ahead of need" plan in this file (see the
pharmacy-expansion sketch above, or phases 16-18's own precedent).

**L1. Lab technician role + specimen entity foundation** - built
2026-10-02, see "Phase L1: lab technician role + specimen entity
foundation" below for the full write-up.

**L2. Test catalog + structured per-analyte results** - built
2026-10-02, see "Phase L2: test catalog + structured per-analyte
results" below for the full write-up.

**L3. Critical-value alerting** - built 2026-10-02, see "Phase L3:
critical-value alerting" below for the full write-up.

**L4. Basic QC logging** - built 2026-10-02, see "Phase L4: basic QC
logging" below for the full write-up.

**L5. External reference-lab send-outs** - built 2026-10-02, see "Phase
L5: external reference-lab send-outs" below for the full write-up.

**L6. Equipment/analyzer integration readiness** - not its own phase so
much as a design constraint threaded through L1/L2: the result-entry
endpoint's own request shape should be agnostic to whether a human
(`lab_technician`, manually) or a future real instrument feed is the
one filling it in, so a later real HL7/ASTM integration could reuse it
without a redesign. No actual instrument integration gets built now.

**L7. Lab reagent/consumable inventory** - built 2026-10-02, see "Phase
L7: lab reagent/consumable inventory" below for the full write-up.

**L8. Frontend** - built 2026-10-02, see "Phase L8: frontend" below for
the full write-up.

Each phase, once built, gets the same treatment every other phase in
this project does: its own migration, `TenantIsolationIntegrationTest`
coverage, pure-unit tests for anything calculation-heavy (the
critical-flag computation, the specimen-status roll-up), Testcontainers
integration tests for the rest, a live-verification pass against the
real running stack, and its own dated CLAUDE.md write-up replacing this
sketch section - same discipline the pharmacy-expansion sketch above
already modeled.

## Phase L1: lab technician role + specimen entity foundation

Built 2026-10-02, same session as the sketch above. First phase of the
in-house laboratory module - a new `lab_technician` realm role plus a
new `Specimen` entity, purely additive alongside `LabOrder`'s own
existing status machine rather than replacing it, mirroring exactly how
`DispenseRecord` was added for pharmacy without touching `Prescription`.
New migration `V30__lab_specimens.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase L2: test catalog + structured per-analyte results

Built 2026-10-02, same session as L1. One direct question was put to the
user first - the fork L1's own sketch explicitly left open (should a
normal range vary by patient age/sex) - answered **one range per
test+analyte**, the recommended option, matching this app's own "keep
minimal in v1" bias (ICD-10 free text, `Prescription.route`'s allow-list).
New migration `V31__lab_analyte_results.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase L3: critical-value alerting

Built 2026-10-02, same session as L1/L2. Closes a gap L2 deliberately
left open - `AnalyteResultService.computeFlag` was only ever 2-way
(normal/abnormal); this phase makes it genuinely 3-way, adds a real
acknowledgment trail, and fires a real outbox notification to the
order's own ordering provider when a result lands in the danger zone.
No direct scoping question was needed - the design forks were already
implicit in the sketch itself (critical range as the outer bound beyond
normal, standard lab panic-value semantics) or resolved by matching an
existing convention verbatim (notification content, acknowledge-gate
role). New migration `V32__lab_critical_values.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase L4: basic QC logging

Built 2026-10-02, same session as L1-L3. One direct question was put to
the user first - the fork L4's own sketch explicitly flagged (should a
failing QC run actually block new result entry, or just flag it) -
answered **flag only, no enforcement**, the recommended option, matching
this app's existing bias toward trusting staff judgment over hard blocks
(no confirm dialogs anywhere in this app, the restricted-test
acknowledgment pattern lets a provider proceed past a flagged lab-order
test rather than refusing it outright). New migration
`V33__lab_qc_runs.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase L5: external reference-lab send-outs

Built 2026-10-02, same session as L1-L4. Unlike L4, the sketch's own
text for this phase named no open fork to pin, so this was built
directly from the sketch rather than preceded by a scoping question -
the only real design calls needed (where in the existing specimen
status machine the new branch sits, and how lenient the transition into
it should be) were made in the same spirit every other un-flagged
design decision in this file already documents inline rather than
re-asked. New migration `V34__lab_reference_lab_sendouts.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase L7: lab reagent/consumable inventory

Built 2026-10-02, same session as L1-L5. One direct question was put to
the user first - the fork the sketch itself flagged (extend the
already-built general inventory module vs. a second, parallel lab-only
stock system) - answered **extend the general inventory module**, the
recommended option, avoiding near-identical batch/lot/expiry machinery
built twice. No new migration - `category` on `InventoryItem` is a
plain, DB-unconstrained `VARCHAR(30)` (validated only in
`InventoryItemController`'s own code-level allow-list, confirmed by
re-reading `V25__inventory_foundation.sql` before writing anything -
no `CHECK` constraint exists on this column), so widening it to accept
one more value is a pure application-layer change.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase L8: frontend

Built 2026-10-02, same session as L1-L7, closing out the whole lab
module. One direct question was put to the user first - no browser
-automation tool was available this session (the same standing gap
L1-L7 already flagged), so unlike every other frontend phase in this
project's history, this one couldn't be clicked through and visually
confirmed, only build-verified - answered **build it now, build-only
verification**, the recommended option, with the missing click-through
explicitly flagged here rather than claimed.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 27: pharmacy clinical safety checks

First of the ten sequential phases sketched under "Pharmacy expansion +
stock management module" above (built 2026-09-29). Before
`DispenseService` decrements stock, it now cross-checks the medication
against the patient's active allergies and against other medications the
patient is currently on, via a new self-maintained `DrugInteractionPair`
table - neither check hard-blocks by default, matching the lab-order
restricted-test acknowledgment pattern (`RestrictedTestsProperties`): an
explicit `acknowledgeConflict` flag on the dispense request lets the
pharmacist proceed past a detected conflict, and - unlike that
precedent, which discards the flag after use - the override outcome is
**persisted** on the `DispenseRecord` itself. New migration
`V23__pharmacy_clinical_safety.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 28: controlled substance tracking

Second of the ten sequential phases sketched under "Pharmacy expansion +
stock management module" above (built 2026-09-29, same session as phase
27). `Medication` gains an optional controlled-substance schedule;
dispensing one no longer goes through the existing single-step
`DispenseService.dispense(...)` path at all - it goes through a
**new, separate, mutable** two-phase workflow instead (a pharmacist
requests it, a *different* pharmacist or clinic_admin co-signs before
the real stock decrement/`DispenseRecord` happens), exactly as pinned in
the original sketch. `DispenseRecord` itself stays genuinely
append-only - the mutable, in-progress state lives entirely in the new
workflow entity, never on the permanent record. New migration
`V24__pharmacy_controlled_substances.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 29: stock/inventory foundation

Third of the ten sequential phases sketched under "Pharmacy expansion +
stock management module" above (built 2026-09-29, same session as
27/28). This is the phase that actually delivers the "medications are
just one category of general stock" architecture the user asked for in
the original round-3 scoping question - a new `com.clinicops.inventory`
package (`InventoryItem`, generalized `StockBatch`, `Supplier`,
`PurchaseOrder`/`PurchaseOrderLine`, `StockAdjustment`, reorder alerts).
One direct scoping question was put to the user before designing this
phase - which roles manage the new *general* inventory, since the
sketch itself never pinned it - answered **`clinic_admin` + `front_desk`**,
the recommended option, matching this app's existing precedent of
extending `front_desk` into non-clinical-judgment operational work
(vitals, immunizations) rather than adding a new role for routine
logistics. Pharmacy/medication stock keeps its existing
`pharmacist`+`clinic_admin` gate, completely unchanged. New migration
`V25__inventory_foundation.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 30: equipment & asset tracking

Fourth of the ten sequential phases sketched under "Pharmacy expansion +
stock management module" above (built 2026-09-29, same session as
27-29). Adds `Asset` + `AssetMaintenanceRecord` to the existing
`com.clinicops.inventory` package - deliberately its own phase, not
folded into phase 29, since equipment is one row per physical item (not
quantity-based stock) and needs its own shape. Much smaller than phase
29 - no relocation/refactor, just two new entities on an already
-established package/role/convention set. No new scoping question
needed - the sketch already pinned the maintenance-log shape (a simple
append-only log, explicitly no `nextDueAt`/reminder field and no
notification-outbox wiring - a history to look back on, not a second
scheduling system alongside appointments), and general-inventory role
gating was already pinned in phase 29 (`clinic_admin`+`front_desk`) -
`Asset` is the same general operational-logistics concern, so it reuses
that gate directly rather than re-asking. New migration
`V26__equipment_asset_tracking.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 31: pharmacy billing integration

Fifth of the ten sequential phases sketched under "Pharmacy expansion +
stock management module" above (built 2026-09-29, same session as
27-30). Closes the last gap phase 20 left open ("dispensing stays
separate from Payment/Invoice this phase") - a pharmacy dispense can
now be paid for and invoiced through the exact same machinery
appointments and lab orders already use, with the ledger
(`JournalService`) picking it up automatically. New migration
`V27__pharmacy_billing_integration.sql`.

One direct scoping question was put to the user before designing this
session - the sketch's own explicitly-flagged open fork, whether a
dispense gets full generate-once Invoice/PDF parity or stays
payment-only - answered **full parity**, the recommended option, to
keep all three owner types symmetric and reuse already-built machinery.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 32: smarter dispensing workflow

Sixth of the ten sequential phases sketched under "Pharmacy expansion +
stock management module" above (built 2026-09-29, same session as
27-31). Adds *suggestions* on top of the existing manual-pick dispense
flow, deliberately not reversing phase 20's own pinned "no drug-name
-matching logic" / "no automatic FEFO allocation" decisions at the data
level - the pharmacist still makes every real choice, the system just
narrows it down. No new migration - this phase adds no new
tables/columns at all.

One direct scoping question was put to the user before designing this
session: unlike phases 27-31 (all backend-only, since none of them had
an existing UI to plug into), this suggestion is only useful if a
pharmacist can actually see it - there's a real, live pharmacist
Dashboard (`pages/pharmacist/Dashboard.jsx`'s `DispensePanel`, built
phase 20) this naturally plugs into. Answered **include the frontend
wiring**, the recommended option - the first pharmacy-expansion phase
this session to touch the frontend.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 33: patient-facing pharmacy features

Seventh of the ten sequential phases sketched under "Pharmacy expansion +
stock management module" above (built 2026-09-29, same session as
27-32). Gives patients their own read access to prescriptions plus a
lightweight refill-request workflow, reusing `LabOrder`'s own phase-7
patient-request -> staff-confirm shape and the phase-17 real-email
notification outbox rather than inventing new patterns for either. One
direct scoping question was put to the user before designing this
session - whether to include a new patient-facing frontend page this
phase, since none exists yet - answered **backend/API only**, the
recommended option, matching phases 27-31's own precedent (new UI
surface is a bigger, separate scope decision better suited to its own
dedicated frontend phase later). New migration
`V28__patient_prescription_refills.sql`.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 34: unified reporting & analytics

Last of the ten sequential phases sketched under "Pharmacy expansion +
stock management module" above (built 2026-09-29, same session as
27-33) - closes out that whole module set. Two new bundled analytics
endpoints mirror `ClinicAnalyticsController`'s exact shape (phase 19):
`GET /api/pharmacy/analytics?days=` and `GET /api/inventory/analytics?days=`.
Both new controllers live in `com.clinicops.analytics` (co-located with
phase 19, reaching into `com.clinicops.pharmacy`/`com.clinicops.inventory`
repositories directly - the same "plain read composition, no dedicated
service bean" convention `ClinicAnalyticsController`'s own javadoc
already documents). No new migration - purely new read queries against
existing tables/columns.

One direct scoping question was put to the user before designing this
session: the sketch's own text commits to frontend charts, but general
inventory (phases 29/30) has zero frontend at all (no `pages/inventory/`
directory, no nav entry), unlike pharmacy, which already has a live
page (`pharmacist/Dashboard.jsx`, phase 20) to extend. Answered
**pharmacy frontend only**, the recommended option - inventory
analytics still gets a real backend endpoint, but its frontend is
deferred until a broader inventory-management frontend phase exists to
host it in context, avoiding an orphan page. Matches phase 32's own
precedent (frontend only where a live page already exists to plug
into).

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Frontend gap-closing, sketched 2026-09-29

Asked directly whether every role and backend API has a frontend - the
honest answer was no. A grep of every `*Controller.java` against every
frontend route/hook found six real gaps: general inventory
(`com.clinicops.inventory` - phases 29/30/34) had **zero** frontend at
all, plus four smaller pharmacy-expansion features left deliberately
backend-only when built (drug-interaction pairs, controlled substances,
dispense billing, patient prescriptions/refills + the staff refill
review queue). The user asked to close all six, as sequential phases -
phase 35 (below) is the first, the biggest by far since it's a whole
unbuilt module; the remaining five are smaller, later phases.

**All six closed as of phase 39 (2026-09-30)** - see "Phase 39: patient
prescriptions/refills UI" below for the closing write-up; every role
and backend API in this project now has a real frontend.

## Phase 35: general inventory UI

First of the six frontend gap-closing phases above. A new
`pages/inventory/` section (Dashboard/Items/Suppliers/PurchaseOrders/
Assets) against the fully-built, fully-tested backend from phases
29/30/34 - zero backend changes planned going in (two were found live,
see below). `clinic_admin`+`front_desk` throughout - a co-equal
two-role gate, not a primary-role-plus-clinic_admin-override the way
`pharmacist`/`accountant` work, since the backend already grants both
roles full, symmetric access with no ownership distinction (phase 29's
own pinned decision).

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 36: drug-interaction pairs UI

Second of the six frontend gap-closing phases above - the smallest one.
Closes the last gap `com.clinicops.pharmacy.DrugInteractionPairController`
(phase 27) left open: a fully-built, fully-tested hard-delete CRUD
backend with zero frontend, even though `DispenseService`'s own
clinical-safety check has been reading from it since phase 27. Zero
backend changes.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 37: dispense billing UI

Fourth of the six frontend gap-closing phases above. Closes
`com.clinicops.pharmacy.DispensePaymentController`/`DispenseInvoiceController`
(phase 31) - a dispense can be paid for and invoiced through the exact
same `Payment`/`Invoice` machinery appointments and lab orders already
use, but had zero frontend until now, plus `GET /api/prescriptions/{id}
/dispense-records` (also phase 31, never wired to a UI). Zero backend
changes. Smallest and most reuse-heavy of the three remaining gaps -
picked next in the established smallest-first sequencing, since the
two existing shared components (`PaymentsPanel.jsx`/`InvoicePanel.jsx`,
frontend phase P) already do almost everything this needed.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 38: controlled substance tracking UI

Fifth of the six frontend gap-closing phases above. Closes
`com.clinicops.pharmacy.ControlledSubstanceDispenseController` (phase
28) - the dual-sign-off workflow for a controlled-substance dispense -
which had a fully-built, fully-tested backend but zero frontend, plus
`MedicationController.updateControlledSubstanceSchedule` (also phase
28, the prerequisite step that had no UI path at all - no medication
could be marked controlled without a direct `curl`). Zero backend
changes.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 39: patient prescriptions/refills UI

Sixth and last of the six frontend gap-closing phases above - closes
this whole set. Closes `com.clinicops.pharmacy.PatientPrescriptionController`
(phase 33), both sides: the patient-facing `GET /api/my-prescriptions`/
refill-request flow, and the staff review queue
(`GET/POST /api/pharmacy/refill-requests`).

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 40: insurance & claims billing

Built 2026-10-03. The user asked what it would take to make this a "full
clinic system"; the biggest real gap identified was that every existing
billing path (`Payment`/`Invoice`, phases 15/16/31) models cash/self-pay
only - `Payment.method` has allowed the literal string `"insurance"` since
phase 1, but nothing ever tracked a claim's own lifecycle behind that
label. Four direct scoping questions were put to the user before writing
any code (matching this project's own "ask before building" convention) -
real EDI/clearinghouse integration vs. a self-contained tracker, single
vs. primary+secondary policies per patient, who manages claims, and
whether to include eligibility verification this phase - all four answered
with the recommended option. New migration `V35__insurance_claims.sql`.

*Full design write-up: see `CLAUDE-history.md`.*

## Sidebar nav arrangement review (2026-10-01)

The user asked for an evaluation of the menu arrangement for every
role - a real review, not a new feature, prompted by the sidebar
having organically grown to 19 flat items for `clinic_admin` across
the six gap-closing phases above with no pass ever taken on the
*arrangement* itself (every one of those phases added a correct link
in a reasonable place, but none stepped back to look at the resulting
whole). Three real, concrete problems were found and fixed, all in
`layout/Sidebar.jsx`:

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 19: clinic-admin analytics dashboard

Built 2026-09-24 at the user's direct request ("modify the dashboard
to have analytical graphs and stat cards"), out of numeric order - phases
16-18 were still only sketched at the time. New `com.clinicops.analytics`
package - one bundled `GET /api/clinic/analytics?days=` endpoint
(`clinic_admin`-only), real backend aggregation rather than deriving
charts from data the frontend already had. The frontend chart work
followed the dataviz skill's palette-validation process closely, and it
surfaced two real findings: this app's own live theme tokens were the
*wrong* source for chart colors (untuned for a dark chart surface), and
the natural green-for-"completed" status color failed CVD-separation
validation, so the status-breakdown chart shipped as a genuine
status-outcome (in-progress/completed vs. lost) encoding instead of
arbitrary per-status color.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 16: real payment gateway + refund flow + invoice PDF

Built 2026-09-24 (sketched 2026-09-21). New `com.clinicops.paymentgateway`
package - `PaymentGatewayClient` (mock-only for now, a real vendor
deferred; the interface's async-capable `status` field held up even
though the mock is always synchronous). Every payment recorded via
`AppointmentPaymentController`/`LabOrderPaymentController` now routes
through it unconditionally, gaining a `gatewayTransactionId`/
`gatewayStatus`. Full and partial refunds (`RefundService`, an
append-only `refunds` audit table, cumulative-refunded-so-far computed
in code rather than a plain DB CHECK). `payments` gained `invoice_id`.
An on-demand invoice PDF (OpenPDF, chosen after confirming it actually
resolves via a real `mvn dependency:resolve`) renders straight from the
already-issued Invoice row - never persisted, since it's regenerable at
zero cost.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 17: real email delivery

Built 2026-09-24 (sketched 2026-09-21 as "email+SMS via SendGrid+
Twilio"; scope deliberately revised once picked up - no SendGrid/Twilio
account was available, so this phase delivers real SMTP email via a
local Mailpit catcher instead, and SMS was deferred entirely rather than
partially built). `SmtpEmailSender` replaces `LoggingEmailSender`
outright; each of the four existing notification call sites now writes a
real typed payload record and gets a real per-type rendered subject/body
- the `notifications.payload` column had stayed an empty `"{}"` since
phase 1 until this phase.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Phase 18: per-clinic timezone

Built 2026-09-24 (sketched 2026-09-21). A nullable `clinic_settings
.timezone` override (a real IANA zone id, validated via `ZoneId.of(...)`
catching `DateTimeException`). One shared
`ClinicSettingsService.resolveTimezone(tenantId)` seam is now the single
call site every day-boundary/slot-generation query goes through - the
five places that used to hardcode `ZoneOffset.UTC`
(`SlotGenerator`/`SlotGenerationService`/`AvailabilityController`/
`my-schedule`) were all updated, confirmed by grep both when this was
sketched and again at build time. Existing slots are deliberately left
untouched - only future generation is affected.

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

## Frontend

`node-bff/frontend/` - a React + Vite + Tailwind SPA with its own
`package.json`/lockfile/`npm install`/`npm run build`, **not** an npm
workspace of `node-bff`. Nested under `node-bff/` specifically so
`docker-compose.yml`'s existing `node-bff` build context covers it. The
Dockerfile is multi-stage: a `frontend-build` stage runs
`npm ci && npm run build`, its `dist/` is `COPY --from=`'d into the runtime
stage as `./public`; `src/index.js` serves `public/` and falls back to
`public/index.html` for any GET that isn't `/health`, `/auth/*`, or `/api/*`.

**Frontend phase A** (built 2026-09-13, same session as phase 7) is the first real frontend work after phase 1's placeholder shell - a
real navigational shell (`react-router-dom` routes, role-aware nav,
per-tenant branding via a new `BrandingProvider`) plus one real working
dashboard per role, ported from the reference project's own frontend and
adapted to this app's roles. Deliberate scope boundary: no dashboard
showed exact appointment time-of-day yet (`Appointment` carried no slot
start-time in its response shape at this point - closed by frontend
phase B's `AppointmentWithSlotView`). *(Full write-up moved to `CLAUDE-history.md`.)*

**Front-desk/provider dashboard widget gap closed** (built 2026-09-26) - the `startTime`-on-dashboards half of frontend phase A's own scope
boundary, closed for the front-desk "recent appointments" and provider
"today's schedule" widgets specifically. New `AppointmentWorklistView`
projection (same join-with-slots shape as `AppointmentWithSlotView`)
backs a new `GET /api/appointments/worklist`, plus `GET /api/my-schedule`
widened in place to the same view. *(Full write-up moved to `CLAUDE-history.md`.)*

**clinic_admin encounter-access fix** (built 2026-09-15) - closed the last "Frontend covers every module" gap: `clinic_admin` had
full backend access to `EncounterController` but no page reaching it.
Shared the existing front-desk/provider pages instead of building a
second one (route `roles` arrays widened, a "Document encounter" link
added, gated by role + appointment status). *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase B** (built 2026-09-13, same session) is the patient/guest booking flow - full lifecycle (create/view/cancel/
reschedule) plus public tracking. Two real backend gaps were found and
fixed along the way: a public `GET /api/clinics/{clinicId}/providers`
directory, and the `AppointmentWithSlotView` projection (closing phase
A's own `startTime` gap for the patient-facing endpoints). A real bug
was found live: `BookingForm.jsx`'s error banner was nested inside the
same conditional block its own 409-handler cleared, so a slot-conflict
message never actually displayed - caught only by deliberately racing a
real concurrent booking against the same slot via `curl`. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase C** (built 2026-09-13, later session) is front-desk's own walk-in UI - patient search/registration, book-for
-patient, the check-in "Next step" button sequence, a payments panel,
and reschedule/cancel actions. Zero backend changes needed - pure
frontend wiring against endpoints already live-verified in phases 2/3/7. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase D** (built 2026-09-14, later session) is clinic-admin's settings/CRUD UI - providers (+ nested working-hours +
login link/unlink panels), rooms, appointment-types, fee-policies, lab
-rates, and a settings/branding tab hub. Found and fixed a real backend
bug along the way: a `ResponseStatusException` with no dedicated
`@ExceptionHandler` fell through to Boot's default `/error` JSON body,
silently omitting the reason text
(`server.error.include-message: always` fixed it). *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase E** (built 2026-09-14, later session) is the provider clinical UI - encounter documentation (chief complaint/
assessment/plan) plus a full-replace prescription list. Client-side
mirrors `EncounterService`'s own documentable-status gate so the common
case never round-trips a 409. Flagged as a real gap at the time:
`clinic_admin` had no UI path to this yet - closed later by the
clinic_admin encounter-access fix above. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase F** (built 2026-09-14, later session) is the lab-orders UI - the largest single frontend phase at the time,
covering staff order creation/status-transitions/results/cancellation,
the patient request-\>confirm-and-order flow, and public two-factor
tracking. Ported from the reference project's own cargo/waybill
frontend, adapted to this app's own lab-order status machine. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase G** (built 2026-09-14, later session) is platform-admin's clinic-onboarding UI - the last item named in the
kickoff spec's original phase plan without a page. One deliberate
difference from the reference project's own operator-onboarding page:
this one offers a real Reactivate action too, since `PlatformController`
has a genuine reactivate endpoint (phase 6) the reference project never
had. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phases H/I** (built 2026-09-21) are the first UI for the revised EHR backend phases 8-10 (allergies/
vitals/medical-history/consent) - one shared `PatientChart.jsx`
component mounted on both `front-desk/AppointmentDetail.jsx` and
`provider/Encounter.jsx`, the same "share the existing page" pattern the
clinic_admin encounter-access fix already established. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase J** (2026-09-21) upgrades `provider/Encounter.jsx` for phases 11/12 - an ICD-10 field,
the new prescription fields, a "Sign encounter" button that locks every
input in place once signed (no separate read-only view), plus an
Addenda list/add-form appearing post-signing. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase K** (2026-09-21) extends `clinic-admin/Providers.jsx` for phase 13 - license/employment
fields plus a per-row Signature upload panel, this app's first
file-upload UI (a new `apiPostForm` helper sending raw `FormData` with
no forced JSON `Content-Type`). *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase L** (2026-09-21) is a new `pages/referrals/Referrals.jsx` for phase 14 - a create form
plus an inline expand-to-update list row, no separate detail route since
referrals have one partial-update endpoint, not a status machine like
lab orders. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase M** (2026-09-21) closes the last EHR-leaning "Known gaps" item - a shared
`InvoicePanel.jsx` component (same "share one component across both
owner-type pages" pattern) mounted on both the appointment and lab-order
detail pages, matching the generate-once/immutable backend design
exactly. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase N: UI language, timezone display, and theme preferences** - Theme (Light/Dark/System) and Language (full i18n sweep, English +
Amharic, all ~590 keys per locale) both built 2026-09-22; timezone
display (browser/clinic's-own/manually-picked IANA zone) followed later,
2026-09-24, once phase 18 unblocked the "clinic's own zone" option.
localStorage-only, no backend changes. Several real bugs were found and
fixed live along the way: native form controls (`<input>`/`<select>`)
staying light-themed regardless of the `.dark` class (fixed with a
single `color-scheme` CSS declaration, letting the browser's own UA
stylesheet reskin them); `lib/format.js`'s formatters silently staying
on the browser's own locale regardless of the chosen UI language (fixed
by building each `Intl` formatter fresh per call against `i18n.language`
instead of once at module load); and a timezone-preference change
triggering no re-render anywhere until an unrelated one happened (fixed
by piggybacking i18next's own `languageChanged` event rather than adding
a new subscription to every consuming file, after a first attempted fix
- subscribing inside `AppShell`/`PublicShell` - was tried live and
disproven, since `<Outlet/>` doesn't re-derive from its layout's own
render). *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase O: clinic-admin analytics dashboard** (built 2026-09-24,
out of order ahead of frontend phases tied to phases 16-18) - see "Phase
19: clinic-admin analytics dashboard" above for the full write-up
(backend endpoint, the dataviz-skill-driven chart-color validation work,
and all four chart components). In short: `pages/clinic-admin/Dashboard.jsx`
gained a day-range toggle (7/30/90 days), a "Revenue" stat card, and four
Recharts panels (`components/analytics/`) - appointment-volume and
revenue trend lines, a status-outcome breakdown bar, and a per-provider
utilization bar - all reading from the new `GET /api/clinic/analytics`
endpoint, all fully translated and theme-aware, and all live-verified in
both languages and both themes. No other role's dashboard was touched -
scoped to clinic-admin only per the user's own answer when this was
kicked off.

**Frontend phase P: phase-16 payment/invoice UI** (built 2026-09-24) -
closes the two "not built this phase" items phase 16's own write-up
flagged: an invoice PDF download link, and a refund action on the
Payments panel. Zero backend changes - both endpoints have covered this
exact shape since phase 16 itself. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase Q: modern UI redesign - sidebar nav + hamburger +
searchable/sortable tables** (built 2026-09-27) - a full visual/navigation
pass across every role, requested directly by the user ("modern...appealing
look, multi searching and sorting features, side menu instead of scrolling
long down, hamburger"). Confirmed via direct questions before building:
sidebar for every role (not just desktop), search+sort on every list page
(not just the busiest ones), and real sortable tables (not cards with a
toolbar bolted on). Zero backend/`node-bff` changes - every list-fetching
hook in `api/queries.js` already returns its full tenant/owner-scoped list
with no server-side sort/pagination to begin with, and every list in this
app is small (seed data ships single digits of most resources), so
search/sort run entirely client-side, same "no premature abstraction, no
backend change without a real need" convention this project already keeps. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase R: accountant/finance UI** (built 2026-09-28) - gives the
`accountant` role (and `clinic_admin` via override) a real frontend for
the accounting/finance backend built in phases 21 (chart of accounts,
journal, trial balance) and 22 (employees, payroll, budgets, P&L/budget
-vs-actual). Closes the last gap the pharmacy/accounting/finance module
set left open - `pharmacist` already had one since phase 20, `accountant`
had none until now. Ported the same conventions phase 20's pharmacist
pages already established (`DataTable` + `renderExpanded` for inline edit
panels, a create-form-above-a-searchable-table shape) rather than
inventing new ones. Zero backend changes - every endpoint this drives
already existed and was already live-verified server-side in phases 21/22. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase S: test infrastructure** (built 2026-09-28) - closes the
"Known gaps" item flagging that every frontend claim in this file was
`npm run build` plus a manual browser walkthrough, never a repeatable
automated check. Scoped with one direct question to the user first - how
much coverage this pass should add, given zero existing test
infrastructure - answered "infrastructure + shared components," the
recommended middle option over "infrastructure only" and "broad page-level
coverage." *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase T: UI for phases 24-26** (built 2026-09-29) - closes the
"backend only" gap the full-EHR-breadth backlog left open. Unlike most
frontend phases, this one needed almost no clarifying questions - nearly
every UI decision followed directly from an existing pattern (the plan
was written straight from reading the real files, not a round of
questions), so this write-up is mostly "which existing pattern got
reused where," not new design. *(Full write-up moved to `CLAUDE-history.md`.)*

**Frontend phase U: insurance & claims UI** (built 2026-10-03) - closes
phase 40's own "no frontend yet" gap. A new `InsuranceSection` in
`PatientChart.jsx` (the one section with no provider carve-out at all -
gated at the mount point, not just its own write form, since
`InsurancePolicyController` grants provider zero access) and a new
`ClaimsPanel.jsx`, mounted next to `InvoicePanel`/`PaymentsPanel` on both
`front-desk/AppointmentDetail.jsx` and `lab-orders/LabOrderDetail.jsx`
(deliberately not the dispense-billing panel - a pharmacy dispense isn't
a typical insurance scenario, revisit if needed). `StatusPill.jsx`
extended with the claim-status vocabulary. No backend changes. *(Full
write-up moved to `CLAUDE-history.md`.)*

**Frontend phase V: tabbed long-scroll pages** (built 2026-10-03) - the
user asked for long-scrolling pages split into tabs "just like the
settings page," scoped to four pages by direct question: `front-desk/
AppointmentDetail.jsx`, `provider/Encounter.jsx`, `lab-orders/
LabOrderDetail.jsx`, `accountant/Budgets.jsx`. New shared `Tabs.jsx`
(client-side, not route-based - every section's data stays fetched
across a switch) ports `SettingsLayout.jsx`'s own underline tab-bar
style. No backend changes. *(Full write-up moved to `CLAUDE-history.md`.)*

## Post-phase-7 backend additions

Built 2026-09-19/20, later extended the same session. Two "Known gaps"
items closed together first: `NotificationWorker` (a line-for-line port
of the reference project's own outbox dispatcher - the `notifications`
table no longer just accumulates unread rows forever) and initial
`clinic_admin` login provisioning on clinic onboarding (`CreateClinicRequest`
gained optional `adminEmail`/`adminFullName` - a real Keycloak user +
role + org-membership + temporary-password sequence; no real email
delivery yet at this point, so the password returns once in the create
response). Folded into the same write-up: the PHI access audit log
(`com.clinicops.phiaudit`, staff-initiated access only, explicit call
sites rather than AOP) and payment auto-charge (a `fee_auto_charged`
`Payment` row now created alongside a cancellation/reschedule fee,
attributed to the patient's own account for a self-service cancel).

*Full design write-up and live-verification detail: see `CLAUDE-history.md`.*

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
- Per-phase test-file coverage for phases 1 through 17 (`ClinicControllerIntegrationTest`
  through `SmtpEmailSenderTest`/`NotificationPayloadWriterTest`) - moved to
  `CLAUDE-history.md` verbatim as part of the 2026-09-28 size-reduction
  pass described under "Verified-session logs" below; nothing summarized
  or dropped, just relocated. See there for the full phase-by-phase list.
- CI (`.github/workflows/ci.yml`): three parallel jobs - `mvn verify`
  (spring-boot-api), `npm test` (node-bff), `npm run build` (frontend).
- Per-phase test-file coverage for phases 20 through 40 (`DispenseServiceTest`
  through `ClaimServiceTest`/`ClaimControllerIntegrationTest`, the pharmacy/
  accounting/finance module set, the full-EHR-breadth backlog, the in-house
  lab module L1-L8, and insurance & claims billing) - moved to
  `CLAUDE-history.md` verbatim as part of a 2026-10-03 size-reduction pass,
  same precedent as the phases-1-17 bullet above; nothing summarized or
  dropped, just relocated. See there for the full phase-by-phase list.

## Verified-session logs

The detailed "Verified this session - <phase>" write-ups (what was actually
clicked/curled/queried live against the running stack to confirm each
phase's own claims above) have been moved to `CLAUDE-history.md`, purely to
keep this file under its 150K-char size limit - nothing summarized or
dropped, a verbatim split. Check there for the phase-by-phase evidence log;
append new ones there too, not here.
## Known gaps (don't pretend these are done)

- ~~No initial `clinic_admin` user provisioning as part of clinic
  onboarding~~ **closed 2026-09-19** - see "Post-phase-7 backend
  additions". `POST /api/platform/clinics` now optionally creates a real
  Keycloak `clinic_admin` login too (`adminEmail`/`adminFullName` on the
  request); still no real email delivery, so the generated temporary
  password is returned once in the response, not emailed - a
  platform_admin still hands it over out of band.
- **Frontend covers every module from the kickoff spec's original 7-phase
  plan** (phases A-G), and every EHR-leaning module from the revised plan
  too. **Frontend phases H-L** (2026-09-21) covered EHR 8-14 (allergies/
  vitals/history/consent/coding-depth/sign-and-lock/provider license+
  signature/referrals); **frontend phase M** (2026-09-21) closed the last
  remaining gap - billing (phase 15) now has a real invoice UI, see
  "Frontend phase M" above. **Frontend phase R** (2026-09-28) closed the
  last module-set gap - `accountant` (phases 21/22) now has a real
  frontend matching `pharmacist`'s own phase-20 precedent, see "Frontend
  phase R: accountant/finance UI" above. **Frontend phase T** (2026-09-29)
  closed the full-EHR-breadth backlog's own frontend gap - immunizations
  and physical-exam findings both have real UI now (in `PatientChart.jsx`
  and `provider/Encounter.jsx` respectively), and the visit-summary PDF
  has a real download link on all three host pages, including this app's
  first ever patient-facing document download. See "Frontend phase T: UI
  for phases 24-26" above. **The "Frontend gap-closing" set (phases 35-39,
  2026-09-29/30) closed the last six gaps** - general inventory,
  drug-interaction pairs, dispense billing, controlled substance
  tracking, and patient prescriptions/refills all now have real UI.
  Every role and every backend API in this project has a real frontend
  as of phase 39 - see "Frontend gap-closing, sketched 2026-09-29" above.
- ~~`clinic_admin` has no UI path to encounter documentation~~ **closed
  2026-09-15** - see "clinic_admin encounter-access fix" above.
  `front-desk/AppointmentDetail.jsx` is now shared with `clinic_admin` (a
  new "Document encounter" link on it, gated by role + status), and
  `/provider/appointments/{id}/encounter`'s `RequireRole` widened to allow
  both roles.
- ~~`demo-front-desk` isn't yet in `infra/keycloak/realm-export.json`~~
  **closed 2026-09-28** - all seven demo users
  (`demo-clinic-admin`/`demo-patient`/`demo-provider`/`demo-platform-admin`/
  `demo-front-desk`/`demo-pharmacist`/`demo-accountant`) are now declared
  there with their real realm roles, and all use the actually-documented
  working credential (`DemoPass123!`, non-temporary) instead of the stale
  `changeme`/temporary value the file had carried since phase 1 (already
  out of date with reality per the `demo-keycloak-credentials` memory -
  every one of these had already been reset by hand in some earlier
  session). `create-demo-clinic.sh` (the org-membership half realm
  -export's plain `users` array can't cover) now loops over every
  clinic-scoped demo user (`clinic_admin`/`provider`/`front_desk`/
  `pharmacist`/`accountant` - not `patient`/`platform_admin`, neither tied
  to a clinic) instead of only `demo-clinic-admin`, and is now idempotent
  itself - a re-run looks up the existing `demo-clinic` org by alias
  instead of failing when the org already exists. **Verified live against
  the real running dev Keycloak** (not a genuine from-scratch import - that
  would mean wiping this environment's own volumes, not attempted here):
  the idempotent org-lookup fallback correctly found the existing org, and
  the membership loop correctly resolved all 5 usernames including the 3
  new ones - all 5 came back "already a member," which incidentally also
  corrects this gap's own stale claim that `demo-provider` had the same
  missing-membership problem as `demo-front-desk`; it didn't, by the time
  this was actually checked. A genuine from-scratch realm-import test (wipe
  the Keycloak volume, `docker compose up`, run this script once) is still
  owed, not claimed here.
- ~~No automated frontend test suite~~ **partially closed 2026-09-28** -
  see "Frontend phase S: test infrastructure" below. Vitest + React Testing
  Library are now real, with 34 passing tests covering the shared
  primitives every page depends on (`DataTable`, `StatusPill`,
  `RequireRole`, `lib/tableUtils.js`, two `api/queries.js` hooks) and wired
  into CI. Deliberately **not** closed further this pass - the user was
  asked directly and chose "infrastructure + shared components" over
  broader page-level coverage, so most of every individual page's own
  frontend claims in this file are still `npm run build` + a real manual
  browser walkthrough, not a repeatable automated check. `node-bff`'s own
  server-side `npm test` (20 tests) remains unaffected/unrelated - it
  never touches `node-bff/frontend/`.
- ~~No PHI-access audit log in v1~~ **closed 2026-09-20** - see
  "Post-phase-7 backend additions". Scoped to staff-initiated access only
  (not a patient viewing their own record) across
  Patient/Encounter/Prescription/LabOrder; not the same DB transaction as
  the domain read/write it accounts for, and list/search endpoints log one
  row per call rather than one per result row - both deliberate, documented
  boundaries, not oversights.
- ~~Payments are recordable but still purely a manual staff-entered record
  ... no real payment processor/gateway integration, no refund flow~~
  **closed 2026-09-24** - see "Phase 16: real payment gateway + refund
  flow + invoice PDF". Every payment recorded via `AppointmentPaymentController`/
  `LabOrderPaymentController` now routes through `PaymentGatewayClient`
  (mock-only for now - still no real vendor, credentials, or third-party
  account), gaining a `gatewayTransactionId`/`gatewayStatus`; full and
  partial refunds are real (`RefundService`, `POST /api/payments/{id}/refund`).
  The `fee_auto_charged` shortcut still bypasses the gateway entirely,
  unchanged from before this phase - that row still only means "this fee
  was assessed," not "collected via the gateway," by design.
- ~~`invoices` still exists in `V1__init.sql`, unused by any code~~
  **closed 2026-09-21** - see "Phase 15: real billing build-out".
  ~~Still no invoice PDF/printable view and no link from a payment back to
  the invoice it's paying down~~ **closed 2026-09-24** - see "Phase 16"
  above: `GET /api/appointments/{id}/invoice/pdf`/`GET /api/lab-orders/{id}/invoice/pdf`
  render a real PDF (OpenPDF) on demand, and `payments.invoice_id` links a
  genuine staff-entered payment back to the invoice it's paying down.
- ~~Email/notifications: the `notifications` outbox table is written to but
  there's no `NotificationWorker`/real sender yet~~ **closed 2026-09-24** -
  see "Phase 17: real email delivery". `SmtpEmailSender` now delivers real
  email (with real per-type templates, not an empty payload) to a local
  Mailpit catcher - genuinely real SMTP delivery, just not to a real
  vendor/external inbox (no SendGrid/Twilio account available). **SMS is
  still entirely unbuilt, by deliberate scope decision** - `Notification.channel`
  never gets set to anything but `"email"`; revisit if/when a real SMS
  need (and a Twilio account) exists.
- ~~No per-clinic timezone~~ **closed 2026-09-24** - see "Phase 18:
  per-clinic timezone". `clinic_settings.timezone` is a real, validated
  override now, resolved through one shared
  `ClinicSettingsService.resolveTimezone` seam by every day-boundary/slot
  -generation call site. The *frontend* display picker (browser-local/
  clinic's/manual) closed the same day - see "Frontend phase N"'s
  timezone-display write-up.
- ~~Recurring-series cancellation isn't its own concept~~ **closed
  2026-09-28** - see "Phase 23: recurring-series cancellation" below.
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
- **A second, related lesson (frontend phase Q)**: after `--force-recreate
  node-bff`, nginx can keep 502ing every request even once the new
  container is healthy and answers `200` directly on `:3000` - it resolved
  `node-bff`'s container IP once and doesn't re-resolve on a plain
  recreate. `docker compose restart nginx` right after fixes it. Verify
  through `http://localhost:80` (nginx), not just `:3000` directly, after
  any `node-bff` recreate - the same "verify through the real browser
  -facing route" trap as the bullet above.
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
- **This machine also has a native Windows `postgresql-x64-17` service that
  auto-starts and permanently holds `:5432`** (found phase 7) - unlike the
  bus-ticketing-saas port conflict above, this isn't one of this project's
  own two run modes, so `docker-compose.yml`'s postgres service now maps to
  host port **`5433`** instead (`"5433:5432"`) rather than fighting the
  service on every session. Every container-to-container connection
  (spring-boot-api, keycloak) still uses `postgres:5432` internally,
  unaffected - only a host tool (psql, a GUI client) connecting from
  outside docker needs `localhost:5433` on this machine. A native Keycloak
  process on `:8080` can also turn up from a `start-native.ps1` run left
  running in a previous session - stop it directly (`Stop-Process`), same
  as any other stray dev process.
- ~~No git remote is configured for this repo yet~~ **closed 2026-09-27** -
  `origin` now points at `git@github.com:mengistu-redae/clinic-management-saas.git`.
  A fresh SSH key was generated on this dev machine (none existed before)
  and registered on the GitHub account; all local history was pushed. The
  branch was renamed `master` -> `main` (to match `ci.yml`'s own
  `on: push/pull_request: branches: [main]` trigger) and set as the
  repo's default branch on GitHub, with the redundant `master` ref
  deleted afterward - confirmed via `git ls-remote --symref origin HEAD`
  resolving to `refs/heads/main` and `git ls-remote --heads origin`
  showing `main` as the only branch.
  - **`gh` is still not authenticated in this environment** - `gh auth
    login`'s device-code flow failed twice with `context deadline
    exceeded`, once relayed through the tool execution context and once
    run directly in the user's own terminal (via the `!` prefix) - the
    device code appears to expire before the browser-approval step
    completes either way. The two GitHub settings changes above (default
    branch, deleting `master`) were done manually via the web UI instead
    of `gh`/the API.
  - **CI ran for the first time ever on this repo (2026-09-27) - mixed
    result, real failure not yet diagnosed.** Confirmed via GitHub's
    unauthenticated public REST API (`/actions/runs`), not `gh` (still
    blocked). `node-bff (npm test)` and `frontend (vite build)` both
    passed; **`spring-boot-api (mvn verify)` failed** - the first real
    signal on whether this suite (`AbstractIntegrationTest`'s
    Testcontainers Postgres+Redis) actually passes anywhere, since it's
    been Windows-npipe-blocked on every dev machine this whole project.
    A same-session attempt to reproduce locally hit the pre-existing
    Windows Testcontainers issue instead (`Could not find a valid Docker
    environment`) - so the cause stayed open at the time.
  - **Diagnosed and fixed 2026-09-28, once the user pasted the actual
    ubuntu-latest job log directly** (the `gh`/API-token block above never
    got resolved - the log arrived by another route). Confirms
    `AbstractIntegrationTest`'s Testcontainers suite genuinely runs and
    mostly passes on `ubuntu-latest` (`Tests run: 312, Failures: 5` -
    307 green) - the Windows npipe issue really is Windows-only, not
    evidence the suite itself is broken. Five real, pre-existing bugs
    surfaced, none related to Windows/Testcontainers at all:
    - **`AccountControllerIntegrationTest`'s two starter-account-count
      assertions were stale** - `AccountSeedingService.STARTER_ACCOUNTS`
      grew from 3 to 4 entries in phase 22 (Salary Expense added), but
      this phase-21 test was never updated to match. Fixed by bumping
      both `$.length()` assertions from 3 to 4 and asserting the new
      Salary Expense row too.
    - **A real, latent bug in `CancellationIntegrationTest`'s own fee
      -policy fixtures** (two cases: `staffCancelFreesTheSlotAnd
      RecordsTheConfiguredFee`, `aSelfCancelFeeIsAutoChargedAndAttributed
      ToThePatientsOwnAccount`) - both configured their "under 2h notice"
      tier at `cutoffHours=2` instead of `0`. `FeeCalculator.calculate`
      always applies the *highest* `cutoffHours` the actual notice period
      still clears (`noticeHours >= tier.cutoffHours`) - confirmed against
      its own passing `FeeCalculatorTest` unit cases and against
      `LabOrderIntegrationTest`'s already-correct `createFeePolicy(...,
      0, 100)` usage - so a tier needs `cutoffHours=0` to act as the
      catch-all for "any notice, however short." With only a `cutoffHours
      =2` tier and ~1 hour of actual notice, no tier ever cleared, the fee
      silently computed as `$0.00`, and `CancellationService.
      applyCancellation`'s own `if (feeAmount.signum() > 0)` guard then
      never created the `fee_auto_charged` Payment the tests expected -
      not a Payment/JournalService bug at all, just an inverted test
      fixture. Fixed both fixtures to `cutoffHours=0`.
    - **A real precision bug in `EncounterService.sign`** -
      `reSigningAnAlreadySignedEncounterIsIdempotent` compares the
      `signedAt` from the first sign call against the second (idempotent
      re-call) response and found them different:
      `2026-09-28T14:32:43.980799027Z` (9 fractional digits, straight
      from the in-memory `Instant.now()` the first response serializes
      before it round-trips through Postgres) vs. `...980799Z` (6 digits
      - Postgres' own `timestamptz` storage precision, what the second
      call reads back). Same moment, genuinely inconsistent
      serialization depending on whether the caller is looking at a
      freshly-written value or a re-fetched one. Fixed at the source -
      `encounter.setSignedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))`
      - so the very first response already matches what persists, rather
      than tolerating the mismatch in the test.
    - All three fixes confirmed via a clean `mvn clean test-compile` and
      a full local run of every pure-unit (non-Testcontainers) test class
      - `69/69` passing, unaffected. The Testcontainers-only assertions
      themselves couldn't be re-run locally (still Windows-npipe-blocked)
      - confirmed correct by re-reading the exact CI failure output
      against the fixed code, not by re-running the suite.
  - **Confirmed green on the real `ubuntu-latest` runner once pushed
    (2026-09-28, commit `f5b7954`)** - the first fully passing CI run
    this repo has ever had: `node-bff (npm test)`, `spring-boot-api (mvn
    verify)`, and `frontend (vite build)` all `success`
    (`github.com/mengistu-redae/clinic-management-saas/actions/runs/36446430246`).
    Closes this gap for real, not just "fixed and assumed" - the fixes
    above were verified against the actual CI environment, the one place
    this suite has ever been provably runnable on this project.
