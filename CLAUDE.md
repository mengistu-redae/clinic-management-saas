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

Still queued, not yet built: a visit/encounter summary document (the
outpatient equivalent of a discharge summary, generated at checkout) -
the last of the three phases scoped alongside immunizations.

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

- **`com.clinicops.pharmacy`** - `Medication` (the catalog: name/form/
  unitPrice/reorderThreshold, soft-deactivate only, same reasoning as
  Room/AppointmentType - referenced by FK from StockBatch/DispenseRecord
  with no cascade), `StockBatch` (one received lot -
  `quantityReceived`/`quantityOnHand`/`expiryDate`, status
  active/depleted/expired/recalled, no delete endpoint at all - real
  inventory/audit weight, same precedent as `Allergy`), `DispenseRecord`
  (genuinely append-only, no update/delete anywhere, same shape as
  `AppointmentCancellation`/`ConsentRecord`/`PhiAccessLog`).
  - **Deliberately no drug-name-matching logic** - a `Prescription`
    (`com.clinicops.encounter`, phase 4/11) stays exactly as it always
    was, free-text `medicationName` a provider writes; a pharmacist reads
    that text and manually picks the corresponding catalog `Medication`
    (and a specific `StockBatch` - no automatic FEFO allocation) when
    dispensing. Two independent entities, joined only by a human's own
    judgment, not a foreign key or a fuzzy-match algorithm.
  - **`DispenseService`** (`com.clinicops.pharmacy`) - a single bean, plain
    `@Transactional`, same "not a contended resource" reasoning
    `EncounterService` already gives for skipping the Redis-lock
    split-bean pattern. Validates the picked batch belongs to the picked
    medication (400), enough `quantityOnHand` remains
    (`InsufficientStockException`, 409), and - only when the prescription
    itself has a prescribed total set - that this dispense wouldn't push
    the cumulative dispensed-so-far past it (400, computed via
    `DispenseRecordRepository.sumQuantityByPrescriptionId`, same "compute
    the running total in a query" shape `RefundRepository.
    sumAmountByPaymentId` already established). Decrements the batch and
    auto-flips it to `depleted` at exactly zero.
  - **`PrescriptionRepository.findPendingDispense`** (new, lives with
    `Prescription` in `com.clinicops.encounter`, not in the pharmacy
    package - same "the projection lives with its entity's repository"
    precedent `AppointmentWorklistView` already set) - a native query
    joining `prescriptions`/`encounters`/`appointments` for a real
    `patientId`/`contactName` (no JPA relation exists between any of
    these), returning every `status = 'active'` prescription whose
    prescribed total (if any) still exceeds its live dispensed-so-far sum.
    Backs `GET /api/pharmacy/queue` (`MedicationController`'s sibling
    `DispenseController`) - the pharmacist's own worklist.
  - **`MedicationController`** - `GET/POST /api/clinic/medications`,
    `GET /{id}`, `POST /{id}/update` (same phase-5 CRUD shape everywhere
    else uses); nested `GET/POST .../{id}/stock-batches` (receive stock)
    and `POST .../stock-batches/{id}/write-off` (a dedicated action
    endpoint, same convention as deactivate/reactivate elsewhere - sets
    status to expired/recalled with a reason and zeroes quantityOnHand).
  - **`DispenseController`** - `GET /api/pharmacy/queue`,
    `POST /api/prescriptions/{id}/dispense`. `pharmacist`+`clinic_admin`
    throughout, no other role has any access.
  - **Low-stock/expiring-soon stayed a per-medication client-side badge on
    the Medications page itself, not a separate dashboard-level banner** -
    a genuine simplification made while building, not in the original
    sketch: a cross-medication aggregate view would need a new bulk
    query (every medication's batches at once), and the per-medication
    view already has everything it needs from data the page fetches
    anyway (each row's own nested stock-batches panel).
- **Frontend** (`pages/pharmacist/`, new) - `Dashboard.jsx` (`/pharmacist`)
  is the dispense queue itself: a stat card (pending-dispense count) +
  `DataTable` (search/sort, the modern-UI-redesign primitives built earlier
  this session) with `renderExpanded` opening a small dispense form
  (medication select → that medication's own active/in-stock batches →
  quantity). `Medications.jsx` (`/pharmacist/medications`) is the catalog,
  same `DataTable`+`renderExpanded` shape `clinic-admin/Providers.jsx`
  already established for its own nested panels - each row expands into
  an edit form plus the stock-batches sub-panel (list + receive-stock form
  + per-batch write-off buttons + the low-stock/expiring badges above).
  `layout/Sidebar.jsx` gained a `pharmacist` nav group (Dashboard,
  Medications) and one shared link for `clinic_admin`
  (`/pharmacist/medications`, matching the existing "clinic_admin gets a
  UI path into a module it overrides, not just backend access" precedent
  set by the clinic_admin encounter-access fix).
  - **A real, pre-existing gap fixed along the way**:
    `theme/BrandingProvider.jsx`'s `isStaff` check only listed
    `clinic_admin`/`front_desk`/`provider` - adding `pharmacist`/
    `accountant` there without also widening
    `ClinicBrandingController`'s own `@PreAuthorize` (still only those
    original three roles) would have made the frontend fetch branding for
    two roles the backend would 403 on. Both sides fixed together.
- **Roles**: `pharmacist` and `accountant` (used starting phase 21) added
  as real Keycloak realm roles on the running dev instance, plus demo
  users `demo-pharmacist`/`demo-accountant` (password `DemoPass123!`,
  joined to the Demo Clinic Organization) - see the
  `demo-keycloak-credentials` memory, updated. Both roles also added to
  `infra/keycloak/realm-export.json` for a from-scratch environment
  (unlike `demo-front-desk`, whose role already existed there - only the
  demo *users* are a live-only gap, same as that one).
- **Tests**: `DispenseServiceTest` (pure Mockito, 6 cases - successful
  dispense, exact-zero depletion, insufficient stock, exceeding the
  prescribed total, a batch belonging to a different medication, an
  unknown prescription - genuinely run locally, `Failures: 0`) +
  `MedicationControllerIntegrationTest`/`DispenseControllerIntegrationTest`
  (CRUD, invalid form/status 400, role gate, cross-tenant 404, the queue
  excluding an already-fully-dispensed prescription, insufficient-stock
  409) + one `TenantIsolationIntegrationTest` case
  (`medicationsAndStockBatchesAreNotReadableOrWritableFromAnotherTenant`).
  Confirmed via a clean `mvn clean test-compile` and by checking every
  other, unrelated integration test class in the same run failed
  identically (`Could not find a valid Docker environment`) - not a
  regression from this phase's own code, same as every prior phase.
- **Live-verified against the real running stack** - `docker compose up
  -d --build --force-recreate spring-boot-api node-bff` (both healthy),
  `V17` confirmed applied via `flyway_schema_history`
  (`success = t`), and `GET /api/pharmacy/queue`/`GET /api/clinic/
  medications` both confirmed reachable through the real nginx/node-bff
  chain (`401`, not `404`, with no session - the routes exist and require
  auth, exactly as expected). The two new demo Keycloak accounts were
  confirmed correct via the admin API (role mapping + Organization
  membership) but **not** via an actual browser login - the Chrome
  extension stayed disconnected the entire session (the same gap this
  session's own UI-redesign phase already flagged) - a real click-through
  as `demo-pharmacist` (create a medication, receive stock, dispense
  against a real prescription, confirm the queue/stock numbers update)
  is still owed once the extension reconnects.

**The owed `demo-pharmacist` click-through happened 2026-09-27 (later
session, extension reconnected) - a real bug was found and fixed.** Logging
in as `demo-pharmacist`/`DemoPass123!` through the actual browser worked
(role/org membership confirmed correct), but the dashboard's dispense queue
table went blank behind a raw `Request failed with status 403` banner a
few seconds after every load - reproduced live, including once mid-entry
(the in-progress medication/batch selection in an open dispense form was
lost when the table blanked underneath it).

- **Root cause**: `pages/pharmacist/Dashboard.jsx` called `usePatients()`
  (`GET /api/patients`) to resolve each queue row's `patientId` to a name -
  the same client-side id->name-`Map` pattern `front-desk/Dashboard.jsx`/
  `Appointments.jsx` already use. But `PatientController`'s read gate is
  `front_desk`/`clinic_admin`/`provider` only (pinned since phase 2) -
  `pharmacist` was never added, so the call always 403s. `DataTable`
  treats any truthy `error` prop as "replace the whole table with just the
  `ErrorBanner`" (by design, for the normal case of the table's own data
  failing to load) - here it wiped out the entire worklist over a
  secondary, non-essential lookup, not the queue data itself.
- **Fix: embed the name server-side, not widen `PatientController`** -
  `PrescriptionRepository.findPendingDispense`'s native query gained a
  `LEFT JOIN patients pt ON a.patient_id = pt.id` and now returns
  `patientName` directly (null for a guest booking, which has no
  `patientId`); `PrescriptionDispenseView` gained the matching getter.
  `pharmacist/Dashboard.jsx` dropped `usePatients()`/the `patientNames`
  `Map` entirely - `patientName(row)` is now just `row.patientName ||
  row.contactName || <guest text>`. Same reasoning `contactName` was
  already embedded directly in this same query rather than requiring a
  second lookup - a pharmacist gets exactly the one field this queue
  needs, not read access to the tenant's whole patient-search endpoint
  (`PatientController.patients` is PHI-audited and lets a caller browse/
  search every patient, a broader grant than this worklist needs).
- **A naively "simpler" fix was checked against real data and rejected
  before writing it**: just deleting `usePatients()` and falling back to
  `contactName` alone would have compiled and looked like it worked, but
  `contactName` is **only ever set for the `guest` channel** - a
  `patient_portal`/`front_desk` booking's `contactName` is always `null`
  (confirmed by reading `AppointmentController.createAppointment`, which
  passes `null, null` for it on that path). A direct Postgres query run
  against this session's own seed data confirmed two of the three pending
  prescriptions belong to a real `Patient` row named "Walk In" with no
  `contactName` on file - the naive fix would have silently mislabeled
  both as "Guest" in the UI, a data-correctness regression traded for
  fixing the crash. The join-based fix avoids this entirely: verified live
  post-fix that both "Walk In" rows render the real patient name, not
  "Guest".
- **Live-verified against the real running stack** - `mvn -q compile`
  clean, `docker compose up -d --build --force-recreate spring-boot-api
  node-bff` + `docker compose restart nginx` (the phase-Q stale-upstream-IP
  gotcha), then a fresh `demo-pharmacist` browser login: `GET
  /api/patients` no longer called at all (confirmed via the network
  panel - only `/api/pharmacy/queue` and `/api/clinic/branding` fire), no
  error banner on load or after an 8-second wait (long enough for the old
  bug's retry-then-fail cycle to have surfaced), and expanding a row still
  opens a working dispense form. The `Medications` page (catalog CRUD +
  nested stock-batch receive/write-off panel) was also exercised live this
  session and confirmed fully working, unrelated to the bug above.

## Phase 21: accounting

Second phase of the pharmacy/accounting/finance module set (built
2026-09-27, same session as the demo-pharmacist bug fix above). Three
scoping questions were put to the user before writing any code - chart-of
-accounts model, journal-entry mutability, and whether phase 21 includes
any reporting - all three answered with the recommended option, matching
this project's own "ask before building" convention for every new module.
No new migration needed for the `accountant` realm role - it (and its
demo user) already exist from phase 20. New migration `V18__accounting.sql`.

- **Chart of accounts: pre-seeded starter set, editable** - `com.clinicops
  .accounting.Account` (code/name/type/status, `UNIQUE(tenant_id, code)`).
  `AccountSeedingService.ensureSeeded(tenantId)` lazily seeds exactly three
  accounts the first time a tenant's accounting endpoints are touched -
  `1000 Cash` (asset), `4000 Service Revenue` (revenue), `4900 Refunds &
  Allowances` (revenue) - same "generate on first access, not at creation
  time" reasoning as `SlotGenerationService`, chosen because clinics
  already existed before this phase shipped. Deliberately seeds *only*
  what `JournalService`'s own auto-posting logic actually targets, not a
  generic full chart of accounts - no seeded-but-unused row; an accountant
  adds anything more specific (Accounts Receivable, Tax Payable, etc.)
  through `AccountController`'s own CRUD. `code` is fixed at creation, not
  editable via update - `JournalService` resolves the starter accounts by
  code, same "read-only after creation" precedent as LabRate's `testCode`.
- **Journal entries: genuinely immutable, corrected via reversing entries**
  - `JournalEntry` (header: description/sourceType/sourceId/postedBy) +
  `JournalLine` (own table, no JPA relation, same "plain UUID FK +
  explicit repository queries" convention as `LabOrderTest` - and, like
  that entity, carries its own `tenant_id` rather than relying only on a
  join, matching this codebase's "every entity extends BaseTenantEntity,
  even a line item" convention). No update/delete endpoint anywhere - same
  audit-only-table precedent as `ConsentRecord`/`PhiAccessLog`/
  `DispenseRecord`. A mistake is fixed with a new offsetting entry, not an
  edit - the phase's own pinned decision, not assumed.
- **`JournalService` auto-posts a balanced two-line entry for every
  Payment/Refund, called explicitly from all five existing call sites** -
  `PaymentService.recordPayment` and `RefundService.refund` (the two
  gateway-routed paths) plus the three `fee_auto_charged` shortcuts
  (`CancellationService`, `RescheduleService`, `LabOrderCancellationService`)
  that bypass `PaymentService` entirely. Same "explicit call sites, not
  AOP/an annotation" convention `PhiAccessAuditService` already
  established - chosen deliberately even though every one of the five
  sites wants the identical "post whenever a Payment/Refund is saved"
  effect, since an event-listener version would do exactly the same thing
  with less visibility at each call site. `postForPayment` debits Cash /
  credits Service Revenue for the full payment amount (every channel and
  method alike, `fee_auto_charged` included - a real cash event whether or
  not it ever went through the mock gateway, same "whatever's in here got
  collected" reading phase 19's own analytics already established for
  `Payment`); `postForRefund` debits Refunds & Allowances / credits Cash.
  Deliberately does **not** split tax out of an invoice-linked payment in
  v1 - the full amount posts straight to Service Revenue, matching this
  app's own "keep minimal in v1" convention (ICD-10 free text,
  Prescription's route allow-list) rather than building a tax-aware
  sub-ledger speculatively.
- **`AccountController`** (`/api/clinic/accounts`) - same phase-5/20 CRUD
  shape as `RoomController`/`MedicationController` (controller calls the
  repository directly, no dedicated service bean for plain single-row
  CRUD); `accountant`+`clinic_admin` only, matching `MedicationController`'s
  own "no other role has any access" gate - the ledger is as role-siloed
  as the pharmacy catalog. `type` allow-listed to asset/liability/equity/
  revenue/expense; a duplicate `code` 409s.
- **`JournalController`** (`/api/clinic/journal-entries`,
  `/api/clinic/trial-balance`) - read-only, same role gate. The trial
  balance is the one reporting view phase 21 was scoped to include
  (P&L/budget-vs-actual stays reserved for phase 22, per the roadmap):
  `JournalLineRepository.trialBalance` (a native query, same
  `@Query(nativeQuery = true)` + interface-projection shape as
  `PrescriptionRepository.findPendingDispense`) returns each account's raw
  debit/credit totals; the controller computes the signed balance from the
  account's own type (debit-normal for asset/expense, credit-normal for
  liability/equity/revenue) - same "compute the derived value in Java, not
  SQL" preference as `Vitals.getBmi()`.
- **Tests**: `JournalServiceTest` (new, pure Mockito, genuinely runs
  locally - 4 cases: a payment posts a balanced debit-Cash/credit-Revenue
  entry with the right source type/posted-by; a lab-order payment uses the
  `lab_order_payment` source type; a refund posts debit-Refunds/credit
  -Cash; a missing seeded account throws rather than posting an unbalanced
  entry) - `Failures: 0`, confirmed passing in the same run as the
  existing `PaymentServiceTest`/`RefundServiceTest` (both updated for
  `JournalService`'s new constructor param, still green) and
  `DispenseServiceTest` (unaffected). `AccountControllerIntegrationTest`
  (6 cases: starter-account seeding, full CRUD round-trip, invalid
  type/status 400, duplicate-code 409, role gate, cross-tenant 404) +
  `JournalControllerIntegrationTest` (3 cases, including a genuine
  end-to-end case that records a real payment through
  `AppointmentPaymentController` and asserts the resulting journal entry
  and trial-balance numbers) + one new `TenantIsolationIntegrationTest`
  case. Confirmed via a clean `mvn test-compile` and a full `mvn test` run
  showing `Tests run: 289` (up from 275 before this phase - exactly the 14
  new test methods) with every failure being the identical pre-existing
  `Could not find a valid Docker environment` wall every other
  `AbstractIntegrationTest` subclass hits on this machine - not a
  regression, and not a new failure mode introduced by this phase.
- **Live-verified against the real running stack** - `docker compose up -d
  --build --force-recreate spring-boot-api node-bff` + `docker compose
  restart nginx`, `V18` confirmed applied via `flyway_schema_history`
  (`success = t`). A real end-to-end chain through the actual browser: as
  `demo-front-desk`, recorded a genuine $75.00 card payment against a real
  appointment via `POST /api/appointments/{id}/payments`; as
  `demo-accountant` (a real login, not simulated), confirmed via the
  browser console that `GET /api/clinic/accounts` had lazily seeded the
  three starter accounts, `GET /api/clinic/journal-entries` showed exactly
  one entry with two balanced $75.00 lines (`sourceType:
  "appointment_payment"`), and `GET /api/clinic/trial-balance` showed Cash
  and Service Revenue both at a $75.00 balance, Refunds & Allowances still
  at zero. The refund path (`postForRefund`) was **not** exercised live
  this session - it's covered by `JournalServiceTest`'s own passing unit
  case, but a real `POST /api/payments/{id}/refund` -> trial-balance
  round trip is still owed, flagged here rather than claimed.
- **No frontend yet** - backend only, same "no dedicated UI this phase"
  scope boundary every EHR-leaning phase before it used. The ledger is
  reachable via `fetch(..., {credentials:'include'})` from the browser
  console today (exactly how it was live-verified above); a real
  chart-of-accounts/journal/trial-balance UI for the `accountant` role is
  a natural next frontend phase, not scoped here. `demo-accountant`'s own
  dashboard currently renders completely blank (no sidebar nav, empty
  main area) - expected, not a bug, since no route/nav case exists for
  this role yet on the frontend.

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

- **A real bug found and fixed while seeding Salary Expense** -
  `AccountSeedingService.ensureSeeded` used to short-circuit on "this
  tenant has ≥1 account at all," which meant a tenant that already had its
  original three accounts seeded (any clinic touched during phase 21's own
  live verification) would **never** pick up a starter account added by a
  later phase - confirmed live before writing the fix: the demo clinic's
  `accounts` table still showed only Cash/Service Revenue/Refunds &
  Allowances, no Salary Expense, right up until this phase's fix landed.
  Fixed by checking each starter account individually (the loop already
  did this correctly; only the wasteful-looking outer fast-path was wrong)
  - confirmed live post-fix that a single `GET /api/clinic/accounts` call
  against the already-seeded demo clinic silently backfilled Salary
  Expense with no migration or manual intervention needed. `AccountRepository
  .existsByTenantId` (now unused) was removed along with it.
- **Payroll: a new `Employee` entity, one per staff `AppUser` opted into
  payroll** - this app had no "Employee" concept before this phase (staff
  are just `AppUser` rows with roles; only `Provider` has its own domain
  entity). `com.clinicops.finance.Employee` (`appUserId`/`salaryAmount`/
  `status`, `UNIQUE(tenant_id, app_user_id)`) is resolved **by email at
  creation**, the exact same `ProviderController.linkLogin` precedent
  ("no account has ever logged in with that email" 404 if unresolvable) -
  there's no general staff-directory endpoint in this app to search by,
  and `fullName`/`email` are snapshotted at creation for display since
  there's nothing to re-resolve them from later. `EmployeeController` -
  same phase-5/20/21 CRUD shape, `accountant`+`clinic_admin` only.
- **The monthly pay-run action: a `PayrollRun` header + one `JournalService
  .postForPayroll` call per active employee, each its own balanced entry**
  - not one entry for the whole run, so a single employee's pay can be
  traced (or reversed) on its own, same reasoning `JournalService` already
  posts one entry per Payment/Refund rather than batching. `PayrollRun`
  (`UNIQUE(tenant_id, year, month)`) makes a re-run of an already-paid
  month a real 409, not a silent no-op - matches Invoice's own "immutable
  once issued" convention, not Encounter's upsert-in-place, since
  re-running payroll for a paid month is a genuine error. `PayrollPayment`
  (one row per employee per run, own table, same "plain UUID FK, carries
  its own tenant_id" convention as `JournalLine`/`LabOrderTest`) links
  back to the specific journal entry it caused. `postForPayroll` debits
  the new `5000 Salary Expense` account / credits Cash - `PayrollService`
  rejects a run with zero active employees (400) rather than creating a
  pointless empty run.
- **Budgets: per-account, per-month** - `com.clinicops.finance.Budget`
  (`accountId`/`year`/`month`/`amount`, `UNIQUE(tenant_id, account_id,
  year, month)`) references `com.clinicops.accounting.Account` directly -
  finance is explicitly built on phase 21's ledger, not a parallel account
  concept of its own. `BudgetController` - same CRUD shape, plus a real
  hard `/delete` (a missing budget row just means "no target set," the
  same well-defined-fallback reasoning FeePolicy/LabRate already use for
  their own hard deletes).
- **P&L and budget-vs-actual share one new period-bounded query** -
  `JournalLineRepository.periodBalance(tenantId, start, end)` is the exact
  same shape as phase 21's own all-time `trialBalance`, just with the
  period filter living in the `journal_lines` join's own `ON` clause
  (`jl.created_at >= :start AND jl.created_at < :end`) rather than a
  second join to `journal_entries` - an account with zero activity in the
  window still gets a row via the outer `LEFT JOIN`, aggregating correctly
  to zero instead of disappearing. Month boundaries resolve through
  `ClinicSettingsService.resolveTimezone(tenantId)` - the same shared
  phase-18 seam every other day/month-boundary call site in this app
  already goes through, deliberately not left on a hardcoded UTC reading
  the way it would have been if built before phase 18 existed.
  - **The signed-balance math (debit-normal vs. credit-normal) was
    factored out of `JournalController` into a new `BalanceMath` static
    helper** rather than duplicated a second time for these two new
    reports - the first genuinely cross-cutting piece of phase 21/22's own
    logic, unlike the plain CRUD controllers which still call repositories
    directly with no shared service bean.
  - **`GET /api/clinic/profit-and-loss?year=&month=`** - revenue lines
    (credit-normal) and expense lines (debit-normal) for that month only,
    `netIncome = totalRevenue - totalExpense`.
  - **`GET /api/clinic/budget-vs-actual?year=&month=`** - one row per
    account that has *either* a budget set for that period *or* real
    activity that period (a LEFT JOIN-shaped union in Java, not SQL) - an
    unbudgeted account with real spending is never silently hidden.
    `budgetAmount`/`variance` are `null` (not zero) when no budget was set
    for that account/period - a deliberately different signal than "this
    account is exactly on budget."
- **Tests**: `PayrollServiceTest` (new, pure Mockito, genuinely runs
  locally - 3 cases: running payroll posts one journal entry per active
  employee with the right amount/description/source; re-running an
  already-run month is rejected before touching employees or the ledger
  at all; zero active employees is rejected before creating a run).
  `EmployeeControllerIntegrationTest` (6 cases: create-by-email
  snapshotting the real AppUser's name/email, unknown-email 404,
  duplicate-appUser 409, update salary/status + invalid-status 400, role
  gate, cross-tenant 404), `PayrollControllerIntegrationTest` (5 cases,
  including a genuine end-to-end case that runs payroll through the real
  controller and asserts the resulting trial-balance numbers),
  `BudgetControllerIntegrationTest` (5 cases: full CRUD + hard delete,
  duplicate-period 409, unknown-account 404, role gate, cross-tenant 404),
  `FinanceReportControllerIntegrationTest` (3 cases: P&L only includes the
  queried month's activity - verified against both the real current month
  and a different month, not just one hardcoded date; budget-vs-actual
  computes variance and omits an unbudgeted/untouched account; role gate)
  + one new `TenantIsolationIntegrationTest` case. Confirmed via a clean
  `mvn test-compile` and a full `mvn test` run showing `Tests run: 312`
  (up from 289 - exactly the 23 new test methods), every failure the
  identical pre-existing `Could not find a valid Docker environment` wall
  - not a regression, not a new failure mode.
- **Live-verified against the real running stack** - `docker compose up -d
  --build --force-recreate spring-boot-api`, `V19` confirmed applied via
  `flyway_schema_history` (`success = t`) and the real container logs
  (`Migrating schema "public" to version "19 - finance"` ->
  `Successfully applied 1 migration`). As `demo-accountant` (a real
  login): confirmed the Salary Expense backfill bug fix live (documented
  above) before touching payroll at all; created a real `Employee` for
  `demo-front-desk`'s own already-provisioned `AppUser` row (email
  resolution confirmed working against a genuine account, not a test
  fixture); ran a real payroll for the current month
  (`POST /api/clinic/payroll-runs`), confirming a real journal entry
  posted and `totalAmount` matched the employee's salary exactly;
  confirmed re-running the same month 409'd; confirmed
  `GET /api/clinic/trial-balance` and `GET /api/clinic/profit-and-loss`
  both reflected the new payroll entry with internally consistent numbers
  (Cash's balance dropped by exactly the payroll amount, `netIncome` =
  revenue-so-far minus the new expense); created a real `Budget` for the
  Salary Expense account and confirmed `GET /api/clinic/budget-vs-actual`
  computed the correct variance (actual $1200 vs. budgeted $1000 = $200
  over) while every unbudgeted-but-active account showed `null`, not a
  misleading zero, and the untouched, unbudgeted Refunds & Allowances
  account didn't appear in the response at all.
- **Frontend built the same day (2026-09-28), a later session** - see
  "Frontend phase R: accountant/finance UI" under "## Frontend" below for
  the full write-up. This closes out the pharmacy/accounting/finance
  module set the user originally scoped across phases 20-22 - every role
  named in that set (`pharmacist`, `accountant`) now has both a working
  backend and a real frontend, matching the `pharmacist` module's own
  phase-20 precedent.

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

- **`POST /api/appointments/{id}/cancel-series`** (`CancellationController`,
  `front_desk`/`clinic_admin` - matching who can create a series in the
  first place, `AppointmentSeriesController`) - triggered from one specific
  occurrence, scoped to every still-`booked` occurrence sharing that
  appointment's own `seriesId` (the triggering occurrence included, if it's
  still booked itself). A plain `status = 'booked'` filter already means
  "not yet happened and not already cancelled" - no separate chronological
  -order comparison needed the way a naive "cancel this and everything
  after it" reading might have required.
- **Each occurrence goes through the exact same `CancellationService
  .applyCancellation` path a single cancel uses** - same fee-tier
  calculation, same `fee_auto_charged` Payment/journal posting, same
  per-occurrence audit row and notification - deliberately not a bulk
  shortcut that skips any of that. `CancellationService.cancelSeries` is
  the new method (one more `@Transactional` public entry point on the
  existing bean, reusing the already-private `applyCancellation` helper
  in a loop) - 400 if the triggering appointment isn't part of any series
  at all (`ResponseStatusException`, checked before touching anything).
- **`AppointmentRepository.findAllBySeriesId` was never actually wired to
  an endpoint before this phase** - added in the original recurring-series
  work but unused until now, and not tenant-scoped. Replaced outright with
  `findAllByTenantIdAndSeriesId(tenantId, seriesId)` - same "every
  staff-scoped repository method takes the tenant id explicitly" convention
  every other query in this codebase already follows, even though a
  series' own occurrences never span tenants in practice. The one existing
  test referencing the old bare method (`AppointmentControllerIntegrationTest
  .recurringSeriesReportsAPartialConflictAndStaysIdempotentOnRetry`) was
  updated to the new signature, not left broken.
- **`SeriesCancellationResult { cancelled: List<Appointment> }`** (new,
  `com.clinicops.appointment`) - same "wrapper record for one
  always-together response" shape as `AppointmentSeriesResult`/
  `EncounterWithPrescriptions`, deliberately simpler than the creation
  flow's own `{created, conflicts}` shape: a booked occurrence is always
  cancellable, there's no external resource (a slot) that could already be
  taken the way booking a new one has, so there's no conflict/partial
  -failure case to represent.
- **Frontend**: `front-desk/AppointmentDetail.jsx` gains a second,
  conditionally-shown link - "Cancel this and the rest of the series" -
  visible only when the loaded appointment's own `seriesId` is non-null
  (the plain `GET /api/appointments/{id}` response already serializes it,
  no new field needed) and the appointment isn't already terminal. Same
  confirm-then-act shape as the existing single-cancel button, plus a
  dismissible success banner reporting how many occurrences were actually
  cancelled (`fdAppointmentDetail.cancelSeriesSuccess`, a genuine
  `_one`/`_other` pluralized key - Amharic only needs the `_other` form,
  same established pattern as `myLabOrders.testCount`). New
  `useCancelSeries(id)` hook (`api/queries.js`) invalidates not just the
  triggering appointment but every appointment/payments query for each
  entry in the response's own `cancelled[]` list, since a single action
  here can affect several appointments' cached state at once - not
  something the existing generic `invalidateAppointment` helper handles.
- **Live-verified against the real running stack**, as `demo-front-desk`:
  seeded a genuine 3-occurrence weekly series via a direct authenticated
  `POST /api/appointments/series` call (no booking-a-series UI exists in
  this app - staff-only backend feature, reachable via the API this
  session used to seed it), then used the real browser UI end to end -
  clicked "Cancel this and the rest of the series" on the first occurrence,
  confirmed, and got "Cancelled 3 appointments in this series." Verified
  independently (not just trusting the mutation's own response) via a
  fresh `GET` on all three occurrence ids afterward - all three genuinely
  `cancelled`. Also exercised the identical flow in Dark theme + Amharic
  end to end on a second, freshly-seeded 2-occurrence series - the button
  label, confirm warning, and the pluralized success banner
  ("2 ቀጠሮዎች በዚህ ተከታታይ ውስጥ ተሰርዘዋል።") all rendered correctly, and the
  cancellation itself completed successfully in that state too, not just
  the translated copy.
- **Tests**: `CancellationIntegrationTest` gained three cases -
  `cancelSeriesCancelsEveryRemainingBookedOccurrenceAndFeesEachOne` (a real
  3-occurrence series via the actual `POST /api/appointments/series`
  endpoint, a catch-all `cutoffHours=0` fee tier so every occurrence gets
  fee-charged, asserting all three end up `cancelled` and each has its own
  separate `fee_auto_charged` Payment), `cancelSeriesOnAnAppointmentThatIsNot
  PartOfASeriesIsRejected` (400), `cancelSeriesIsNotFoundForAnotherTenants
  Appointment` (404, matching the existing single-cancel cross-tenant
  case's own shape). No new `TenantIsolationIntegrationTest` case - the
  cross-tenant check already lives inline in `CancellationIntegrationTest`
  for the plain single-cancel endpoint too, same precedent. Confirmed via
  a clean `mvn clean test-compile` and a full local run of every pure-unit
  (non-Testcontainers) test class - `69/69` passing, unaffected. The three
  new Testcontainers-only cases themselves are confirmed correct by the
  live browser verification above (the exact same code path, actually
  executed against a real Postgres), not by a local test run - still
  Windows-npipe-blocked for this whole suite.

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

- **`com.clinicops.immunization`** (new package), modeled directly on
  `com.clinicops.allergy.Allergy` - patient-level, not encounter-level (a
  vaccination isn't tied to one visit), a list that accumulates per patient
  at `/api/patients/{patientId}/immunizations`, no delete endpoint. Unlike
  Allergy, no `status` field - each row is a discrete historical fact (a
  dose given on a date), not an evolving condition with something to
  resolve/unconfirm. `vaccineName`/`administeredAt`/`patientId` are fixed
  at creation; only `doseNumber`/`lotNumber`/`site` are correctable via
  `POST .../{id}/update` (same "only the non-identity fields" partial
  -update shape as `UpdateAllergyRequest`).
- **One deliberate departure from Allergy's own access gate, made as a
  judgment call, not re-asked** (same "flagged as revisitable" treatment
  Allergy's own gate got in phase 8): Allergy's write gate is
  `front_desk`+`clinic_admin` only (intake-recorded data). An immunization
  is physically **administered** - the same real-world shape as Vitals,
  which this project already extended to `provider`+`clinic_admin`+
  `front_desk` specifically because there's no "nurse" role. Immunizations
  get that identical 3-role gate on both `GET` and `POST`, not Allergy's
  narrower 2-role one.
- **`ImmunizationController`** - no dedicated service bean, controller
  calls `ImmunizationRepository` directly, same as `AllergyController`.
  Ownership-checked via `PatientRepository.findByIdAndTenantId` (404 if
  the patient isn't this tenant's; a record whose `patientId` doesn't
  match the URL's also 404s on update). PHI-audited exactly like Allergy -
  `resourceType` `"immunization"`/`"immunization_list"`.
- **`TenantIsolationIntegrationTest`** gained
  `immunizationsAreNotReadableOrWritableFromAnotherTenant`.
  `ImmunizationControllerIntegrationTest` (new) covers create+list+update,
  missing-required-field (`vaccineName`/`administeredAt`) 400, all three
  write-gated roles succeeding while a `patient` token gets 403, cross
  -tenant 404, and update-for-the-wrong-`patientId` 404. Compiles clean;
  `mvn test` showed `Tests run: 321, Errors: 252` - the identical count of
  pre-existing Testcontainers/Windows-npipe failures as every other
  `AbstractIntegrationTest` subclass (69 pure-unit tests still passing,
  unchanged) - not a regression, and the new tests fail only via that same
  wall, not a new failure mode.
- **Live-verified against the real running stack** - `docker compose up -d
  --build --force-recreate spring-boot-api`, `V20` confirmed applied via
  `flyway_schema_history` (`success = t`) and the container reporting
  healthy (Hibernate's `ddl-auto: validate` accepted the new entity against
  the new table with no startup failure). As `demo-front-desk` (a real
  browser login through the actual nginx/node-bff/Keycloak chain, not
  simulated): created a real immunization, confirmed it in the list
  (count 1), partially updated `doseNumber`/`lotNumber` while leaving
  `site` untouched (`null` in the request correctly left the existing
  value alone, not cleared it - `UpdateImmunizationRequest`'s partial
  -update semantics holding for real), and confirmed `vaccineName`/
  `administeredAt`/`patientId` stayed fixed throughout. Cross-checked
  directly in Postgres - the row matches exactly what the API returned -
  and confirmed the PHI audit log picked up all three calls (`write`/
  `read`/`write`) correctly scoped to the real `patientId`. Cross-tenant
  lockout and the full role matrix were not separately exercised live
  (covered by the compiled, Windows-blocked integration suite instead) -
  same bar most backend-only EHR phases before this one used.
- **No frontend yet** - backend only, same scope boundary as phases 8-15;
  a UI is a natural candidate for a later batched frontend sweep, not
  built here. No `node-bff`/`PUBLIC_ROUTES` change (staff-authenticated
  only) and no new Keycloak realm role (reuses `provider`/`front_desk`/
  `clinic_admin`).

Physical-exam findings (structured review-of-systems checklist) and the
visit/discharge summary document are the next two phases in this backlog,
not touched here.

## Phase 25: physical-exam findings

Second of the three sequential full-EHR-breadth phases. Three scoping
questions were put to the user before writing any code - access gate,
storage shape, and sign-and-lock interaction - all three answered with
the recommended option. Body-system list taken from the user's own
`my-notes/clinical-forms-templates.md` ("Physical Examination Form"
section), restructured to the user's own "normal/abnormal flag + a
free-text note" shape rather than that template's plain free-text-per
-system fields. New migration `V21__physical_exam_findings.sql`.

- **18 new nullable columns directly on `Encounter`** - a fixed 9-system
  checklist (general appearance, HEENT, cardiovascular, respiratory,
  abdominal, musculoskeletal, neurological, skin, psychiatric), each a
  `Boolean` normal/abnormal flag (null = not examined this visit) plus a
  free-text note. Matches how `icd10Codes` (phase 10/`V10`) was added as
  a plain field rather than a new entity - the access gate and 1:1
  -per-encounter cardinality here are identical to `Encounter`'s own, so
  a separate table (the `Vitals`-style alternative) wasn't justified.
- **`UpsertEncounterRequest` grows to a 22-field flat positional record**
  - stays flat even at this width, matching `UpsertVitalsRequest`'s own
  "many nullable scalar fields in one record" precedent (9 fields there)
  rather than introducing a nested request shape with no precedent in
  this codebase.
- **`EncounterService.upsert` now takes the whole `UpsertEncounterRequest`
  record instead of four scalar parameters** - a deliberate, flagged
  deviation from its own prior shape: extending the existing "explode
  into scalars" pattern to 22 fields would have meant a 25-parameter
  method signature (with `appointmentId`/`tenantId`/`actingProviderId`),
  a real readability/error-proneness threshold. Pure parameter-passing
  change, zero behavior change - the same `requireUnlocked(encounter)`
  check still runs before any field is applied.
- **Exam findings lock with the rest of the encounter for free, by
  construction** - since the 18 new setter calls sit in the same `upsert`
  block, after the same lock check every other field already goes
  through, no new locking code was needed to satisfy the "locks with the
  encounter" decision. Confirmed live, not just by reading the code (see
  below).
- **No new endpoints** - exam findings ride on the existing
  `POST`/`GET /api/appointments/{id}/encounter`. No PHI-audit change
  needed (the existing `"encounter"` resource-type calls already cover
  this data, since it's part of the same entity now).
- **`EncounterIntegrationTest` extended in place** (matches phase 11's
  own precedent - a field addition to an existing resource, not a new
  test class): every existing `new UpsertEncounterRequest(...)` call site
  (17 of them) mechanically grew to the 22-arg form via a small Python
  script (not hand-edited one by one, to guarantee the appended `null`s
  landed in exactly the right position with no transcription risk); a new
  `examFindingsRoundTripThroughUpsertAndGet` case; and
  `signingLocksTheEncounterAndItsPrescriptions` gained an extra
  assertion - an exam-finding-only edit attempt is rejected post-sign
  too, and the field stays `null` afterward, not just chief
  -complaint/assessment/plan. No new `TenantIsolationIntegrationTest`
  case, same reasoning phase 11 already used. `mvn test` showed
  `Tests run: 322, Errors: 253` (up from 321/252 - exactly the one new
  test method, same pre-existing Testcontainers wall, 69 pure-unit tests
  still passing unaffected).
- **Live-verified against the real running stack** - `docker compose up
  -d --build --force-recreate spring-boot-api`, `V21` confirmed applied
  via `flyway_schema_history` (`success = t`) and the container reporting
  healthy (Hibernate `ddl-auto: validate` accepted the 18 new entity
  fields against the new columns with no startup failure). As
  `demo-provider` (a real browser login through the actual nginx/
  node-bff/Keycloak chain - the app's own `POST /auth/logout` form
  submitted via JS, not a bare `fetch` with `redirect: 'manual'`, which
  turned out to leave the Keycloak SSO cookie untouched and silently
  re-authenticated the previous user on the next login attempt; a real
  fresh username/password form on the second attempt confirmed the SSO
  session had actually ended): attempted an exam-finding edit against a
  **real already-signed** encounter (`signedAt` genuinely set from an
  earlier session) and got a real `409` - "This encounter was signed
  on... and is locked"; then, against a different, unsigned, documentable
  appointment (flipped from `no_show` to `with_provider` directly in
  Postgres for a clean test fixture, matching `EncounterIntegrationTest`'s
  own fixture-setup convention), posted a full 9-system exam - a genuine
  new abnormal finding (`cardiovascularNormal: false`, a systolic murmur
  note) alongside normal findings on the other 8 systems - confirmed
  correct in the `POST` response, the subsequent `GET`, and a direct
  Postgres query of the row.
- **No frontend yet** - backend only, same scope boundary as every
  EHR-leaning phase before it; `provider/Encounter.jsx` (frontend phase
  J) is the natural next place for this UI, not built here.

Discharge/visit summary is the last remaining phase in this backlog, not
touched here.

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
exact shape since phase 16 itself.

- **`components/PaymentsPanel.jsx`** (new) - consolidates what had been
  two separate, near-identical inline Payments blocks
  (`front-desk/AppointmentDetail.jsx` and `lab-orders/LabOrderDetail.jsx`)
  into one shared component, specifically because the new refund
  affordance would otherwise have needed writing twice - same "share the
  existing page/component" instinct `PatientChart.jsx`/`InvoicePanel.jsx`
  already established. A `totalOwed` prop, when given, renders the
  lab-order page's own "collected of total" summary line (omitted on the
  appointment page, matching its prior behavior exactly - no owed-total
  concept exists there).
  - Each payment row is its own child component (`PaymentRow`) - a plain
    React rules-of-hooks consequence, since each row needs its own
    `usePaymentRefunds` query that can't be called conditionally in a
    `.map()` at the parent's own top level. A "Refund" link expands an
    inline amount/reason form (capped at the payment's own remaining
    un-refunded amount via the input's `max`); once a payment is fully
    refunded, the link disappears rather than allowing a further refund
    the backend would reject anyway.
  - New hooks `usePaymentRefunds`/`useCreateRefund` (`api/queries.js`) -
    owner-agnostic (`GET`/`POST /api/payments/{id}/refund(s)`, phase 16),
    matching the backend's own shape rather than nesting under either
    owner's own route.
- **`InvoicePanel.jsx`** gained a `pdfUrl` prop - when an invoice exists,
  a plain same-origin `<a href={pdfUrl} target="_blank">` replaces the
  "Generate invoice" button; no new download-handling code needed, since
  the browser already carries the session cookie same-origin. Confirmed
  `node-bff`'s existing `relayUpstreamResponse` needed no changes for
  this (already content-type-agnostic, the same code path phase 13's
  provider-signature binary `GET` already proved).
- **A real, pre-existing gap found and fixed along the way, in both
  panels** - `lab-orders/LabOrderDetail.jsx` is shared by `provider` and
  `clinic_admin` alike, but its record-payment form and (now) refund
  button, and `InvoicePanel`'s own "Generate invoice" button, all render
  unconditionally regardless of role - even though
  `LabOrderPaymentController.recordPayment`/`PaymentController.refund`/
  `LabOrderInvoiceController.generateInvoice` are all
  `front_desk`/`clinic_admin`-only. A `provider` viewing a shared lab
  order would see working-looking controls that 403 on submit. Confirmed
  live as `demo-provider`: before the fix, the record-payment form and
  "Generate invoice" button both rendered; after gating both (and the new
  refund button) on `hasRole('front_desk') || hasRole('clinic_admin')`,
  a provider's view of the same order correctly shows the payments list
  and "No invoice generated yet." with no actionable controls at all.
  `front-desk/AppointmentDetail.jsx` is already route-gated to those same
  two roles, so this fix is a no-op there - confirmed unaffected.
- **Live-verified against the real running stack** - as `demo-front-desk`
  on a real pre-existing appointment (payments already recorded and
  partially refunded from phase 16's own verification session): a live
  partial refund through the actual UI (not just curl) updated the
  payment row's "X.XX refunded" note instantly via React Query cache
  invalidation, and the invoice panel's "Download PDF" link resolved to a
  real `%PDF`-prefixed, `application/pdf` response, confirmed by fetching
  the link's own `href` and checking the bytes directly. As `demo-provider`
  on a real reviewed lab order with an existing payment: confirmed the
  payments list and "collected of total" line still render (read access
  intact) while the record-payment form, refund link, and generate
  -invoice button are all correctly absent.

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
backend change without a real need" convention this project already keeps.

- **`layout/Sidebar.jsx`+`layout/Topbar.jsx`** (new) replace `AppShell.jsx`'s
  old brand+icons+user+two-row-nav header (frontend phase A) - a
  collapsible desktop column (icon-only when collapsed, persisted to
  `localStorage` same pattern as `ThemeProvider`) and an off-canvas
  drawer+backdrop on `<lg` screens via a new hamburger button. Nav content
  itself is a straight re-shelving of `AppShell.jsx`'s old `hasRole()`
  branches and `nav.*` i18n keys - no new nav items, just a new container.
  `components/UserMenu.jsx` (new, `IconMenu`-based) replaces the old
  always-visible name+email+Logout block. `layout/PublicShell.jsx` stays a
  top-bar-only shell (3 public links + login never justified a sidebar)
  but restyled to the same sticky/slim header language.
  - **A real pre-existing gap fixed along the way**: `components/
    TimezoneToggle.jsx` (frontend phase N) had been orphaned since the
    header menus were last split back into separate Language/Theme icons
    (`a917e75`) - nothing rendered it anymore, confirmed by grep before
    touching anything. `Topbar.jsx`/`PublicShell.jsx` both gained a third
    `ClockIcon` `IconMenu` wired to it, restoring the browser/clinic's
    /manual timezone-display picker.
- **`components/DataTable.jsx`+`lib/tableUtils.js`** (new) - the one
  generic searchable/sortable table every converted list page adopts:
  column-header click-to-sort (asc/desc, an indicator icon), a search box
  over caller-supplied `(row) => string` accessors, and an optional
  `renderExpanded(row)` - re-hosting this app's existing "toggle a row open
  into an inline edit form" pages (Referrals' expand-to-update,
  Providers' nested hours/login/signature panels) under one shared chevron
  mechanism instead of each page's own bespoke toggle state. Reuses
  `Skeleton`/`ErrorBanner`/`EmptyState` unchanged. `overflow-x-auto`, no
  separate mobile card variant - an ordinary admin-dashboard pattern given
  this app's current column counts.
  - Converted: `front-desk/Appointments.jsx`, `clinic-admin/Providers.jsx`
    (`renderExpanded` hosts the pre-existing hours/login/signature panels
    verbatim), `Rooms.jsx`/`AppointmentTypes.jsx`/`LabRates.jsx`
    (inline-edit-in-place, unchanged logic), `FeePolicies.jsx` (keeps its
    existing clinic-wide-then-per-provider grouping; each group's own tier
    list is now one small `DataTable`), `lab-orders/LabOrders.jsx` (its
    existing status `<select>` filter narrows `rows` before `DataTable`'s
    own search/sort run), `referrals/Referrals.jsx` (`renderExpanded` hosts
    the existing inline update form verbatim - the page's own documented
    "expand-to-update, not a detail route" decision, frontend phase L, is
    unchanged, just re-hosted), `platform-admin/Clinics.jsx`,
    `patient/MyLabOrders.jsx`, root `MyAppointments.jsx`, and
    `front-desk/PatientSearch.jsx` (kept its real server-side `?query=`
    search as the source of truth - `DataTable`'s own client search box is
    omitted there on purpose, not duplicated over the same field).
  - **Deliberately not converted**: `components/PatientChart.jsx`'s nested
    allergy/consent sub-lists - a few rows at most, embedded inside an
    already-collapsible chart panel, not an independent list page.
- **`components/PageContainer.jsx`/`PageHeader.jsx`/`Button.jsx`/`Card.jsx`**
  (new) - replace the ad hoc `mx-auto max-w-*` wrapper div and repeated
  button/card classNames every page had independently declared since phase
  A. Every page under `src/pages/` not converted to `DataTable` above
  still got this lighter-touch wrapper swap (detail pages, the booking
  flow, `TrackAppointment`/`TrackLabOrder`, etc.) - no structural/logic
  changes there, purely the container primitive. Dashboards (one per role)
  were left un-wrapped on purpose - all five are already full-bleed
  grids/chart layouts with no existing width cap, so `PageContainer`
  would've been a no-op; `StatCard` gained a subtle brand-colored top
  accent border and a slightly larger number instead, a shared visual bump
  every dashboard picks up for free.
- **New icons added to `components/icons.jsx`** (hand-authored stroke SVGs,
  same convention as the existing two - still no icon library dependency):
  hamburger/close, chevrons, search, sort indicators, a clock (timezone),
  and one per new sidebar nav concept (dashboard/calendar/users/flask
  /clipboard/building/settings/user-circle/logout).
- **i18n**: new `sidebar.*`/`userMenu.*` keys plus `common.search`/
  `common.noResults`/`common.noResultsHint`, added to both `locales/
  en.json` and `am.json` together - confirmed key-parity by script
  afterward (638 keys each side, the one pre-existing intentional
  English-only key - `myLabOrders.testCount_one` - unchanged). Every new
  table column header reuses an existing translated label from that
  page's own i18n namespace rather than inventing a parallel one (e.g.
  `providersPage.fullName`, `referralsPage.status`).
- **An operational gotcha found rebuilding `node-bff` for this phase, not
  previously documented**: after `docker compose up -d --build
  --force-recreate node-bff`, nginx kept 502ing every request even though
  `node-bff` itself answered `200` directly on `:3000` - nginx resolves
  its upstream's container IP once and doesn't retry DNS on a plain
  container recreate, so a stale IP lingers. Fixed with `docker compose
  restart nginx` immediately after recreating `node-bff` - added as a
  second bullet alongside the existing "rebuild node-bff after editing
  PUBLIC_ROUTES" lesson in "Known gaps" below, since both are the same
  underlying "the browser-facing route is nginx, verify through it" trap.
- **The owed click-through happened 2026-09-27 (later session, extension
  connected)** - real `demo-front-desk` login against
  `front-desk/Appointments.jsx`: the search box live-filtered rows
  client-side (`"Walk In"` narrowed 8+ rows to exactly the matching ones),
  clicking the `Status` column header sorted rows ascending with a visible
  indicator, the desktop sidebar's collapse toggle worked (icon-only rail,
  active item still highlighted, chevron flips), and - once the browser
  window was actually narrowed below the `lg` breakpoint (confirmed via
  `window.innerWidth`, not just requested) - the hamburger button opened a
  real off-canvas drawer with a backdrop, full clinic name (untruncated,
  unlike the collapsed desktop rail), and the same nav/active-item
  treatment. Dark theme and Amharic were both exercised together on this
  same page (table, search box, status pills, sidebar/drawer nav, and the
  language/theme toggles themselves all rendered correctly translated and
  themed) - screenshotted, not just read from source.
  - **One real tool limitation hit, not a product bug**: the automation's
    `resize_window` call didn't take effect immediately (`window.innerWidth`
    stayed at the pre-resize value for several tool calls after requesting
    390x844) and never reached true phone width in this session - it
    settled at 896px after a delay, which was still enough to cross the
    `lg` breakpoint and trigger the hamburger drawer, but a genuinely
    narrow (~390px) phone-width check is still outstanding.
  - `demo-accountant`/`DemoPass123!` was also confirmed via a real browser
    login this session (correct `accountant` role + `demo-clinic` org
    membership via `GET /auth/me`) - its dashboard renders blank, expected
    since Phase 21 (Accounting) has no route/nav for this role yet.

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
already existed and was already live-verified server-side in phases 21/22.

- **Route/nav parity matches the `pharmacist` precedent exactly, not the
  narrower split first guessed while writing it**: every one of the six
  new routes (`/accountant`, `/accountant/accounts`, `/accountant/journal`,
  `/accountant/employees`, `/accountant/payroll`, `/accountant/budgets`)
  uses `roles={['accountant', 'clinic_admin']}` - full route parity, since
  every endpoint behind these pages already grants `clinic_admin` full
  override access on the backend (no ownership check, same as every other
  module). The sidebar nav stays curated the way `pharmacist`'s already
  was: `clinic_admin`'s own sidebar links only to `/accountant/accounts`
  and `/accountant/payroll`, not the full six-item group `accountant`
  itself sees - `clinic_admin` already has its own Dashboard, so it
  doesn't need a second one. This was a real self-caught error, not
  something the user flagged: the first draft split the six routes
  `accountant`-only vs. `accountant`+`clinic_admin` on a guess, then was
  corrected by re-reading the actual pharmacist routes in `App.jsx` before
  any of this was shown to the user.
- **Six new pages** (`pages/accountant/`) - `Dashboard.jsx` (stat cards for
  cash balance/revenue/expense/net-income this month, computed from
  `GET /api/clinic/profit-and-loss` + a trial-balance lookup for the
  `1000` Cash account, plus five link tiles into the other pages; uses a
  plain `"{year}-{month}"` numeric period label rather than inventing a
  month-name i18n namespace this app has never needed before),
  `Accounts.jsx` (chart-of-accounts CRUD, code fixed at creation matching
  `UpdateAccountRequest`'s own shape), `Journal.jsx` (read-only trial
  balance + journal entries, each entry's `renderExpanded` showing its
  balanced debit/credit lines), `Employees.jsx` (create-by-email + salary/
  status edit, mirroring `ProviderController.linkLogin`'s "email must
  already belong to a real login" precedent), `Payroll.jsx` (run-payroll
  form + history; each run's `renderExpanded` lazily fetches its own
  per-employee payments via `usePayrollRun(runId)` - the same "each row
  owns its own query" shape `PaymentsPanel.jsx` established in phase 16,
  reused here since the list endpoint doesn't nest payments), and
  `Budgets.jsx` (the most complex page - one shared year/month selector
  drives three sections at once: budget CRUD with a real hard delete,
  a P&L summary, and the budget-vs-actual table, all genuinely the same
  "for this period" question).
- **A real bug found and fixed live during this phase's own verification**:
  `Budgets.jsx`'s create/update/delete mutations only invalidated the
  `['clinic', 'budgets']` React Query key, never
  `['clinic', 'budget-vs-actual']` - so the Budget vs. Actual table kept
  showing a stale `—` for a just-created or just-edited budget until an
  unrelated refetch happened to occur. Caught live: created a $2,000
  budget for the `1000 Cash` account, watched it appear correctly in the
  budgets list above but stay `—` in the budget-vs-actual table below on
  the same page, in the same render pass. Fixed by having
  `invalidateBudgets` (`api/queries.js`, shared by `useCreateBudget`/
  `useUpdateBudget`/`useDeleteBudget`) invalidate both query keys -
  confirmed live post-fix (after a `--force-recreate node-bff` +
  `nginx` restart) that the same create/delete sequence now updates both
  tables in the same pass with no manual refresh needed.
- **The `/accountant/*` direct-navigation bounce-back investigated last
  session turned out to be transient, not a real bug** - repeated direct
  full-browser navigations to `/accountant/employees`, `/accountant/
  payroll`, and `/accountant/budgets` this session all landed correctly
  with no redirect to `/`, including several right after a
  `--force-recreate node-bff` + `nginx restart` (the one point it did
  reproduce once). `RequireRole.jsx`/`AuthContext.jsx` were read in full
  and are already written correctly (waits for `useAuthMe()`'s own
  `isLoading` before evaluating role access) - the one reproduction is
  most plausibly a genuine transient blip in that narrow post-restart
  window (nginx/node-bff still settling), not a deterministic race. Not
  treated as a known gap requiring a fix; worth re-flagging only if it
  starts reproducing outside a fresh-restart window.
- **Live-verified against the real running stack**, as `demo-accountant`
  (a real login, not simulated): Accounts (create "2000 Accounts Payable"/
  liability, inline-edit its name, deactivate then reactivate - all
  confirmed via the table updating in place); Journal (trial balance and
  journal entries both rendering real data, including the two new payroll
  entries from this session's own payroll runs; expanded a "Payroll
  2026-9" entry and confirmed its balanced Salary Expense/Cash lines);
  Employees (existing "Demo FrontDesk" row rendering correctly via SPA
  navigation); Payroll (ran a genuine new payroll for 2026-10 - "Paid
  1,200.00 across 1 employees" success banner, the new run appearing
  correctly sorted above the existing 2026-09 one; re-running the
  already-paid 2026-09 month correctly surfaced the backend's real 409
  "Payroll for 2026-9 has already been run" message through the UI, not a
  raw error; expanding a run's row lazily fetched and displayed its
  per-employee payment); Budgets (create/edit/delete round-tripped
  correctly, including the cache-invalidation bug found and fixed above;
  P&L summary and budget-vs-actual both showed internally consistent
  numbers matching the trial balance). Dark theme and Amharic were both
  exercised together across every one of the six pages (Dashboard,
  Accounts, Journal - including the header/sidebar/status pills/table
  contents) and confirmed correctly themed and translated via real
  screenshots, not just code review; switched back to Light/English
  afterward. `npm run build` clean; `npm test` (node-bff's own suite,
  unrelated to this frontend work) unaffected.

**Frontend phase S: test infrastructure** (built 2026-09-28) - closes the
"Known gaps" item flagging that every frontend claim in this file was
`npm run build` plus a manual browser walkthrough, never a repeatable
automated check. Scoped with one direct question to the user first - how
much coverage this pass should add, given zero existing test
infrastructure - answered "infrastructure + shared components," the
recommended middle option over "infrastructure only" and "broad page-level
coverage."

- **Vitest, not Jest** - a deliberate, not-asked call: this is already a
  Vite project (`vite.config.js`/`@vitejs/plugin-react` since phase 1), and
  Vitest shares that same config/transform pipeline natively, where Jest
  would need its own separate Babel/transform setup for the identical JSX
  this app already builds with esbuild. `vitest@^2.1.x` specifically, not
  the current `vitest@5` - the latest major requires Vite 6/7/8, and this
  project is pinned to Vite 5 (`^5.4.8`); 2.1.x's own peer range covers
  Vite 5 cleanly, confirmed by a real `npm install` rather than assumed
  from the changelog. `@testing-library/react`/`jest-dom`/`user-event` +
  `jsdom` (the DOM environment vitest needs to actually render components,
  as opposed to running in a plain Node environment) round out the
  toolchain.
- **`vitest.config.js`, a separate file from `vite.config.js`** - the dev
  -server proxy config in `vite.config.js` (forwarding `/api`/`/auth` to
  node-bff on `:3000`) has nothing to do with running tests, so keeping
  them apart means neither file needs a test-vs-dev conditional branch.
  `src/test/setup.js` imports `@testing-library/jest-dom/vitest` (matcher
  extensions) and, importantly, the same `i18n/index.js` `main.jsx` itself
  imports - every test gets a working `useTranslation()` against the
  app's **real** `en.json`/`am.json` locale files for free, so a
  component test can assert on real translated copy ("Checked Out") rather
  than mocking the whole i18n layer or asserting on raw keys.
- **What got tested, and why these four and not others**: `lib/
  tableUtils.js` (`matchesQuery`/`compareValues` - pure functions, the
  actual search/sort logic every converted list page in the phase-Q
  redesign depends on, easiest to test exhaustively in isolation);
  `components/DataTable.jsx` (the shared table itself - search filtering,
  ascending/descending/column-switch sort, loading/error/empty states,
  the `renderExpanded` toggle - rendered and interacted with via RTL +
  `user-event`, not just unit-testing its internals); `components/
  StatusPill.jsx` (real translated labels for both status vocabularies,
  plus the raw-underscore-split fallback for an unmapped status);
  `auth/RequireRole.jsx` (the one role-gating component every staff route
  in this app passes through - `useAuth` mocked directly via `vi.mock`
  rather than exercising the real `AuthProvider`'s own `GET /auth/me`
  fetch, so each case can drive `isLoading`/`authenticated`/`hasRole`
  independently: loading renders nothing, unauthenticated does a real
  `window.location.href` redirect not a client route change,
  authenticated-but-wrong-role does a client-side redirect to `/`,
  authenticated-with-role or -any-of-roles renders children); two
  `api/queries.js` hooks - deliberately not "a couple of arbitrary ones"
  but the two written earlier this same session: the budget mutations'
  cache-invalidation fix (a genuine regression test for the real bug found
  live earlier today - both query keys, not just one, get invalidated on
  create/update/delete) and `useCancelSeries` (invalidates every
  individual appointment/payments query named in the response's own
  `cancelled[]` list, not just the tenant-wide list).
- **`npm test` (`vitest run`) added to `package.json`**, and wired into
  CI - the `frontend` job now runs `npm test` before `npm run build`
  (renamed `frontend (vitest + vite build)`), so a real test failure
  blocks the same way a build failure already does. Confirmed via a full
  clean-install simulation of the exact CI sequence (`rm -rf node_modules
  && npm ci && npm test && npm run build`), not just run once against an
  already-populated `node_modules`.
- **Live-verified is the wrong frame here - these are real automated
  tests, confirmed by actually running them**, not manual browser
  clicking: `34/34` passing locally (5 files) both before and after a
  clean `npm ci`.

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
- `DispenseServiceTest` (phase 20, pure Mockito, genuinely run locally -
  6/6 passing) - successful dispense decrements the batch and saves a
  record, exact-zero depletion flips the batch to `depleted`, insufficient
  stock rejected before anything is saved, exceeding a prescription's own
  prescribed total rejected, a stock batch belonging to a different
  medication rejected, an unknown prescription 404s.
  `MedicationControllerIntegrationTest`/`DispenseControllerIntegrationTest`
  (CRUD, invalid form/status 400, role gate, cross-tenant 404, the queue
  correctly excluding an already-fully-dispensed prescription,
  insufficient-stock 409) plus one new `TenantIsolationIntegrationTest`
  case. Same Testcontainers wall as every other integration test on this
  machine - confirmed via a clean `mvn clean test-compile` and by checking
  every other, unrelated test class failed identically in the same run.
- `JournalServiceTest` (phase 21, pure Mockito, genuinely run locally -
  4/4 passing) - a payment posts a balanced debit-Cash/credit-Revenue
  entry with the correct source type/posted-by; a lab-order payment uses
  the `lab_order_payment` source type; a refund posts debit-Refunds
  -and-Allowances/credit-Cash; a missing seeded account throws rather than
  posting an unbalanced entry. `PaymentServiceTest`/`RefundServiceTest`
  both updated for `JournalService`'s new constructor param, confirmed
  still green in the same run. `AccountControllerIntegrationTest` (starter
  -account seeding, full CRUD round-trip, invalid type/status 400,
  duplicate-code 409, role gate, cross-tenant 404) +
  `JournalControllerIntegrationTest` (role gate, per-tenant scoping, and a
  genuine end-to-end case recording a real payment through
  `AppointmentPaymentController` and asserting the resulting journal
  entry/trial-balance) plus one new `TenantIsolationIntegrationTest` case.
  Same Testcontainers wall as every other integration test on this
  machine - a full `mvn test` run showed `Tests run: 289` (up from 275),
  every failure identical (`Could not find a valid Docker environment`),
  not a regression from this phase's own code.
- `PayrollServiceTest` (phase 22, pure Mockito, genuinely run locally -
  3/3 passing) - running payroll posts one journal entry per active
  employee with the right amount/description/source and saves a payslip
  for each; re-running an already-run month is rejected before touching
  employees or the ledger at all (`verify(..., never())`); zero active
  employees is rejected before creating a run.
  `EmployeeControllerIntegrationTest` (create-by-email snapshotting the
  real AppUser's name/email, unknown-email 404, duplicate-appUser 409,
  update salary/status + invalid-status 400, role gate, cross-tenant 404)
  + `PayrollControllerIntegrationTest` (a genuine end-to-end case running
  payroll through the real controller and asserting the resulting
  trial-balance numbers, duplicate-month 409, zero-employees 400, role
  gate, cross-tenant 404) + `BudgetControllerIntegrationTest` (full CRUD +
  hard delete, duplicate-period 409, unknown-account 404, role gate,
  cross-tenant 404) + `FinanceReportControllerIntegrationTest` (P&L scoped
  to the queried month only - checked against both the real current month
  and a different month; budget-vs-actual variance computed correctly
  while omitting an unbudgeted/untouched account; role gate) plus one new
  `TenantIsolationIntegrationTest` case. Same Testcontainers wall as every
  other integration test on this machine - a full `mvn test` run showed
  `Tests run: 312` (up from 289), every failure identical (`Could not
  find a valid Docker environment`), not a regression from this phase's
  own code.
- `CancellationIntegrationTest` (phase 23) gained three cases -
  cancel-series through a real 3-occurrence series cancelling all three and
  fee-charging each one separately, rejected on a non-series appointment
  (400), cross-tenant 404. No new `TenantIsolationIntegrationTest` case -
  same reasoning the plain single-cancel endpoint already uses (its own
  cross-tenant check lives inline here too). Local pure-unit run (`69/69`)
  confirmed unaffected; the three new cases themselves were confirmed
  correct via a full live browser verification of the exact same code
  path against a real Postgres, not a local Testcontainers run (still
  Windows-npipe-blocked).
- `ImmunizationControllerIntegrationTest` (phase 24, new) - create+list+
  update round trip, missing-required-field 400, all three write-gated
  roles (`provider`/`front_desk`/`clinic_admin`) succeeding while a
  `patient` token gets 403, cross-tenant 404, update-for-the-wrong
  -`patientId` 404. `TenantIsolationIntegrationTest` gained one more case.
  Same Testcontainers wall as every other integration test on this
  machine - `mvn test` showed `Tests run: 321, Errors: 252` (same 69
  pure-unit tests passing, unaffected), confirmed correct instead via a
  real end-to-end browser session (create/list/update all confirmed in
  Postgres and the PHI access log).
- `EncounterIntegrationTest` extended again (phase 25) - the 18 new exam
  -finding fields round-trip through the existing upsert/GET endpoints
  (`examFindingsRoundTripThroughUpsertAndGet`, new), and
  `signingLocksTheEncounterAndItsPrescriptions` gained an assertion that
  an exam-finding-only edit is rejected post-sign too. No new test class
  - a field addition to an existing resource, same precedent phase 11's
  `icd10Codes` addition already set. `mvn test` showed `Tests run: 322,
  Errors: 253` (up from 321/252 - exactly the one new test method), same
  Testcontainers wall, 69 pure-unit tests unaffected. The Testcontainers
  -only assertions themselves are confirmed correct by the live browser
  verification in "Phase 25" above (a real signed encounter genuinely
  409ing, a real unsigned one genuinely accepting and persisting all 9
  systems), not by a local test run.

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
  phase R: accountant/finance UI" above.
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
