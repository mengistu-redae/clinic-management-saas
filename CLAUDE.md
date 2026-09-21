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

Lower priority / only if the product genuinely wants full-EHR breadth:
immunizations, structured physical-exam findings, discharge summaries -
these fit an inpatient/full-EHR shape more than this app's outpatient-
scheduling core, not pulled forward by default.

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

## Phase 7: lab orders module

Drafted from, and read against in full before writing any code, the
reference bus-ticketing-saas project's `com.bustix.cargo` module (waybills)
- the direct structural template: order+line-items split, manual
staff-driven status machine, snapshot pricing with a missing-rate 400
(the deliberate opposite of `fee_policies`' "missing = zero" fallback), a
`@ConfigurationProperties`-bound restricted-list, generic `FeeCalculator`
reuse for cancellation, two-factor public tracking, and a request->confirm
two-phase customer flow.

Four decisions pinned in plan mode (the kickoff spec's own explicit
"scope this via multiple-choice questions" items, plus two more forced by
gaps the investigation surfaced), all confirmed live this session:

- **Manual staff result entry only** - no HL7/FHIR ingestion. `result`
  takes a typed-in per-test value/unit/reference-range/abnormal-flag
  payload.
- **Resulted-but-unreviewed values are visible to any `provider`/
  `clinic_admin`** who reads the order - `review` is a workflow/audit stamp
  (`reviewedAt`/`reviewedBy`), not an access gate. Matches the existing
  no-staff-side-hiding-by-workflow-stage convention (encounters are visible
  immediately once written).
- **Cancellation fee always resolves at the clinic's zero-notice tier** -
  `FeeCalculator.calculate(tenantId, providerId, totalCost, dueAt)` reused
  completely unchanged, with `dueAt = Instant.now()` at cancel time (a lab
  order has no future "due" instant the way an appointment has a slot
  start time) - lands on whichever `fee_policies` row has `cutoffHours = 0`
  (zero fee if none configured, matching `FeeCalculator`'s existing
  fallback).
- **A shared `com.clinicops.payment` package built this phase**, not just
  for lab orders - closes "no invoicing/payment wired to booking," a
  standing known gap since phase 1. One `Payment` entity, two thin
  controllers (`AppointmentPaymentController`, `LabOrderPaymentController`),
  no delete on either.

**Also decided, not asked, because the kickoff spec already answers it**:
the `collect-specimen` identity check treats **no ID on file as a mismatch**
(`IdentityMismatchException`, 409) - the literal opposite of phase 3's
check-in convention (which lets "nothing on file" through). Kept as two
separate exception classes on purpose (`com.clinicops.laborder.
IdentityMismatchException` is not a reuse of `com.clinicops.appointment.
IdentityMismatchException`) so a future change to one convention never
silently changes the other.

- **`V5__lab_orders.sql`**: `lab_test_rates` (`UNIQUE(tenant_id,
  test_code)`), `lab_orders` (patient/encounter/ordering-provider/
  customer-user ids, `status`, `order_ref`/`clinic_ref`, snapshotted
  `total_cost`, per-transition timestamps), `lab_order_tests` (line items,
  own table - `test_code` nullable until a patient-initiated request is
  confirmed-and-ordered), `lab_order_cancellations` (mirrors
  `appointment_cancellations` exactly - no `created_at` column, so the
  entity doesn't extend `BaseTenantEntity`, same fix as phase 3's
  `AppointmentCancellation`). `payments.appointment_id` dropped `NOT NULL`,
  gained `lab_order_id` + `chk_payments_exactly_one_owner` (`(appointment_id
  IS NOT NULL) <> (lab_order_id IS NOT NULL)`).
- **`com.clinicops.labrate`**: same CRUD shape as phase 5's
  `FeePolicyController` (`/api/clinic/lab-rates`, `clinic_admin` only, real
  `POST /{id}/delete`). `NoLabRateConfiguredException` (400) - a lab order
  can't be created or priced without a configured rate for every requested
  `testCode`. No fixed "test catalog" table; a test becomes orderable the
  moment a rate exists for its code (same "config implies availability"
  shape `fee_policies` already uses).
- **`com.clinicops.laborder`**: `LabOrder`/`LabOrderTest` (line items, own
  table, no JPA relation - plain UUID FK + explicit repository queries,
  same convention as everywhere else); every endpoint returns
  `LabOrderWithTests { order, tests }` (same wrapper-record shape as
  `AppointmentSeriesResult`/`EncounterWithPrescriptions`).
  - `LabOrderService` - the consolidated core (create/get/listForTenant/
    update/createRequest/confirmAndOrder/myLabOrders/trackByRefAndPhone).
    Deliberately not split into a separate request-flow service since both
    share essentially all pricing/validation/encounter-match logic.
    Pricing is **snapshotted** at order-creation (or confirm-and-order)
    time onto each `LabOrderTest.price` and summed onto `LabOrder.
    totalCost` - a later `lab_test_rates` change never re-prices an
    already-issued order.
  - `LabOrderStatusService` - mirrors `CheckInService`'s idempotent
    -transition convention (`InvalidLabOrderStatusException` on an
    out-of-order call, no-op on a repeat of the current state) but with
    each of `collectSpecimen`/`send`/`result`/`review` as its own explicit
    method rather than one generic transition helper, since each carries
    unique extra validation/payload/side-effects.
  - `LabOrderCancellationService` - mirrors `CancellationService` exactly;
    pre-collection only.
  - `RestrictedTestsProperties` (`@ConfigurationProperties(prefix =
    "clinic.lab")`, `List<String> restrictedTests`) - the first
    `@ConfigurationProperties` class in this app (a YAML sequence has no
    single bare-key property the way `@Value` expects - Boot stores it
    indexed), so `ClinicManagementApplication` gained
    `@ConfigurationPropertiesScan` - same "silently inert without the
    enabling annotation" gotcha as `@EnableMethodSecurity`/
    `@EnableScheduling` before it. Each entry compiles as a case
    -insensitive regex, falling back to a literal-substring match if it
    isn't valid regex.
  - **Patient-initiated requests** (`PatientLabRequestController`) -
    `POST/GET /api/my-lab-orders` (`patient`), `GET /api/lab-orders/
    requests` (staff review queue, `findAllByTenantIdAndStatus(tenantId,
    "requested")`), `POST /api/lab-orders/{id}/confirm-and-order` (staff -
    assigns `orderingProviderId`/optionally `encounterId`/tests,
    `requested -> ordered`, `RequestNotIssuableException` 409 once past
    `requested`). `GET /api/my-lab-orders` unions two ownership paths: a
    directly-requested order (`customerUserId`) and an order on an
    encounter behind one of the patient's own appointments (join through
    `appointments.customer_user_id -> encounters.appointment_id`) - needed
    a new non-tenant-scoped `EncounterRepository.findByAppointmentId`,
    mirroring `AppointmentRepository.findByAppointmentRef`'s precedent.
  - **Public tracking**: `GET /api/lab-orders/track/{orderRef}?phone=`
    (`permitAll`) - two-factor match against `patients.phone`, mismatch and
    unknown ref both 404 alike. `LabOrderTrackingView` is deliberately
    narrow - status and timestamps only, never result values/reference
    ranges/abnormal flags/ID numbers/provider names.
  - **Roles**: create/update/status-transitions/cancel = `provider` +
    `clinic_admin`; lab-rate CRUD = `clinic_admin` only; patient request =
    `patient` only. No `front_desk` access anywhere in this package -
    clinical content, same reasoning phase 4 already applied to encounters.
- **`com.clinicops.payment`**: `Payment` (extends `BaseTenantEntity`,
  `appointmentId`/`labOrderId` both nullable, exactly one set per the DB
  CHECK). `AppointmentPaymentController`/`LabOrderPaymentController` - both
  `front_desk`/`clinic_admin`/`provider` read, `front_desk`/`clinic_admin`
  record; `POST` (create) + `GET` (list) only, no delete - a payment is a
  financial record, not config.

## Phase 8: allergies + vitals

First phase of the revised (EHR-leaning) phase plan sketched with the user
2026-09-20 - no reference-project precedent at all (bus-ticketing-saas has
no clinical-safety analog), a fresh design built directly against this
app's own conventions. Three design questions were put to the user before
writing any code (vitals write access, vitals status gate, allergy
deletion) - see "Domain decisions pinned so far" for the answers and
reasoning; a fourth call (allergies' access gate) was made without asking,
flagged as revisitable. New migration `V7__allergies_and_vitals.sql`.

- **`com.clinicops.allergy`** (new package) - `Allergy` (extends
  `BaseTenantEntity`, `patientId` FK, `allergen`/`reactionType`/`severity`
  /`status`/`identifiedAt`/`recordedBy`, own `updatedAt` same convention as
  `Encounter`). `AllergyController` - `GET`/`POST /api/patients/{patientId}/allergies`
  (`front_desk`+`clinic_admin` write, `+provider` read, mirroring
  `PatientController`'s own gate exactly) and `POST .../{id}/update`
  (partial - `reactionType`/`severity`/`status` only, `allergen`/`patientId`
  fixed at creation; no delete endpoint at all - see the pinned decision).
  `severity`/`status` are allow-listed the same way `RoomController`'s own
  `status` field is.
- **`com.clinicops.vitals`** (new package) - `Vitals` (extends
  `BaseTenantEntity`, `appointmentId` unique - exactly one vitals row per
  visit, enforced at the DB level, same shape as `encounters.appointment_id`
  - height/weight/temperature/pulse/respiratory-rate/BP/O2-sat/pain-score,
  `recordedBy` referencing `app_users` directly rather than `providers`
  since front_desk can record these too). `getBmi()` is a derived,
  non-persisted getter (computed from height/weight on every read, so it
  can never drift from the source measurements) - covered by its own pure
  -unit `VitalsTest` (4 cases: normal computation, missing height, missing
  weight, height of zero returns null rather than dividing by zero).
  `VitalsService.upsert` mirrors `EncounterService.upsert`'s find-or-create
  shape exactly, but with no ownership check (any of provider/clinic_admin/
  front_desk can record vitals regardless of which provider the
  appointment is assigned to) and a much looser status gate (rejects only
  `cancelled`, where Encounter requires `with_provider`/`checked_out`).
  `VitalsController` - `GET`/`POST /api/appointments/{id}/vitals`,
  `hasAnyRole('PROVIDER','CLINIC_ADMIN','FRONT_DESK')` on both.
- **PHI audit wiring** - both new controllers call `PhiAccessAuditService`
  exactly like `PatientController`/`EncounterController` already do
  (`resourceType` `"allergy"`/`"allergy_list"`/`"vitals"`) - confirmed live
  this session that a fresh write from each shows up correctly scoped by
  patient in the audit trail, not just added to satisfy the pattern by
  rote.
- **`TenantIsolationIntegrationTest`** gained one case per new resource
  (`allergiesAreNotReadableOrWritableFromAnotherTenant`,
  `vitalsAreNotReadableOrWritableFromAnotherTenant`), matching that file's
  own "one canonical cross-tenant check per staff-scoped resource"
  convention from the moment these resources were added, not bolted on
  later. `AllergyControllerIntegrationTest`/`VitalsControllerIntegrationTest`
  cover CRUD, validation, role gates, and the cross-tenant/cross-patient
  edges each own controller adds beyond that canonical check. All new
  tests compile clean and were confirmed to fail only via the pre-existing
  Testcontainers/Windows-npipe wall (`Failures: 0` in every surefire
  report, same as every other `AbstractIntegrationTest` subclass on this
  machine) - not a regression.
- **Live-verified against the real running stack** - see CLAUDE-history.md's
  "Verified this session - phase 8: allergies + vitals" for the full
  write-up (rebuilt the container, confirmed V7 applied, real
  `demo-front-desk` browser login exercising both the no-status-gate
  decision and BMI computation, both writes confirmed in `phi_access_log`).
- **No frontend yet** - backend only, same "no dedicated UI this phase"
  scope boundary the PHI audit log used. Reachable via `fetch(...,
  {credentials:'include'})` from the browser console today; a real
  intake/vitals-entry UI is a natural next frontend phase, not scoped here.

## Phase 9: medical history

Second phase of the revised (EHR-leaning) phase plan. Two design questions
were put to the user before writing code (storage shape, write-access
gate) - see "Domain decisions pinned so far" for the answers and
reasoning; both went with the recommended option. New migration
`V8__medical_history.sql`.

- **`com.clinicops.medicalhistory`** (new package) - `MedicalHistory`
  (`patientId` as the primary key itself, no separate `id` - same
  singleton shape as `ClinicSettings`; `pastConditions`/`pastSurgeries`/
  `currentMedications`/`familyHistory`/`socialHistory`, all free text;
  `recordedBy`/`updatedAt`). Deliberately does **not** duplicate
  `known_allergies` from the reference clinical-forms doc's own "Medical
  History Form" - `Allergy` (phase 8) is already the real, structured
  record for that; also does not duplicate `chief_complaint`, which stays
  on `Encounter` as a per-visit field, not a standing one.
- **`MedicalHistoryService`** - `upsert`/`get`, mirroring
  `EncounterService`/`VitalsService`'s own find-or-create shape exactly,
  with `PatientRepository.findByIdAndTenantId` as the ownership check (no
  separate "provider owns this" concept the way Encounter has one - any of
  the three gated roles can write regardless of who else has touched it).
  `upsert` is a genuine **full-replace** on every call (all five fields
  overwritten, `null` clears a field that isn't resent) - same "no
  partial-update semantics for a singleton clinical record" shape
  `UpsertVitalsRequest` already established, confirmed live this session
  (see below), not just documented.
- **`MedicalHistoryController`** - `GET`/`POST /api/patients/{patientId}/medical-history`,
  `hasAnyRole('CLINIC_ADMIN','FRONT_DESK','PROVIDER')` on both - the same
  three-role gate as `VitalsController`, for the same reasoning (intake
  -collected, not a clinical-judgment call the way an encounter is).
  PHI-audited exactly like Patient/Encounter/Vitals/Allergy
  (`resourceType = "medical_history"`, `resourceId`/`patientId` both the
  patient's own id since there's no separate row id to log).
- **`TenantIsolationIntegrationTest`** gained
  `medicalHistoryIsNotReadableOrWritableFromAnotherTenant`, matching that
  file's own "one canonical cross-tenant check per staff-scoped resource,
  added the moment the resource exists" convention.
  `MedicalHistoryControllerIntegrationTest` covers upsert (create-then-
  full-replace, confirming a later call's omitted fields actually clear
  the earlier call's values - not just overwrite the ones resent), the
  three-role gate (patient token forbidden), get-before-any-recorded 404,
  and cross-tenant 404. Compiles clean, confirmed to fail only via the
  pre-existing Testcontainers/Windows-npipe wall (`Failures: 0` in the
  surefire report) - not a regression.
- **Live-verified against the real running stack** - see CLAUDE-history.md's
  "Verified this session - phase 9: medical history" (real `demo-front-desk`
  login, confirmed the full-replace semantics actually null out omitted
  fields rather than retaining them, exactly one row in Postgres after
  two calls).
- **No frontend yet** - same scope boundary as phase 8.

## Phase 10: consent & compliance records

Third phase of the revised (EHR-leaning) phase plan. Three scoping
questions were put to the user before writing code (consent-type breadth,
signature capture, whether to introduce a "nurse" role) - all three
resolved with the recommended/simplest option, see "Domain decisions
pinned so far" for the full reasoning. New migration
`V9__consent_records.sql`.

- **`com.clinicops.consent`** (new package) - `ConsentRecord` (extends
  `BaseTenantEntity`, `patientId` FK, `consentType` allow-listed to
  `general_treatment`/`privacy_data`, `policyVersion`, `consentGiven`
  bool, optional `witnessName`/`languagePresented`/`dataSharingPreferences`,
  `signedAt`/`recordedBy`). Unlike Allergy/Vitals/MedicalHistory, this is
  **not** a singleton-per-owner entity - a patient can accumulate multiple
  rows over time (e.g. re-consenting after a policy version change), and
  genuinely has **no update/delete endpoint at all**, matching this app's
  own existing audit-only tables and the reference doc's "legal
  defensibility" reasoning for consent specifically.
- **`ConsentController`** - `GET`/`POST /api/patients/{patientId}/consent-records`.
  Access gate mirrors `AllergyController`/`PatientController` exactly
  (`front_desk`+`clinic_admin` write, `+provider` read) - the "don't add a
  nurse role" decision reinforces this being the same non-clinical-intake
  gate those two already use, not a new pattern. `consentType` is allow
  -listed the same way `Allergy.severity`/`Room.status` are.
  `consentGiven` defaults to `true` when omitted, but can be explicitly
  `false` - staff can record a genuine decline, not just an acceptance.
  PHI-audited like every other resource this session
  (`resourceType = "consent_record"`/`"consent_record_list"`).
- **`TenantIsolationIntegrationTest`** gained
  `consentRecordsAreNotReadableOrWritableFromAnotherTenant`.
  `ConsentControllerIntegrationTest` covers create+list (confirming two
  records for different types **accumulate**, the second never replacing
  the first - the one behavior genuinely distinct from every other phase
  -8/9 entity), the explicit-decline path, invalid-`consentType` 400, the
  role gate, and cross-tenant 404. Compiles clean, confirmed to fail only
  via the pre-existing Testcontainers/Windows-npipe wall (`Failures: 0` in
  the surefire report) - not a regression.
- **Live-verified against the real running stack** - see CLAUDE-history.md's
  "Verified this session - phase 10: consent & compliance records" (real
  `demo-front-desk` login, confirmed two different consent types both
  persist and both come back on GET - the accumulate-not-replace design
  holding against a real request).
- **No frontend yet** - same scope boundary as phases 8/9.

## Phase 11: prescription + coding depth

Fourth phase of the revised (EHR-leaning) phase plan. No user questions
this time - a straightforward extension of two existing entities
(`Prescription`, `Encounter`) rather than a new resource with its own
access-gate/shape forks to resolve; three design calls made and pinned
directly (see "Domain decisions pinned so far"). New migration
`V10__prescription_and_coding_depth.sql` (`ALTER TABLE`, not new tables).

- **`Prescription`** gained `route` (allow-listed), `frequency`/`duration`
  (free text), `quantityDispensed`/`refillsAllowed` (integers), `status`
  (allow-listed, defaults `"active"`) - all optional except `status`.
  Validated in `EncounterService.replacePrescriptions` before the
  delete-and-reinsert, same `ResponseStatusException`-on-invalid-value
  shape `RoomController`'s own status check uses.
- **`Encounter`** gained `icd10Codes` (free-text `TEXT`, e.g. `"J20.9,
  R05"`) - threaded through `UpsertEncounterRequest` ->
  `EncounterService.upsert` -> the entity, no validation against a real
  code set (see the pinned decision).
- **Existing frontend page untouched, deliberately** -
  `provider/Encounter.jsx` (frontend phase E) still only reads/writes the
  original fields; confirmed live it keeps working unchanged against the
  wider backend shape (new columns all nullable/defaulted). The new
  fields aren't surfaced in any UI yet - same "backend first" pattern
  phases 8-10 used, applied to an existing page instead of a new one.
- **`EncounterIntegrationTest`** extended in place (call sites updated for
  the new constructor arity) plus `anInvalidRouteOrStatusIsRejected`. No
  `TenantIsolationIntegrationTest` addition - fields, not a new resource.
  `Failures: 0`, 11/11 - not a regression.
- **Live-verified against the real running stack** - see CLAUDE-history.md's
  "Verified this session - phase 11: prescription + coding depth" (real
  `demo-provider` login, all six new prescription fields plus `icd10Codes`
  round-tripped through Postgres, the route allow-list rejecting a bad
  value live, and the existing provider encounter UI confirmed unaffected).

## Phase 12: sign-and-lock clinical notes

Fifth phase of the revised (EHR-leaning) phase plan - the one flagged from
the first sketch as reversing existing behavior, not just adding to it.
Explicit sign-off was obtained via a direct question before writing any
code, not just a generic "proceed" - see the four pinned decisions above.
New migration `V11__encounter_sign_and_lock.sql`.

- **`Encounter`** gained `signedAt`/`signedBy` (both null until signed).
  **`EncounterAddendum`** (new entity, `com.clinicops.encounter` package) -
  genuinely immutable once created, same "no update/delete anywhere"
  precedent as `ConsentRecord`/`PhiAccessLog`; only creatable once the
  parent encounter is signed.
- **`EncounterService`** - `upsert`/`replacePrescriptions` both call a new
  `requireUnlocked` check (throws `EncounterLockedException`, 409, once
  `signedAt` is set); `sign` (idempotent, mirrors `CheckInService`'s own
  transition shape); `addAddendum` (requires the encounter already
  signed; deliberately skips the documentable-status gate - a correction
  applies regardless of what the appointment's status has done since).
  `get` now bundles `addenda` (oldest-first) alongside encounter/
  prescriptions.
- **`EncounterController`** - two new no-body POST endpoints,
  `.../encounter/sign` and `.../encounter/addenda` (same role gate as
  every other encounter endpoint), plus an `EncounterLockedException` ->
  409 handler. Both PHI-audited.
- **`EncounterIntegrationTest`** gained six cases: sign locks both the
  encounter and its prescriptions (original content confirmed unchanged
  after a rejected edit, not just the call rejected); re-sign is
  idempotent; sign-before-encounter-exists rejected; addendum rejected
  pre-signing, accepted post-signing and shown in the bundled GET;
  clinic_admin signs on behalf of any provider; a different provider
  can't sign someone else's encounter. `Failures: 0`, 17/17 - not a
  regression.
- **Live-verified against the real running stack, with extra care given
  this phase's behavior-reversing nature** - see CLAUDE-history.md's
  "Verified this session - phase 12: sign-and-lock clinical notes" for the
  full write-up (a real `demo-provider` login signing a real encounter,
  the lock genuinely rejecting a direct edit and prescription change, an
  addendum confirmed both via the API and in Postgres, and the *existing*
  `provider/Encounter.jsx` page - not just raw API calls - confirmed to
  surface the real 409 message rather than fail silently).
- **A real, honestly-documented UX gap**: `provider/Encounter.jsx`
  (frontend phase E) has no dedicated "locked"/"signed" UI, no sign
  button, and no addendum form - a real frontend refinement, not scoped
  to this backend-only phase.

## Phase 13: provider profile hardening

Sixth phase - license number/expiry, employment status (allow-listed:
full_time/part_time/locum), and a digital signature image on `Provider`.
New migration `V12__provider_profile_hardening.sql`. First feature needing
real file storage - `com.clinicops.filestorage.FileStorageService` (local
disk + a Docker volume, per the phase-9 decision): one file per
`(subdirectory, ownerId)`, filename always `<ownerId><extension>` derived
from a validated content type (`image/png`/`image/jpeg` only, 2MB cap),
never the client-supplied filename - avoids an unexpected extension or a
path-traversal attempt reaching disk. `docker-compose.yml` gained a named
`uploads_data` volume, same pattern as `postgres_data`.

`ProviderController` gained `POST .../signature` (multipart, `clinic_admin`
only), `GET .../signature` (raw bytes, same 3-role gate as the resource),
`POST .../signature/remove` (hard delete, idempotent).
`ProviderControllerIntegrationTest` gained 7 cases - `Failures: 0`, 12/12.

**A real, previously-latent bug found and fixed**: the first live upload
through the browser 500'd, "Current request is not a multipart request."
`node-bff`'s `forwardToApi` always JSON-re-serialized `req.body`, but
`express.json()` never populates `req.body` for a multipart request - the
file bytes were silently discarded before reaching spring-boot-api. Fixed
by streaming the raw request through for a multipart content-type instead
(`body: req, duplex: 'half'`), preserving the original boundary. Detection
is now `isMultipartRequest` (exported, unit-tested, 3 new cases,
mirroring `shouldForwardBody`'s convention) - latent since node-bff's
proxy layer was first built, never exercised until this phase's own
upload.

**Live-verified against the real running stack** - see CLAUDE-history.md's
"Verified this session - phase 13" (a real PNG through the actual
browser->node-bff->spring-boot-api path, byte-for-byte round trip, and
the file's real presence/absence confirmed directly on the mounted Docker
volume via `docker exec`, not just the API's own claim).

No frontend UI for any of this yet (license/employment fields, signature
upload/preview) - same "backend first" scope boundary as every EHR-leaning
phase before it.

## Phase 14: referrals

Seventh phase - internal and external referrals, one `Referral` entity
(reference doc's Forms 21 and 12 - see pinned decisions above). New
migration `V13__referrals.sql`.

`ReferralService.create` enforces the internal-xor-external rule as a
cross-field check (400 otherwise) - a validation annotation on the record
can't express it. `referringProviderId` is explicit in the request, same
pattern as `LabOrder.orderingProviderId`. `status`/`priority` allow-listed;
`completedAt` auto-set on a terminal status. `ReferralController` mirrors
`LabOrderController`'s CRUD shape, PHI-audited.

`ReferralControllerIntegrationTest` (6 cases) + one `TenantIsolationIntegrationTest`
case - `Failures: 0` throughout. **Live-verified** - see CLAUDE-history.md's
"Verified this session - phase 14" (real internal+external referrals as
`demo-provider`, the neither-nor validation rejected live, a status
transition confirmed setting `completedAt`, all writes confirmed in
Postgres and `phi_access_log`).

No frontend UI yet - same "backend first" scope boundary as before.

## Phase 15: real billing build-out

Eighth and last phase of the revised plan - closes "Invoicing/billing
deferred" (pinned 2026-09-12). Three questions resolved before writing
code (owner scope, generation trigger, `taxRatePercent` wiring) - see
"Domain decisions pinned so far". New migration `V14__invoices_billing.sql`.

- **`com.clinicops.invoice`** - `Invoice` extends `BaseTenantEntity`,
  `appointmentId`/`labOrderId` nullable, one set per
  `chk_invoices_exactly_one_owner` - `Payment`'s phase-7 shape, finally
  applied to the table it was originally meant for. `InvoiceService` (a
  dedicated bean - spans `AppointmentType`/`LabOrder` pricing plus
  `ClinicSettingsService.resolve`, same reasoning as `ClinicSettingsService`
  itself): prices from `AppointmentType.priceAmount` or `LabOrder.
  totalCost` (already snapshotted, phase 7); `tax = subtotal * taxRate /
  100` (HALF_UP, scale 2). A second generate for the same owner throws
  `InvoiceAlreadyExistsException` (409) - immutable once issued, unlike
  Encounter's upsert-in-place.
- **`AppointmentInvoiceController`**/**`LabOrderInvoiceController`** -
  mirrors the Payment controllers' split-by-owner shape and role gate
  (`front_desk`+`clinic_admin`+`provider` read, `front_desk`+
  `clinic_admin` generate); no status gate.
- **`InvoiceControllerIntegrationTest`** (7 cases) - untaxed default,
  overridden tax rate applied, duplicate-generate 409, pre-generation
  404, provider read-only, lab-order pricing from snapshotted
  `totalCost`, cross-tenant 404. `Failures: 0`.
- **Live-verified** - see CLAUDE-history.md's "Verified this session -
  phase 15" (both owner types generated live via real staff logins, a
  real non-zero tax override applied correctly, both rows confirmed in
  Postgres).

No frontend UI yet - same "backend first" scope boundary as every
EHR-leaning phase before it.

## Frontend

`node-bff/frontend/` - a React + Vite + Tailwind SPA with its own
`package.json`/lockfile/`npm install`/`npm run build`, **not** an npm
workspace of `node-bff`. Nested under `node-bff/` specifically so
`docker-compose.yml`'s existing `node-bff` build context covers it. The
Dockerfile is multi-stage: a `frontend-build` stage runs
`npm ci && npm run build`, its `dist/` is `COPY --from=`'d into the runtime
stage as `./public`; `src/index.js` serves `public/` and falls back to
`public/index.html` for any GET that isn't `/health`, `/auth/*`, or `/api/*`.

Phase 1 built only a placeholder (`App.jsx` if/else between `PublicShell`
and a "signed in as X" `AppShell` stub, `GET /api/clinic/me` smoke test) -
phase 1 through 7 sessions all built backend only. **Frontend phase A**
(built 2026-09-13, same session as phase 7) is the first real frontend
work: a real navigational shell (actual `react-router-dom` routes, a
role-aware nav bar, per-tenant branding) plus one real, working dashboard
per role. No deep CRUD/booking/search screens yet - those are separate
follow-on frontend phases (B, C, ...) that slot into the shell this phase
built. Ported directly from the reference bus-ticketing-saas project's own
frontend (`customer`/`agent`/`operator_admin`/`platform_admin` + a `cargo`
module - read in full before building this), adapted to this app's role
names/domain, **except** its dashboards, which call dedicated backend
aggregation endpoints (`GET /api/agent/dashboard`, etc.) - deliberately not
added here (see "Deliberate scope boundary" below); this app's dashboards
compose the same kind of summary from **existing** endpoints instead.

- **`theme/BrandingProvider.jsx`** + **`lib/color.js`** (new) - ported
  near-verbatim from the reference. Fetches `GET /api/clinic/branding` only
  for `clinic_admin`/`front_desk`/`provider` (matches that endpoint's own
  `@PreAuthorize` - `platform_admin` isn't tied to a clinic, `patient`
  stays on the platform default) and writes `--brand`/`--accent`/etc. onto
  `document.documentElement`. Closes a loose end flagged in comments
  already sitting in `tailwind.config.js`/`index.css` since phase 1 ("a
  future BrandingProvider overrides them... for a signed-in clinic's
  staff") - confirmed live: a clinic with `brandColor` set (from earlier
  phase-5 verification) rendered its own orange header the moment
  `demo-clinic-admin` logged in, and reverted to the platform default blue
  for `demo-patient`/`demo-platform-admin`.
- **`layout/AppShell.jsx`** (rewritten) - a real layout route: branded
  header, a role-conditional `<nav>` (one "Dashboard" `NavLink` per role
  this phase - more links arrive with each future page), user info, the
  `POST /auth/logout` button, `<Outlet/>`. **`App.jsx`** (rewritten) - real
  `react-router-dom` routes mirroring the reference's `RootLayout`/
  `RoleHome`/`NotFound` shape: `/` renders the signed-in role's dashboard
  (or `PublicShell` logged out), plus a `RequireRole`-gated stable deep
  -link per role (`/patient`, `/front-desk`, `/provider`, `/clinic-admin`,
  `/platform-admin`) - confirmed live that a wrong-role visit (e.g. a
  `clinic_admin` token hitting `/patient`) redirects cleanly back to `/`,
  and an unknown path renders `NotFound` via the SPA's own client-side
  routing (not a server 404 - `src/index.js`'s static-fallback already
  handles this).
- **`components/`** (new) - small generic pieces ported/adapted from the
  reference: `StatCard` (simplified - no delta/sparkline, since there's no
  aggregation endpoint behind it here), `EmptyState`, `ErrorBanner`,
  `Skeleton`, `StatusPill` (style map rebuilt for this app's actual status
  vocabularies - appointment `booked/checked_in/roomed/with_provider/
  checked_out/no_show/cancelled`, lab order `requested/ordered/
  specimen_collected/in_transit/resulted/reviewed`, and the generic
  `active/inactive`).
- **`pages/<role>/Dashboard.jsx`** (new, one per role) - `patient`
  (`GET /api/my-appointments` + `GET /api/my-lab-orders`; "Book an
  appointment"/"Request a lab test" shown as visibly-disabled
  "coming soon" buttons, not dead links - the real flows are a later
  phase), `front-desk`/`clinic-admin` (`GET /api/appointments`, the
  tenant-wide list - **"active" counts** (`booked`/`checked_in`/`roomed`/
  `with_provider`), not "today", stand in for a real date-scoped worklist;
  see "Deliberate scope boundary"), `provider` (`GET /api/my-schedule`,
  already day-scoped server-side since phase 4 - a genuine "today"),
  `platform-admin` (`GET /api/platform/clinics`).

**Deliberate scope boundary for this phase: zero backend changes.**
`Appointment` carries no slot start-time in its response shape (time lives
on `Slot`, joined only inside `findProviderSchedule`'s query, never
returned in the JSON) - so no dashboard this phase shows exact
appointment time-of-day, only status/ref/count. Adding a `startTime` field
to these responses, and a tenant-wide "today" endpoint for front_desk/
clinic_admin (only the provider-scoped one exists), are natural, small
pieces of the *next* frontend phase (the one that builds real time-sorted
schedule/booking views), not this one.

**clinic_admin encounter-access fix** (built 2026-09-15) - closed the one
remaining gap from "Frontend covers every module..." below: `clinic_admin`
had full backend access to `EncounterController` (no ownership check,
confirmed since phase 4) but no page reaching it, since
`/provider/appointments/:id/encounter` and the `AppointmentDetail.jsx`
front-desk uses to drive check-in/payments/cancel/reschedule were both
gated `RequireRole role="..."` to a single role. Rather than building a
second encounter-documentation page, **shared the existing pages** -
confirmed first via `Grep` that `CheckInController`/`CancellationController`/
`RescheduleController` already permit `CLINIC_ADMIN` too (they do, since
phase 3), so nothing server-side needed to change:
- `App.jsx`'s `RequireRole role="front_desk"` on `/front-desk/appointments`,
  `/front-desk/appointments/:id`, `/front-desk/appointments/:id/reschedule`
  became `roles={['front_desk', 'clinic_admin']}`; `role="provider"` on
  `/provider/appointments/:id/encounter` became
  `roles={['provider', 'clinic_admin']}` - same `roles` array shape
  `/lab-orders` already used for its own provider+clinic_admin sharing.
- `AppShell.jsx` - added an "Appointments" nav link for `clinic_admin`
  pointing at the same `/front-desk/appointments` route (no separate
  clinic-admin-branded route created - reusing the URL, not renaming it).
- `front-desk/AppointmentDetail.jsx` - a "Document encounter" link/button,
  visible only when `hasRole('clinic_admin')` **and** the appointment's
  status is in the same `with_provider`/`checked_out` set
  `EncounterService`/`provider/Encounter.jsx` already gate on client-side -
  never shown to `front_desk` (zero backend access to encounter content,
  the phase-4 pinned decision), and never shown before there's actually
  something to chart.
- `provider/Encounter.jsx` - the "not ready to document yet" empty state's
  back-link was hardcoded to `/provider` (a `RequireRole role="provider"`
  -gated dashboard `clinic_admin` can't reach) - made role-aware:
  `clinic_admin` gets "Back to appointment" -> `/front-desk/appointments/:id`
  (the page they came from), `provider` keeps "Back to Today's Schedule" ->
  `/provider` unchanged.

**Verified live this session** - see CLAUDE-history.md's "Verified this
session - clinic_admin encounter-access fix" (as `demo-clinic-admin`,
reached the shared appointments list, opened a real `with_provider`
appointment, edited the note via the shared encounter page, confirmed
exactly one `encounters` row before/after; confirmed `front_desk` still
has zero access by both omission and route gate; confirmed `demo-provider`
sees the same edited note - the shared-editing path working across roles).

**Frontend phase B** (built 2026-09-13, same session) is that next phase for
the patient side specifically: the booking flow (patient + guest, full
lifecycle - create/view/cancel/reschedule, plus public tracking). Unlike
phase A, this one **couldn't** stay backend-free - two real gaps blocked a
working booking flow outright, not just a nicety, so both were fixed as
small, narrowly-scoped additions:

- **`GET /api/clinics/{clinicId}/providers`** (new, `ProviderController`) -
  a public `List<ProviderDirectoryView(id, fullName)>`, active only. Without
  it, a patient/guest had no way to discover which providers exist at all -
  `GET /api/clinics/{id}/availability` already requires a `providerId` the
  caller must already know, and `GET /api/providers` is staff-only. Exact
  same "narrow public directory view" pattern as `ClinicDirectoryView`/
  `AppointmentTypeDirectoryView`; one more `permitAll()` matcher in
  `SecurityConfig`, one more `PUBLIC_ROUTES` entry in `node-bff`.
- **`AppointmentWithSlotView`** (new interface projection,
  `com.clinicops.appointment`) - the phase-A-deferred `startTime`/`endTime`
  gap, fixed exactly where it was predicted to land: `GET /api/
  my-appointments` and `GET /api/my-appointments/{id}` only (the tenant-wide
  staff list/cancel/reschedule endpoints are untouched). A native query
  joined with `slots` (`SELECT a.*, s.start_time, s.end_time ... JOIN slots
  s ON a.slot_id = s.id`, column aliases matching the projection interface's
  getter names) - the first use of a Spring Data native-query interface
  projection in this app, otherwise the same join shape as
  `findProviderSchedule`. Cancel/reschedule mutations still return bare
  `Appointment`; the frontend re-fetches the enhanced GET afterward rather
  than trusting the mutation's own response shape.

Frontend: `pages/booking/ClinicPicker.jsx` (`/book`, public) -> `pages/
booking/BookingForm.jsx` (`/book/:clinicId`, public) - pick an appointment
type + provider, `components/booking/SlotPicker.jsx` (shared, also used by
`Reschedule`) shows open slots grouped by day, picking one reveals a
confirm panel (guest contact fields only when `!authenticated`, mirroring
the reference project's `SeatSelection.jsx`) -> submits via
`useCreateAppointment`/`useCreateGuestAppointment` (branched on
`authenticated`), an idempotency key minted once per attempt
(`useRef(crypto.randomUUID())`) and re-minted on a 409
(`SlotConflictException`) -> `pages/AppointmentDetail.jsx` (`/appointments/
:id`, reachable logged-in or as a guest, mirrors `BookingDetail.jsx`'s
state-vs-fetch fallback - a guest revisiting/refreshing has no session to
fetch by, dead-ends to `/track-appointment` instead; clinic/provider/type
**names** are resolved by cross-referencing the appointment's `tenantId`/
`providerId`/`appointmentTypeId` against the three public directory
endpoints, no per-appointment name-lookup endpoint needed). `pages/
Reschedule.jsx` (`/appointments/:id/reschedule`, patient-only) reuses
`SlotPicker` scoped to the appointment's own fixed `appointmentTypeId`
(the backend 400s a type change) with `providerId` re-pickable. `pages/
MyAppointments.jsx` (`/my-appointments`, patient-only) is the full list the
dashboard's own top-5 preview links out to. `pages/TrackAppointment.jsx`
(`/track-appointment`, public) ports the reference's `TrackBooking.jsx`.
`layout/PublicShell.jsx` gained a real header/nav/`<Outlet/>` (it was a
dead-end static page before this phase - `App.jsx`'s new `PublicHome` is
what actually renders at `/` for a logged-out visitor now).

**A real bug found live during this phase's own verification**: `Booking
Form.jsx`'s error banner was nested inside the `{selectedSlot && (...)}`
confirm panel - the 409 handler clears `selectedSlot` (so the taken slot's
button stops looking selected) in the same state update that sets the error
message, which unmounted the message in the same instant it would have
appeared. Never visibly wrong in casual testing (a slot conflict is rare to
trigger by hand); only caught because this session deliberately raced a
real second booking against the same slot via a raw `curl` call between
selecting it in the browser and confirming. Fixed by moving the error
banner outside that conditional block - `Reschedule.jsx`'s equivalent error
banner was already correctly placed outside its own analogous block.

Role info for UX-only nav/route gating comes from `GET /auth/me`. The OIDC
**ID** token is expected to carry no `realm_access.roles` on this realm
(matching the reference project's own finding) - only the **access** token
does; the BFF callback decodes its payload (no signature check needed, the
OAuth exchange already established authenticity) and merges
`realm_access.roles` into the session user. Re-verify this once real login
is tested against the actual realm. Real authorization stays entirely
server-side (`@PreAuthorize`).

**Frontend phase C** (built 2026-09-13, later session) is front-desk's own
walk-in UI - the first "Known gaps" item phase B's own write-up predicted.
Zero backend changes needed this time: every endpoint it drives
(`PatientController`, the check-in transitions, `RescheduleController`,
`CancellationController`, `AppointmentPaymentController`) already existed
and was already live-verified server-side in phases 2/3/7 - this phase is
pure frontend wiring against them.

- **`api/queries.js`** gained the front-desk hook group: `usePatients`/
  `usePatient`/`useCreatePatient`, a tenant-scoped `useAppointment(id)`
  (distinct from the patient-owned `useMyAppointment` - reads the bare
  `GET /api/appointments/{id}`, which does **not** carry `AppointmentWithSlotView`'s
  `startTime`/`endTime` the way `GET /api/my-appointments/{id}` does, per
  phase B's own documented scope boundary - `AppointmentDetail.jsx` shows
  `â€”` rather than a fabricated time, confirmed live below), one
  `useCheckInAction(path)` factory instantiated five times
  (`useCheckIn`/`useRoom`/`useStart`/`useCheckOut`/`useMarkNoShow`) rather
  than five near-identical hooks, and `useCancelAppointment`/
  `useRescheduleAppointment`/`useAppointmentPayments`/
  `useCreateAppointmentPayment`.
- **`pages/front-desk/PatientSearch.jsx`** (`/front-desk/patients`) - a
  live-search box over `GET /api/patients?query=` (empty query browses all),
  each result linking to `BookForPatient`; an inline "register new patient"
  form (`POST /api/patients`) shown on no-match or on request, submitting
  straight into the booking flow for the newly-registered patient.
- **`pages/front-desk/BookForPatient.jsx`** (`/front-desk/book/:patientId`)
  - reuses the same `SlotPicker`/confirm-panel shape as the patient/guest
  `BookingForm.jsx` from phase B, but reads the **staff** appointment-type/
  provider lists (`useAppointmentTypes`/`useProviders`, tenant-scoped) rather
  than the public per-clinic directories, since front-desk's own clinic is
  already resolved from their JWT - no clinic-picker step. Same error-banner
  -outside-the-conditional placement as phase B's fixed `BookingForm.jsx`,
  applied correctly from the start this time (not a repeat of that bug).
- **`pages/front-desk/AppointmentDetail.jsx`** - the front-desk counterpart
  to the patient-facing `AppointmentDetail.jsx`, plus what a patient's own
  view has no business doing: a single "Next step" button that swaps by
  current `status` (`Check in` -> `Room` -> `Start visit` -> `Check out`,
  plus a `Mark no-show` alongside `Check in`) driving `CheckInController`'s
  exact linear sequence, a payments list + record-payment form
  (`AppointmentPaymentController`), and reschedule/cancel actions - all
  three hidden once the appointment reaches a terminal status
  (`cancelled`/`checked_out`/`no_show`; payments stays visible/editable
  through every non-`cancelled` status, matching `AppointmentPaymentController`
  having no status gate of its own).
- **`pages/front-desk/Reschedule.jsx`** - same `SlotPicker`-reuse shape as
  the patient-facing `Reschedule.jsx`, posting to the staff
  `RescheduleController` endpoint (`newSlotId`/`newProviderId`) instead of
  the ownership-scoped one; provider defaults to the appointment's current
  one but is re-pickable.
- **`pages/front-desk/Appointments.jsx`** (`/front-desk/appointments`) - the
  full tenant-wide list the dashboard's own "recent appointments" preview
  links out to (same relationship as `MyAppointments.jsx` to the patient
  dashboard in phase A). `GET /api/appointments` carries only a `patientId`
  (or a guest's `contactName`) - patient names are resolved via one
  `GET /api/patients` call built into an id->name `Map`, not an N+1 lookup
  per row.
- `layout/AppShell.jsx`'s front-desk nav gained "Book for a walk-in"/
  "Appointments" links alongside the existing "Dashboard" one;
  `front-desk/Dashboard.jsx`'s stat cards and recent-appointments rows are
  now real links into this new UI instead of static text.

**Frontend phase D** (built 2026-09-14, later session) is clinic-admin's
settings/CRUD UI - providers (+ nested working-hours + login link/unlink),
rooms, appointment-types, fee-policies, lab-rates, and a settings/branding
hub. Ported from the reference bus-ticketing-saas project's own
`operator/Buses.jsx`/`Routes.jsx`/`RefundPolicies.jsx`/`SettingsLayout.jsx`/
`Settings.jsx`/`Branding.jsx` (all read in full before writing anything) -
same inline-edit-row/create-form/tab-hub shapes, adapted to this app's
resources. Zero backend endpoint changes needed - every endpoint this
drives already existed and was already live-verified server-side in phase
5 - but one real, pre-existing backend bug surfaced immediately on first
live use and did need a genuine fix (below), not just a frontend workaround.

- **A real bug found and fixed live**: a `ResponseStatusException` thrown
  directly with no dedicated `@ExceptionHandler` (the overlap/duplicate
  /bad-status checks sprinkled across most phase-5 controllers -
  `ProviderWorkingHoursController`'s overlap check was the one that
  actually got hit first) falls through to Spring Boot's own default
  `/error` JSON body, which - without `server.error.include-message:
  always` - omits the exception's own reason text entirely. The frontend's
  `err.message` then showed the generic `{timestamp,status,error,path}`
  JSON blob instead of "This window overlaps an existing one for this
  provider and day". This was a latent bug since phase 5 (every controller
  -local `@ExceptionHandler` returns a bare String and always worked -
  `client.js`'s own doc comment claimed *every* error body was plain text on
  that basis) - simply never exercised by a validation error actually
  reaching a `catch` block in a live session until this phase's working
  -hours overlap test. Fixed two ways together: `server.error.include
  -message: always` in `application.yml` (spring-boot-api) so the JSON body
  actually carries the message now, and `api/client.js`'s `parseErrorBody`
  (node-bff/frontend) to unwrap `{message: "..."}` JSON into just that
  string rather than assuming every error body is plain text - handles both
  shapes correctly now, not just the ResponseStatusException one. Confirmed
  live: the overlap 409 now shows "This window overlaps an existing one for
  this provider and day" instead of raw JSON.
- **`pages/clinic-admin/Providers.jsx`** - create form + inline edit (`full
  Name`/`specialty`/`roomId`/`status`) per `ProviderController`, all
  statuses shown (not active-only, unlike the booking-flow directories) -
  an admin needs to see and reactivate deactivated ones too. Two more
  panels toggle inline per row rather than separate routes:
  - **Working hours** (`WorkingHoursPanel`) - `ProviderWorkingHoursController`
    CRUD, day-of-week 0=Sunday select, `<input type="time">` for start/end,
    a hard `/remove`. The overlap-rejection bug above was found testing
    exactly this panel.
  - **Login** (link/unlink) - an email field posting to `/link-login` when
    unlinked, an "Unlink" button + "linked to a login" badge when linked -
    `ProviderController`'s existing endpoints, confirmed live with a real
    round trip (linked to `demo-front-desk`'s account, then unlinked).
- **`pages/clinic-admin/Rooms.jsx`** / **`AppointmentTypes.jsx`** - same
  create-form + inline-edit-row shape as `Providers.jsx`, simpler (no
  nested panels) - `RoomController`/`AppointmentTypeController`.
- **`pages/clinic-admin/SettingsLayout.jsx`** - the tab hub (`General`/
  `Branding`/`Fee Policies`/`Lab Rates`), ported near-verbatim from the
  reference's own `operator/SettingsLayout.jsx`.
  - **`Settings.jsx`** (General tab) - `ClinicSettingsController`'s
    `{overrides, effective, defaults}` shape edited as one full-replace
    form; each blank field shows the platform default as a placeholder/hint
    beside it. Confirmed live that clearing a field back to blank actually
    reverts `effective` to the platform default (not just that setting a
    value works) - re-confirms the phase-5 `RescheduleService` wiring is
    still intact from the UI's own save path, not just the raw API.
  - **`Branding.jsx`** - reads the already-resolved `GET /api/clinic/
    branding` (this endpoint has no raw-override/effective split the way
    settings does, since it's a single disjoint column group with one
    always-resolved view - `displayName`'s fallback to the clinic's legal
    name means the field pre-fills even when unset, harmless to resave
    as-is) with a live colour preview (`lib/color.js`'s `themeVars`,
    already built in frontend phase A) that visibly matches the real
    `AppShell` header/button chrome - confirmed live, not just built to
    look similar.
  - **`FeePolicies.jsx`** - tiers grouped by `providerId` (clinic-wide
    default first, then providers by name) - unfiltered `GET
    /api/fee-policies` fetched once, grouped client-side, same shape as the
    reference's own route-grouped `RefundPolicies.jsx`. Confirmed live that
    a provider-specific tier set stays entirely separate from the
    clinic-wide default (never merged in the UI, matching `FeeCalculator`'s
    own replace-not-merge semantics).
  - **`LabRates.jsx`** - `LabRateController` CRUD; `testCode` shown
    read-only (not an editable field) once a rate exists, matching
    `UpdateLabTestRateRequest`'s own shape (delete-and-recreate to move a
    rate to a different code, no in-place rename).
- `layout/AppShell.jsx`'s clinic-admin nav gained "Providers"/"Rooms"/
  "Appointment Types"/"Settings" links; `clinic-admin/Dashboard.jsx`'s
  three CRUD-backed stat cards are now real links into this new UI, plus a
  new "Manage settings" header button - same "make the new pages
  discoverable from the dashboard" treatment as front-desk phase C.

**Frontend phase E** (built 2026-09-14, later session) is the provider
clinical UI - encounter documentation (chief complaint/assessment/plan)
plus a full-replace prescription list, both on one page. Zero backend
changes needed - `EncounterController` has covered this exact shape since
phase 4. Reached only from the provider dashboard's "Today's Schedule"
rows (`pages/provider/Dashboard.jsx` gained the same row-wraps-in-`Link`
treatment as front-desk/clinic-admin's dashboards before it) - no separate
nav link, since `GET /api/my-schedule` is already the full day's list, not
a truncated preview the way front-desk's dashboard needed a separate
"Appointments" page to see everything.

- **`pages/provider/Encounter.jsx`** (`/provider/appointments/:id/
  encounter`) - fetches the appointment (`useAppointment`, the same
  tenant-scoped staff hook front-desk's pages already use) and resolves the
  patient name the identical way front-desk's `AppointmentDetail.jsx` does
  (`usePatient(appointment.patientId)`, falling back to `contactName` for a
  guest). **Client-side mirrors `EncounterService.requireDocumentableStatus`
  exactly** (`with_provider`/`checked_out` only) so the common case never
  round-trips a 409 - a `booked`/`checked_in`/`roomed` appointment shows an
  explanatory `EmptyState` instead of the form, confirmed live
  (`"This appointment is still 'booked' - start the visit..."`).
  - **Note form** - `useEncounter(id)` (`GET .../encounter`, `retry:
    false` since a 404 - no encounter documented yet - is an expected,
    common state here, not a failure to retry) drives create-vs-update
    button text ("Save note" vs "Update note"); `useUpsertEncounter`
    posts. Confirmed live via direct Postgres query that a second save
    updates the same row, never creates a duplicate (matches the DB's own
    unique-`appointment_id` constraint and `EncounterService.upsert`'s
    find-or-create).
  - **Prescriptions** - locked behind a "Save the note above first" message
    until an encounter exists (mirrors `EncounterService.
    replacePrescriptions`'s own 400 for the same case, checked client-side
    so that 400 is never actually hit in normal use), then a
    `TiersEditor`-style add/remove line-item list (medication/dosage/
    instructions), `useReplacePrescriptions` posting the whole list -
    confirmed live via Postgres that this is a genuine full-replace (one
    row, matching the one line submitted), not an append.
- **Not built this phase, a real gap**: `EncounterController` allows
  `clinic_admin` on every endpoint too (no ownership check for that role,
  same override pattern as everywhere else), but this phase only wired the
  route/page for `provider` - `clinic_admin` has no UI path to a specific
  appointment's encounter yet (front-desk's own `AppointmentDetail.jsx`,
  the natural place clinic_admin might reach one from, is `front_desk`
  -role-gated, not shared). Noted in "Known gaps" below rather than
  silently left implicit.

**Frontend phase F** (built 2026-09-14, later session) is the lab-orders
UI - the largest single frontend phase yet, covering all three angles the
kickoff spec's own phase 7 backend built: staff order creation/status
-transitions/results/cancellation, the patient request->confirm-and-order
flow, and public two-factor tracking. Zero backend/BFF changes needed -
every endpoint (including the tracking route's `permitAll()`/
`PUBLIC_ROUTES` wiring) already existed and was already live-verified
server-side in phase 7. Ported from the reference bus-ticketing-saas
project's own `cargo/Waybills.jsx`/`WaybillDetail.jsx`/`customer/
RequestShipment.jsx`/`MyShipments.jsx`/`pages/Track.jsx` (all read in full
before writing anything) - same list+create+pending-queue/detail
-with-status-actions/request-form/public-tracking shapes, adapted to lab
orders' own fields and status machine (`ordered -> specimen_collected ->
in_transit -> resulted -> reviewed`, or `cancelled`/`requested`).

- **`components/labOrder/LabOrderTestsEditor.jsx`** (new, shared) - the
  test-line-item editor (testCode/testName/specimenType) used by every
  form that submits a `tests: TestItem[]` list (create, edit-while
  -ordered, confirm-and-order) - same "one reusable line-item editor"
  shape as the reference project's own `WaybillItemsEditor.jsx`. A
  `<datalist>` of already-configured lab-rate test codes (from
  `useLabRates`, phase D) backs the test-code field so staff can see
  what's actually priceable without leaving the form.
- **`pages/lab-orders/LabOrders.jsx`** (`/lab-orders`, shared by
  `provider`+`clinic_admin` - identical `LabOrderController`/
  `LabOrderStatusController`/`LabOrderCancellationController`/
  `PatientLabRequestController` permissions on every endpoint here, one
  route tree instead of duplicating it per role) - a pending-patient
  -requests banner (`GET /api/lab-orders/requests`) linking into each
  order's own confirm-and-order form, a create form (patient/provider
  pickers + the tests editor + a consent checkbox), and the tenant-wide
  list with a client-side status filter. Patient/provider names resolved
  the same id->name-`Map` pattern front-desk's `Appointments.jsx`
  established, not per-row lookups.
- **`pages/lab-orders/LabOrderDetail.jsx`** - one page covering every
  `LabOrderStatusService` transition, `LabOrderCancellationController`,
  and `payment.LabOrderPaymentController`, mirroring
  `WaybillDetail.jsx`'s own shape exactly: a `requested` order swaps the
  normal action UI for a confirm-and-order form (pre-filled from the
  patient's freeform test names, same auto-init-on-load pattern as that
  reference page's own confirm-and-issue form); an `ordered` order can be
  edited (patient/provider/notes/priority/tests) or have its specimen
  collected (presented-ID field, confirmed live that a mismatch shows the
  real backend message - "Presented ID does not match the ID on file, or
  nothing is on file to check against" - not a raw error, and that this
  identity check treats no-ID-on-file as a mismatch too, the deliberate
  phase-7 divergence from check-in's own convention); `in_transit` shows a
  per-test result-entry form (value/unit/reference range/abnormal
  checkbox); `resulted`/`reviewed` show the entered results inline in the
  test table (visible immediately once resulted, matching the phase-7
  "not gated on review" decision - confirmed live as a genuinely different
  login, not just the ordering provider's own session).
- **`pages/patient/RequestLabTest.jsx`** (`/my-lab-orders/request`) - a
  clinic picker (`useClinicsDirectory`, public) + freeform test-name
  list (no test-code picker, no pricing shown - a patient names tests in
  plain language exactly like the reference project's own `consignee`
  -side request form leaves pricing to staff) + optional notes, posting
  to `POST /api/my-lab-orders`.
- **`pages/patient/MyLabOrders.jsx`** (`/my-lab-orders`, the full list the
  dashboard's own top-5 preview links out to) / **`MyLabOrderDetail.jsx`**
  (`/my-lab-orders/:id`, read-only) - **there's no dedicated
  `GET /api/my-lab-orders/{id}`** (only the list), so the detail page
  filters the same `useMyLabOrders(true)` list `MyLabOrders.jsx` already
  fetches rather than adding a new backend endpoint for one lookup - same
  "no extra endpoint needed" reasoning phase B's own `AppointmentDetail.jsx`
  used for resolving clinic/provider/type names from public directories.
  Every staff action (status transitions/confirm-and-order/payments) stays
  off this page entirely; result values ARE shown once resulted (only the
  anonymous tracking view hides them).
- **`pages/TrackLabOrder.jsx`** (`/track-lab-order`, public) - ported
  directly from this app's own `TrackAppointment.jsx` (not the reference
  project's `Track.jsx`, since this app already has its own established
  two-factor-ref-tracking-page convention) - `LabOrderTrackingView`'s
  narrow status/timestamps-only shape, mismatch and unknown-ref both 404
  identically, confirmed live via a genuinely anonymous session (logged
  out first, not just an unauthenticated route inside an authenticated
  tab).
- `layout/AppShell.jsx` gained a "Lab Orders" link for both `provider` and
  `clinic_admin`; `layout/PublicShell.jsx` gained "Track a lab order"
  alongside "Track an appointment"; `patient/Dashboard.jsx`'s
  "Request a lab test" `ComingSoonButton` (disabled since frontend phase A)
  is now a real link, and its lab-orders panel rows/-"View all" link are
  real too, matching the appointments panel's own treatment;
  `clinic-admin/Dashboard.jsx`'s "Pending lab requests" stat card is now a
  link into `/lab-orders`.

**Frontend phase G** (built 2026-09-14, later session) is platform-admin's
clinic onboarding UI - the last item named in the kickoff spec's own phase
plan without a page. Zero backend changes needed - `PlatformController`
has covered this exact shape since phase 6. Ported from the reference
bus-ticketing-saas project's own `platform/Operators.jsx` (read in full
before writing anything) - same create-form + inline-edit-row shape every
other phase-D-style CRUD page in this app already uses, with one
deliberate difference: this page also offers **Reactivate**, since
`PlatformController` has a real reactivate endpoint (phase 6), unlike the
reference project's operator-onboarding page, which only ever
soft-deactivates with no way back through the UI.

- **`pages/platform-admin/Clinics.jsx`** (`/platform-admin/clinics`) - a
  create form (name/org alias/domain) that's genuinely the only "create"
  call in this whole app that reaches out to Keycloak (a real
  Organization, via `ClinicProvisioningService`) before ever touching
  Postgres - slower and with more ways to fail (a taken alias, Keycloak
  unreachable) than every other create form here, called out in the
  page's own copy. `orgAlias`/`keycloakOrgId` isn't editable after
  creation (`UpdateClinicRequest` only takes `name`) - it's what
  `TenantContextFilter` matches a staff token's organization claim
  against, so changing it would silently break tenant resolution for
  every existing staff login at that clinic. Each row: inline edit
  (name only), Deactivate/Reactivate (idempotent, matching
  `PlatformController.setStatus`'s own re-call convention).
- `layout/AppShell.jsx` gained a "Clinics" link for `platform_admin`;
  `platform-admin/Dashboard.jsx` gained an "Onboard a clinic" header
  button, its two stat cards now link into `/platform-admin/clinics`, and
  its clinic list gained a "Manage" link - same "make the new page
  discoverable from the dashboard" treatment every prior phase's own
  dashboard tweak used.

## Post-phase-7 backend additions

Two of the "Known gaps" items closed in one session (built 2026-09-19),
chosen because both had an already-solved pattern to port from rather than
needing fresh design: the reference bus-ticketing-saas project's own
`com.bustix.notification` package (read in full before writing anything)
covers the outbox-dispatcher half almost verbatim; the initial-admin-login
half has no reference-project analog (it doesn't solve this either - see
CLAUDE.md's own phase 6 write-up), so that one's a fresh design built
directly against this app's existing `KeycloakAdminTokenProvider`/
`KeycloakOrganizationClient` (whose own javadoc already anticipated exactly
this second consumer).

- **`com.clinicops.notification.NotificationWorker`** - a line-for-line
  port of the reference project's own `NotificationWorker`
  (`@Scheduled(fixedDelay = 10_000)`, `MAX_ATTEMPTS = 5`, pulls
  `findTop50ByStatusOrderByCreatedAtAsc("pending")`, retries on failure,
  marks `failed` once attempts are exhausted), adapted only for this app's
  richer `Notification` shape (`type`/`payload` jsonb instead of
  `template`/`bookingId`). `NotificationSender` is the same one-method
  interface; `LoggingEmailSender` is the same stub (logs instead of
  calling a real provider) - still no real email/SMS provider anywhere in
  this app, but the outbox no longer just accumulates unread rows forever.
  `@EnableScheduling` was already on `ClinicManagementApplication` since
  phase 3's `NoShowScheduler`, so no new wiring needed there.
  `NotificationWorkerTest` (new, pure Mockito - no Spring context, no
  Testcontainers, actually runs on this dev machine) covers the
  successful-send/retry/permanent-failure paths; confirmed passing
  (`mvn -o test -Dtest=NotificationWorkerTest`).
- **Initial `clinic_admin` login on clinic onboarding** - closes the "a
  freshly onboarded clinic has nobody who can log in" gap pinned in phase
  6. `CreateClinicRequest` gained two **optional** fields, `adminEmail`/
  `adminFullName` - omitting both reproduces the original behavior exactly
  (verified via the existing `ClinicProvisioningServiceTest`'s 3-arg
  `provisionClinic(...)` overload, left untouched for old callers).
  Supplying `adminEmail` makes `ClinicProvisioningService.provisionClinic`
  also: create a real Keycloak user (new
  `KeycloakUserProvisioningClient.createUser`, username/email both set to
  the given email, `emailVerified: false`), assign it the `clinic_admin`
  realm role (`assignRealmRole` - a role-representation lookup then a
  role-mappings POST, the two-step shape Keycloak's admin API requires),
  add it as a member of the just-created organization (new
  `KeycloakOrganizationClient.addMember`, keyed by the org's
  Keycloak-internal id returned from `createOrganization` - previously
  discarded, now captured), and set a random 16-character temporary
  password (`setTemporaryPassword`, `temporary: true` so the user must
  change it at first login).
  - **No real email delivery** - the generated temporary password is
    returned once in the create response
    (`ClinicProvisioningResult { clinic, initialAdminTemporaryPassword }`),
    never persisted/logged - a platform_admin hands it over out of band.
    Same no-rollback caveat as clinic creation itself (a mid-sequence
    Keycloak failure leaves an orphaned user, recoverable by hand).
  - `PlatformController.createClinic` now returns `ClinicProvisioningResult`
    unconditionally - `PlatformControllerIntegrationTest`'s assertions
    updated to `$.clinic.*`, plus a new test mocking the full create/
    assign-role/add-member/set-password sequence. `ClinicProvisioningServiceTest`
    gained two more pure-unit cases - all 5 passing locally.
  - **Frontend**: `pages/platform-admin/Clinics.jsx`'s create form gained
    optional admin email/name fields and a one-time amber banner showing
    the temporary password (component state only, never persisted).
  - **Both features live-verified end to end** - see CLAUDE-history.md's
    "Verified this session - post-phase-7 backend additions" (the
    temporary password actually logged in with Keycloak forcing a
    password change first; a real booking's outbox row confirmed flipping
    `pending` -> `sent` in Postgres within the worker's own poll window).

**Test-coverage gaps closed (built 2026-09-20)** - an audit found
`PatientController`/`AppointmentPaymentController` had **zero** dedicated
tests, and the consolidated `TenantIsolationIntegrationTest` was still
genuinely unbuilt (11 of 19 resource classes had their own inline
cross-tenant case; Patient/AppointmentPayment/ProviderWorkingHours had
none). ClinicSettings/branding and `my-schedule`/`my-appointments` have no
cross-tenant vector to test (resolve entirely from the caller's own
token); `PlatformController` is cross-tenant by design - all three
excluded with a comment, not silently skipped.

- **`PatientControllerIntegrationTest`** (new) - create/list/get/search,
  required-field validation, role checks (`front_desk`/`clinic_admin`
  write, `+provider` read, `patient` refused), cross-tenant 404 on get/
  list/search alike.
- **`AppointmentPaymentIntegrationTest`** (new) - role gate
  (`front_desk`/`clinic_admin` record, `+provider` read), a non-positive
  amount and a blank method both 400, cross-tenant 404 on list and record.
- **`TenantIsolationIntegrationTest`** (new, `com.clinicops.tenant`) - one
  method per staff-scoped resource, each seeding in clinic A and asserting
  clinic B's staff get 404 on every path checked (working-hours/payments
  also assert the write path), plus one deactivated-clinic lockout case.
  Narrower than the existing per-resource tests, not a replacement.
- All three compile clean and were confirmed to fail **only** via the
  pre-existing, documented Testcontainers/Windows-npipe wall (`Failures:
  0` in every surefire report) - not a new regression.

**PHI access audit log** (built 2026-09-20) - closes the "PHI-access audit:
deferred" gap pinned 2026-09-12. No reference-project precedent - two
scoping questions were put to the user before writing code: **what counts
as PHI** (answered "patient + clinical records" - `PatientController` plus
encounters/prescriptions/lab orders, explicitly not `fee_policies`/
payments) and **reads, writes, or both** (answered "both").

- **`com.clinicops.phiaudit`** (new package) - `PhiAccessLog` (extends
  `BaseTenantEntity`; `patientId` nullable for a guest-channel appointment/
  encounter with no `Patient` row at all), `PhiAccessLogRepository`,
  `PhiAccessAuditService` (the actual logger - `logRead`/`logWrite`,
  resolves the actor's internal user id via the existing
  `CurrentUserService.resolveInternalUserId` - already used identically by
  `AppointmentPaymentController`'s `recordedBy` - plus email/role straight
  off the JWT), `PhiAccessLogController` (`GET /api/clinic/phi-access-log`,
  `clinic_admin` only, optional `?patientId=`, most-recent-200, no
  pagination framework - same simplicity as every other list endpoint in
  this app). New migration `V6__phi_access_log.sql`.
- **Explicit call sites, not AOP/an annotation** - a plain injected bean
  called by name (matches the Notification outbox/`TenantContext` style).
  Wired into `PatientController`, `EncounterController` (`patientId`
  resolved via the appointment - a new `AppointmentRepository` dependency),
  and the `laborder` package's staff-facing controllers. Deliberately
  **not** wired into `PatientLabRequestController`'s patient-facing
  endpoints - scoped to staff-initiated access only ("staff looked at
  this" vs. "the patient checked their own record").
  - **List/search endpoints log one row per call, not one per result row**
    (`PatientController.patients`/`LabOrderController.labOrders`) - a
    search touches many patients at once, not one specific chart;
    `resourceType` is `patient_search`/`lab_order_list` with a null
    `patientId`/`resourceId` for these.
  - **Best-effort, not transactional with the domain write it accounts
    for** - `PhiAccessAuditService.log` catches and logs a warning on any
    repository failure rather than propagating it (same reasoning as
    `NotificationWorker`); a domain write can in principle succeed while
    its audit row fails - a documented limitation, not an oversight.
- **`PhiAccessAuditServiceTest`** (pure Mockito, runs locally) - role
  extraction, null patientId allowed, unrecognized role -> `"unknown"`,
  repository failure swallowed; 5/5 passing. `PhiAccessLogIntegrationTest`
  (Testcontainers, blocked here same as every other) covers the
  real-request wiring: patient create+read each write their own row, an
  encounter read resolves `patientId` through the appointment, review is
  `clinic_admin`-only and tenant-scoped.
- **Live-verified against the real running stack** - see CLAUDE-history.md's
  "Verified this session - PHI access audit log" (real `demo-front-desk`/
  `demo-clinic-admin` logins, both a write and read row confirmed in
  Postgres with the correct patient id, and the review endpoint's actor
  identity confirmed resolved from a real Keycloak token, not mocked).

**Payment auto-charge** (built 2026-09-20) - closes "nothing auto-creates a
payment from a cancellation/reschedule fee." Scoped with the user first
(this app has no real payment gateway, so "auto-charge" means auto
-creating a real `Payment` row for the fee, not literally charging a
card). `Payment`'s own javadoc previously claimed cancelling never creates
one - now the documented exception, not the rule.

- **`Payment.FEE_AUTO_CHARGE_METHOD`** (`"fee_auto_charged"`) distinguishes
  these from a genuine staff-entered payment. A zero fee creates nothing -
  matches `FeeCalculator`'s own fallback, so an unconfigured clinic sees
  no behavior change.
- All three fee-computing services (`CancellationService`,
  `RescheduleService`, `LabOrderCancellationService.cancel`) create one
  alongside their existing audit row, same transaction.
- **Self-service fees attribute to the patient's own account** - a new,
  separate `payerUserId` param on `CancellationService`/`RescheduleService`,
  kept distinct from the existing staff-only `cancelledByUserId`/
  `actingUserId` (whose "null = self-service" signal is unchanged).
- **Live-verified** - see CLAUDE-history.md's "Verified this session -
  payment auto-charge" (a real guest booking with no `Patient` row
  cancelled through the actual front-desk UI, confirmed in Postgres as a
  `fee_auto_charged` payment for the right amount). Extended
  `CancellationIntegrationTest`/`RescheduleIntegrationTest`/
  `LabOrderIntegrationTest` accordingly - `Failures: 0` throughout.

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
- `LabRateControllerIntegrationTest`/`LabOrderIntegrationTest`/
  `LabOrderPaymentIntegrationTest`/`PatientLabRequestIntegrationTest`
  (phase 7) - lab-rate CRUD + duplicate-testCode 409; multi-test create
  with snapshotted pricing; missing-rate 400; empty-tests 400;
  restricted-test 400 (and `consentAcknowledged` bypass); encounter
  -patient-mismatch 409; the full `ordered -> specimen_collected ->
  in_transit -> resulted -> reviewed` happy path (idempotent re-calls,
  out-of-order 409s); `collect-specimen`'s identity check (mismatch 409,
  no-ID-on-file 409 too - the deliberate divergence from check-in);
  resulted-but-unreviewed values readable by a second provider; test-list
  replace pre/post-collection; cancellation fee at the zero-cutoff tier;
  the `chk_payments_exactly_one_owner` CHECK violation via raw JDBC;
  payments scoped correctly per owner; the patient request ->
  confirm-and-order round trip (incl. re-pricing), role checks, idempotent
  -past-`requested` 409, cross-patient isolation, and `GET /api/
  my-lab-orders` unioning both ownership paths. Same Testcontainers/
  Windows-npipe wall as every prior phase - confirmed via the surefire
  report, not a regression from this phase's code.
- `VitalsTest` (phase 8, pure unit) - BMI computed correctly from height/
  weight; null when either input is missing; null (not a divide-by-zero
  throw) when height is zero.
- `AllergyControllerIntegrationTest`/`VitalsControllerIntegrationTest`
  (phase 8) - allergy create/list/partial-update, default severity when
  omitted, invalid severity/status 400, role gate (front_desk+clinic_admin
  write, +provider read), cross-tenant 404, updating an allergy under the
  wrong patientId 404; vitals upsert (create-then-update the same row),
  BMI round-tripped through the API, front_desk write despite no other
  clinical access, no status gate even at plain `booked`, cancelled-
  appointment 409, pain-score-out-of-range 400, get-before-any-recorded
  404, role gate, cross-tenant 404. `TenantIsolationIntegrationTest` grew
  one case per resource. Same Testcontainers wall as every other
  `AbstractIntegrationTest` subclass - confirmed via the surefire report.
- `MedicalHistoryControllerIntegrationTest` (phase 9) - upsert (create,
  then full-replace confirming omitted fields clear rather than persist),
  three-role gate (patient token forbidden), get-before-any-recorded 404,
  cross-tenant 404. `TenantIsolationIntegrationTest` grew one more case.
  Same Testcontainers wall - confirmed via the surefire report.
- `ConsentControllerIntegrationTest` (phase 10) - create+list accumulating
  rather than replacing (two different consent types both persist and
  both come back), explicit-decline (`consentGiven: false`),
  invalid-`consentType` 400, role gate (front_desk+clinic_admin write,
  +provider read, patient token forbidden), cross-tenant 404.
  `TenantIsolationIntegrationTest` grew one more case. Same Testcontainers
  wall - confirmed via the surefire report.
- `EncounterIntegrationTest` extended (phase 11) - the new prescription
  fields/`icd10Codes` round-trip through the existing upsert/replace
  endpoints, plus a new invalid-route/invalid-status 400 case. No new
  test class - existing coverage widened in place since this phase only
  added fields, not a new resource. Same Testcontainers wall.
- `EncounterIntegrationTest` extended again (phase 12) - sign locks the
  encounter+prescriptions (content confirmed unchanged after a rejected
  edit); idempotent re-sign; sign-before-encounter-exists 400; addendum
  gated on already-signed; clinic_admin sign-on-behalf-of; cross-provider
  sign 403. Same Testcontainers wall.
- `ProviderControllerIntegrationTest` extended (phase 13) - signature
  upload/get/remove round trip, invalid content type 400, get-before
  -upload 404, role gate, cross-tenant 404, invalid employment-status 400.
  `node-bff` gained `isMultipartRequest` (3 cases) - both suites clean.
- `ReferralControllerIntegrationTest` (phase 14) - internal/external
  create, completedAt-on-completion, internal-xor-external 400, invalid
  priority/status 400, role gate, cross-tenant 404. Same Testcontainers
  wall.
- `InvoiceControllerIntegrationTest` (phase 15) - untaxed-by-default
  generation, the clinic's tax-rate override actually applied, duplicate
  -generate 409, get-before-generation 404, provider read-only (403 on
  generate), lab-order invoice prices from its snapshotted `totalCost`,
  cross-tenant 404. Same Testcontainers wall.

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
- **Frontend now covers every module named in the kickoff spec's own
  phase plan** (phases A through G - shell/dashboards, patient/guest
  booking, front-desk's walk-in flow, clinic-admin's settings/CRUD,
  provider encounter documentation, the full lab-orders module, and
  platform-admin clinic onboarding) - real routing/nav/branding, every
  role's dashboard, and every backend endpoint built across all seven
  backend phases now has a live-data page reaching it. No named-module
  gap remains; what's left (below) is smaller cross-cutting items - no
  automated frontend test suite, and the pre-existing backend known-gaps
  list further down (PHI audit, real payment-gateway integration,
  notifications, per-clinic timezone, etc.) - not missing screens. See
  "Frontend" above for phase A/B/C/D/E/F/G's own write-ups and deliberate
  scope boundaries.
- ~~`clinic_admin` has no UI path to encounter documentation~~ **closed
  2026-09-15** - see "clinic_admin encounter-access fix" above.
  `front-desk/AppointmentDetail.jsx` is now shared with `clinic_admin` (a
  new "Document encounter" link on it, gated by role + status), and
  `/provider/appointments/{id}/encounter`'s `RequireRole` widened to allow
  both roles.
- `demo-front-desk` (created live this session for frontend-phase-A
  verification - see "Verified this session") isn't yet in
  `infra/keycloak/realm-export.json`, so a from-scratch environment won't
  have it pre-seeded - same gap phase 4/6 already left for
  `demo-provider`/`demo-platform-admin`, now applies to this fourth role
  too. Create it live the same way (admin REST API: realm role + demo
  -clinic Organization membership + a password reset) until someone adds
  all of these to the realm export properly.
- No automated frontend test suite (no Jest/Vitest/React Testing Library
  set up in `node-bff/frontend/`) - every frontend claim above is `npm run
  build` succeeding plus a real, manual browser walkthrough, not a repeatable
  automated check. `node-bff`'s own server-side `npm test` (20 tests) is
  unaffected/unrelated - it never touches `node-bff/frontend/`.
- ~~No PHI-access audit log in v1~~ **closed 2026-09-20** - see
  "Post-phase-7 backend additions". Scoped to staff-initiated access only
  (not a patient viewing their own record) across
  Patient/Encounter/Prescription/LabOrder; not the same DB transaction as
  the domain read/write it accounts for, and list/search endpoints log one
  row per call rather than one per result row - both deliberate, documented
  boundaries, not oversights.
- Payments are recordable (`com.clinicops.payment`, phase 7) but still
  purely a manual staff-entered record for a genuine cash/card/etc.
  payment - no real payment processor/gateway integration, no refund flow.
  ~~nothing auto-creates a payment from a cancellation/reschedule
  fee~~ **closed 2026-09-20** - see "Post-phase-7 backend additions":
  a non-zero fee now auto-creates a `Payment` row (`method =
  "fee_auto_charged"`) the moment it's computed, so staff no longer has to
  separately re-type an amount the system already knows. That row still
  only means "this fee was assessed," not "cash/a card was actually
  collected" - there's still no real gateway behind it, and no refund
  concept exists for the opposite direction.
- ~~`invoices` still exists in `V1__init.sql`, unused by any code~~
  **closed 2026-09-21** - see "Phase 15: real billing build-out".
  `com.clinicops.invoice` generates one on staff request (appointment or
  lab order), with `ClinicSettings.taxRatePercent` finally wired up to
  compute a real `tax_amount`. Still no invoice PDF/printable view and no
  link from a payment back to the invoice it's paying down (the two stay
  entirely separate financial records) - not solved here.
- ~~Email/notifications: the `notifications` outbox table is written to but
  there's no `NotificationWorker`/real sender yet~~ **partially closed
  2026-09-19** - see "Post-phase-7 backend additions".
  `com.clinicops.notification.NotificationWorker` now drains the outbox
  (fixed-delay poll, retry with a max attempt count, marks
  `sent`/`failed`), but `NotificationSender` is still only
  `LoggingEmailSender` - a stub that logs instead of calling a real
  provider. No real email/SMS delivery exists anywhere in this app yet;
  rows now reliably end up `sent` (logged) or `failed` instead of
  accumulating as `pending` forever, but nothing actually reaches an inbox.
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
- No git remote is configured for this repo yet, and `gh` is not
  authenticated in this environment - device-code `gh auth login` cannot
  complete when relayed through the tool execution context (it times out
  waiting on the browser-approval step). CI has therefore never run on this
  repo - "should run clean in CI" claims above remain unconfirmed by an
  actual CI run.
