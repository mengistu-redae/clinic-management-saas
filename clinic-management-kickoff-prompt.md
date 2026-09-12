# Clinic Management SaaS — Claude Code kickoff prompt

Paste everything below the line into Claude Code in a fresh, empty repo. It is
modeled on the architecture and working conventions of the bus-ticketing SaaS:
`nginx → node-bff (OIDC/session) → spring-boot-api (JWT, tenant-aware) →
postgres/redis`, Keycloak Organizations for tenancy, phased delivery, and a
`CLAUDE.md` kept as running project memory.

Before you send it, decide two things and add the answers to the prompt (or be
ready to answer them in the first plan-mode exchange):

1. **Patient scope** — are patients strictly per-clinic, or a shared
   platform-wide directory that clinics link to?
2. **PHI-access audit** — does v1 build a real audit log of who viewed/changed
   patient clinical data, or defer it?
3. **(Lab module only)** — does v1 ingest results from a real external lab
   (HL7/FHIR), or is result entry manual staff input only?

---

**Build a multi-tenant clinic management SaaS.**

Tenants are **clinics**. Each clinic manages its providers, rooms, and
appointment calendar. Patients book through a patient portal; front-desk staff
book / reschedule / check-in walk-in patients at the counter; providers
document visits. Later, a lab orders module tracks diagnostics from order to
resulted-and-reviewed.

## Architecture — mirror this exactly

```
browser → nginx → node-bff (session, OIDC, PKCE) → spring-boot-api (JWT, tenant-aware) → postgres, redis
                ↘ keycloak (login + admin console only)
```

- **`spring-boot-api/`** — Java 21 / Spring Boot 3.3, Maven. Stateless,
  validates bearer JWTs, never talks to the browser directly. Flyway
  migrations only (`V1__init.sql`, …), `ddl-auto: validate` — schema changes
  always go through a new migration.
- **`node-bff/`** — Node ≥20 / Express, npm. The only thing the browser talks
  to. Runs OAuth2 Authorization Code + PKCE (S256) against Keycloak, keeps
  tokens in a Redis-backed server-side session, forwards them to the API as a
  Bearer header. The browser only ever holds a session cookie, never a token.
- **keycloak** — one realm; realm roles `platform_admin`, `clinic_admin`,
  `provider`, `front_desk`, `patient`; the Organizations feature groups clinic
  staff by tenant. Config under `infra/keycloak/`. The org claim only appears
  on a token if the client's authorization request explicitly includes the
  `organization` scope — verify the claim shape against a real decoded token,
  don't guess.
- **postgres / redis** — primary datastore, and appointment-slot locking +
  session store.
- **nginx** — single entry point on `:80` for local dev, under `infra/nginx/`.
- `docker compose up --build` runs the whole stack. First boot is slow
  (Keycloak realm import); the BFF should retry OIDC discovery with backoff so
  services don't need to win a startup race.

Each service has its own build tooling and its own `start-local.ps1` for
running against local infra instead of `docker compose up`.

Service URLs (via docker compose): app through nginx on `:80`, node-bff
directly on `:3000`, Keycloak admin console on `:8080`, spring-boot-api on
`:8081` for debugging.

## Tenancy model

- `tenant_id` on `clinics` is the internal tenant key, deliberately **not**
  the same as `clinics.keycloak_org_id` — the app must not be hard-wired to
  Keycloak's id format.
- There is **no** blanket Hibernate multi-tenant filter. Instead:
  - A `TenantContextFilter` runs once per request (after JWT auth), reads the
    org claim off a staff token, resolves it to a `clinics.id` via
    `ClinicRepository.findByKeycloakOrgId`, and stashes it in a request-scoped
    `TenantContext`. It is **also the clinic-deactivation enforcement point**:
    if the resolved clinic's `status != "active"`, the filter writes a plain
    `403 "Clinic account is deactivated"` and stops the chain. Only staff
    tokens carry an org claim, so `patient` / `platform_admin` requests are
    untouched.
  - Every staff-scoped repository method takes the tenant id **explicitly** as
    a parameter (`findByTenantIdAndId(...)`, `findAllByTenantId(...)`), never
    an implicit read of `TenantContext` deep inside a shared base repository.
    You can tell whether an endpoint is tenant-scoped or cross-tenant just by
    reading its method signature. Write paths that reference another resource
    by id validate it against the caller's tenant too.
  - `TenantContext` is `null` for `patient` tokens (not a member of any
    Organization) and for `platform_admin` tokens (acting across every
    tenant). Code that legitimately needs a tenant calls
    `TenantContext.require()`, which throws instead of silently proceeding
    with `null`.
  - Every `/api/**` endpoint carries a `@PreAuthorize` (method security is
    explicitly enabled — Spring Security 6 does **not** turn it on
    automatically) except the deliberately-public paths listed in
    `SecurityConfig`'s `permitAll()`.
- A mirror `tenant_id` on the local user row is written once at provision time
  and **never consulted for authorization** — the per-request token is the
  source of truth, so moving a user between Keycloak orgs takes effect
  immediately.
- One consolidated `TenantIsolationIntegrationTest`: for every staff-scoped
  resource, clinic A seeds it and clinic B's staff is refused (404/403) on
  every read / write / action path — plus the deactivation lockout.

**Marketplace-style exception:** the appointment-availability search (a patient
browsing open slots across clinics, if that is in scope) is intentionally
cross-tenant and has no tenant filter; staff-facing calendar management uses
the tenant-scoped finders. Keep the two clearly separated and both wired up.

## Auth / BFF details

- `node-bff` does OIDC discovery against the internal Keycloak URL
  (container-to-container) but rewrites `authorization_endpoint` /
  `end_session_endpoint` to the browser-reachable public URL before handing
  URLs to the browser.
- Sessions live in Redis (`connect-redis`), not memory, so the BFF can restart
  or scale to more than one instance without logging everyone out. The token
  set round-trips through Redis as plain JSON — rewrap it before calling any
  library method that expects the real instance.
- Everything under `/api` requires a session **except** the paths in a
  `PUBLIC_ROUTES` table (status-tracking endpoints, and any public
  availability search), which are mounted ahead of the session gate. The BFF
  refreshes an expired access token first, then forwards the request as-is to
  `spring-boot-api` with the token attached as a Bearer header. An
  `invalid_grant` on refresh destroys the session and returns the same `401`
  shape as "no session", so the frontend's central 401 handling redirects
  cleanly to login.
- `express.json()` sets `req.body` to `{}` for a request with no body and no
  `Content-Type` — only forward a body downstream (always with an explicit
  `Content-Type: application/json`) when it actually has content.
- Add `.requestMatchers("/error").permitAll()` — otherwise a `@Valid`
  bean-validation failure gets rewritten by the servlet `/error` re-dispatch
  into a misleading `403 insufficient_scope` instead of a `400`.

## Domain (v1)

- **Patients** — demographics, contact, optional national ID and insurance
  member id. (Per-clinic vs shared directory: pin this in plan mode.)
- **Providers** — specialty, weekly working hours, assigned room.
- **Rooms** — per clinic, referenced by providers and by roomed check-ins.
- **Appointment types** — per-clinic, each with a duration and a price (e.g.
  "New patient / 30 min", "Follow-up / 15 min").
- **Slots** — generated from a provider's working hours + the appointment
  type's duration (same idea as seats generated from a bus layout at trip
  creation). `slot_class` exists and is populated (`standard` default) but
  unused for pricing — reserved for a future `slot_class → multiplier` table,
  no migration needed to add it later.
- **Booking flow** — pick a slot → acquire a short-lived Redis lock on it
  (`SlotLockService`, TTL configurable) → lock acquired (write the
  appointment) or already locked (`409 SlotConflictException`). The Redis lock
  is the **fast path** (a quick 409 across app instances). The **correctness
  backstop** is a `@Lock(PESSIMISTIC_WRITE)` (`SELECT … FOR UPDATE`) finder on
  the slot with a `status = 'open'` re-check *inside* the transaction, so a
  double-book is impossible even if the Redis lock is bypassed. The DB write
  lives in a **separate `@Transactional` bean** from the orchestrating service
  — calling a `@Transactional` method on `this` skips the proxy. Idempotency:
  a `(tenant_id, idempotency_key)` unique constraint means a retried request
  returns the original appointment rather than re-locking; checked before any
  locking.
- **Channels** — `patient_portal` (patient JWT, no org) and `front_desk`
  (staff JWT, org set — enforced to match the appointment's own clinic via a
  `TenantMismatchException` the controller maps to `403`). The channel is
  decided from the JWT role, never from anything the client sends.
- **Cancel / reschedule**
  - Two cancel endpoints, kept separate because their lookups are scoped
    differently: `POST /api/appointments/{id}/cancel` (`front_desk` /
    `clinic_admin`, tenant-scoped) and `POST /api/my-appointments/{id}/cancel`
    (`patient`, ownership-scoped via the patient's own user id). A different
    patient's or clinic's appointment 404s identically — ownership/tenant
    checks never distinguish "exists but not yours" from "doesn't exist".
    Both delegate to a shared private `applyCancellation(...)` inside
    whichever public method's `@Transactional` boundary.
  - **No-show / late-cancel fee policy** (`fee_policies`): each clinic
    configures an ordered list of tiers keyed by hours-before-appointment
    (`[{cutoff_hours: 24, fee_percent: 0}, {cutoff_hours: 2, fee_percent: 50},
    {cutoff_hours: 0, fee_percent: 100}]`). A row with a specific
    `provider_id` overrides the clinic-wide default (`provider_id NULL`).
    Sort tiers highest-cutoff-first, apply the first tier the notice period
    clears. **If the clinic hasn't configured a policy, the fee is zero** —
    a missing policy is a config gap, not grounds to block the cancellation.
  - **Reschedule** (`POST /api/appointments/{id}/reschedule` and
    `/api/my-appointments/{id}/reschedule`) carries patient name / phone / ID
    over unchanged (immutability), moves only the slot (possibly to a
    different provider within the same clinic — a different clinic is a new
    appointment, `TenantMismatchException`), adds a flat mutation fee
    (per-channel amounts, configurable), and writes an audit row. Blocked
    with a `409` if fewer than a configurable minimum notice remains — the
    caller falls back to cancellation, the endpoint doesn't do it for them.
    The seat/slot move: insert the new row first, repoint any dependents,
    then delete the old row and free its slot.
- **Check-in state machine** — `booked → checked_in → roomed → with_provider
  → checked_out`, plus `no_show` / `cancelled`. Each transition is its own
  endpoint (`POST .../check-in | room | start | check-out | no-show`),
  idempotent when re-called (returns current state), `409` when out of order.
  Optional `presentedIdNumber` compared against the patient's ID on file at
  check-in → `IdentityMismatchException` (409) on mismatch. "Too late to
  check in" is a **live `Instant.now()` vs. appointment-time comparison at
  call time**, never a stored status a scheduler could get wrong. A separate
  `@Scheduled` job flips past appointments to `no_show` purely so they leave
  the active worklist — a polling job's lag must never decide a real-time
  gate check.
- **Encounters** — one clinical note per appointment (provider-authored:
  chief complaint, assessment, plan) plus a simple prescription list. Keep
  minimal in v1; consider deferring entirely to its own scoped session.
- **Billing** — one invoice per appointment with a `subtotal_amount` /
  `tax_amount` / `total_amount` split (`total = subtotal + tax`). A
  `payments` ledger (cash / card / mobile-money / insurance), optional
  `transaction_id`. The `payments` table exists from V1 but recording a
  payment is a deliberate, separate staff action — creating an appointment
  doesn't create or require one, and cancelling doesn't auto-void one.
  `GET/POST/PATCH /api/appointments/{id}/payments(/{id})`, no `DELETE` (a
  payment is a financial fact).
- **Reference / number generation** — each appointment gets a human
  `appointment_ref` (bare 6-char code) and optionally a longer per-clinic
  reference (clinic initials + year + sequence), both checked for uniqueness
  with a bounded retry, not trusted from randomness alone.
- **Public endpoint** — `GET /api/appointments/track/{ref}?phone=` —
  two-factor (ref + phone must match either the booking contact or a
  patient's own phone on that appointment); a mismatch and an unknown ref
  `404` identically. Response is a deliberately narrow `AppointmentTrackingView`:
  status, timestamps, clinic/provider/time only — never clinical detail, ID
  numbers, or money beyond the total. Added to `SecurityConfig` `permitAll()`
  **ahead of** the blanket `/api/**` `.authenticated()` rule (matchers apply
  in order), with a matching carve-out in the BFF's `PUBLIC_ROUTES` table
  ahead of the session gate; `forwardToApi` attaches no Bearer header when
  there's no session.
- **Guest booking** (optional for v1, same pattern as the public track
  endpoint) — `POST /api/appointments/guest` with a required `contactPhone`
  and optional `contactEmail`, `channel = "guest"`, no `Jwt` parameter,
  fully `permitAll()`'d. `contactEmail` is not persisted — it only flows
  through as the transient notification recipient. Skip the notification
  write entirely when there's no recipient (the `notifications.recipient`
  column is `NOT NULL`).
- **Per-clinic settings** — one lazy singleton row per tenant (`tenant_id`
  PK, created on first `PATCH`, `GET` works with no row and returns pure
  defaults). `GET /api/clinic/settings` returns `{overrides, effective,
  defaults}`. `PATCH` is a **full replace of the override set** — a `null`
  field means "revert to the platform default". Overridable: tax rate,
  no-show / reschedule fees, reschedule minimum notice, appointment reminder
  lead time. Plus contact info (support phone / email / address, website)
  and branding (logo URL, brand / accent colours, display name, footer
  note). A single `ClinicSettingsService.resolve(tenantId)` is the one merge
  point — it holds the `@Value` platform defaults and coalesces each
  nullable override; `resolve(null)` is safe and returns pure defaults. All
  consumers read `resolve(...)`, never the `@Value` directly.
- **Branding** — a **separate** `PATCH /api/clinic/branding` endpoint writing
  a disjoint column set on the same settings row (a shared full-replace
  request would have one tab wipe the other's fields). `GET` is
  `clinic_admin` + `front_desk` + `provider` (staff need it to theme the
  workspace); `PATCH` is `clinic_admin` only. The frontend applies the
  Bustix/default fallback — the platform stores no fallback value. The staff
  workspace recolours globally via CSS custom properties; a patient's own
  appointment card / tracking result is themed to *that* clinic's colour
  inline, scoped to the one card, not globally.
- **Platform admin** — `GET/POST/PATCH/DELETE /api/platform/clinics(/{id})`
  (`platform_admin`). `POST` creates the Keycloak Organization via the Admin
  REST API first (a plain `RestClient`, **not** the
  `keycloak-admin-client` library — its transitive RESTEasy/Jackson versions
  risk classpath conflicts), then inserts the local `clinics` row; no
  compensating rollback to Keycloak if the DB insert fails (documented
  caveat). `PATCH` only allows editing display fields — `keycloak_org_id`
  (the org alias) is fixed at creation, since `TenantContextFilter` matches
  it against a staff token's org claim. `DELETE` soft-deactivates via the
  `status` column, never a row delete (a clinic has providers / appointments
  / invoices underneath it). **Reactivation** is direct-SQL only in v1
  (`status` not in the update request) — note this as a follow-up.
- **Notifications** — an outbox table + a polling worker (`@Scheduled`,
  same shape as any other outbox poller). The email sender is a
  `LoggingEmailSender` stub — the outbox / retry / status-tracking machinery
  around it doesn't change when a real sender is swapped in. Guests
  (`customerUserId == null`) are skipped on every notification write.

## Lab Orders Module (add after v1 works — scope it as its own session)

A lab order is a set of tests ordered for a patient, tracked from order
through resulted-and-reviewed. Model it on the same patterns as everything
above. Scope it via multiple-choice questions first — especially whether v1
ingests results from a real external lab (HL7/FHIR) or result entry is manual
staff input only, and whether result values are ever shown to staff before
provider review.

- **Ordering is clinician-driven** (`provider` / `clinic_admin`,
  tenant-scoped). A lab order is created against an encounter (or standalone
  for a patient). No patient self-service *creation* in v1 (there's a request
  flow below).
  - `POST /api/lab-orders` — `CreateLabOrderRequest`: `patientId` (required),
    optional `encounterId` (must be the same patient →
    `EncounterPatientMismatchException`), `orderingProviderId`, clinical
    `notes`, `priority` (`routine` / `urgent` / `stat`), and
    `tests: List<TestItem>` (`@NotEmpty`) — each item a `testCode` /
    `testName` / `specimenType` / optional `notes`.
  - Line items live in their own `lab_order_tests` table (each its own row
    and price), never flat fields on the order — same as multi-item cargo
    waybills. Since this codebase maps no cross-entity JPA relations (always
    plain UUID FK columns + explicit repository queries), every read/write
    endpoint returns a `LabOrderWithTests` wrapper `{order, tests}`, not a
    bare entity.
- **Status is a manual, staff-driven state machine**, not scheduler-inferred:
  `ordered → specimen_collected → in_transit → resulted → reviewed` (or
  `cancelled`, pre-collection only). Each transition is its own endpoint
  (`POST .../collect-specimen | send | result | review | cancel`) mirroring
  the check-in machine — re-calling a transition already reached is
  idempotent; calling one out of order throws
  `InvalidLabOrderStatusException` (409).
  - `collect-specimen` (`CollectSpecimenRequest{presentedIdNumber}`) checks
    the presented ID against the patient's ID on file —
    `IdentityMismatchException` (409) on mismatch or nothing on file to
    check against. Keep it as its own exception class even though the check
    is shaped like the check-in one.
  - `result` (`ResultLabOrderRequest`) attaches a per-test result set
    (value, unit, reference range, abnormal flag) to the `lab_order_tests`
    rows and stamps `resultedAt` / `resultedBy`.
  - `review` records the ordering provider's sign-off (`reviewedAt` /
    `reviewedBy`) and fires a `lab_result_ready` outbox notification
    (skipped when the patient has no contact on file).
- **Pricing** (`lab_test_rates`) — one row per `(tenant_id, test_code)`,
  with an optional override dimension (per-provider or per-appointment-type)
  only if it earns its place; keep it simple. Configured via
  `GET/POST/PATCH/DELETE /api/clinic/lab-rates(/{id})` (`clinic_admin`),
  real delete not soft-deactivate (it's config, not a financial record).
  **A missing rate blocks order creation** (`400
  NoLabRateConfiguredException`) rather than defaulting to free — same
  reasoning as a missing freight charge, opposite of the "no fee policy =
  zero" fallback. `base_charge` / `collection_fee` / `total_cost` are
  computed once at order creation (or at a pre-collection test-list
  correction) and **snapshotted** onto the order row — a later rate change
  never re-prices an issued order.
- **Restricted tests** — a platform-wide config list,
  `clinic.lab.restricted-tests` in `application.yml`, checked against every
  `testCode` at creation and on any `PATCH` that changes the test list. Use
  for tests needing explicit consent or genetic-counseling gating. Bound via
  a `@ConfigurationProperties` class, **not** `@Value` — a YAML sequence has
  no single property at the bare key (Boot stores it as indexed
  `restricted-tests[0]`, `[1]`, …). Each entry compiles as a
  case-insensitive regex, falling back to a literal-substring match if it
  isn't valid regex. `RestrictedTestException` → `400` unless the request
  carries a `consentAcknowledged` flag.
- **Immutability principle** — every clinical field on the order and its
  test items (`testCode`, `specimenType`, `patientId`,
  `orderingProviderId`, `notes`) is only `PATCH`-editable while
  `status = "ordered"`; once `specimen_collected`, editing any of them
  throws `InvalidLabOrderStatusException` (409). Result fields and
  `paymentStatus` are exempt and editable later. On `PATCH`, `tests` is
  nullable-and-replace-the-whole-set (null = "don't touch", an explicit
  empty list is rejected → `InvalidLabOrderTestsException`, 400).
- **Cancellation** reuses the same fee calculator the appointment
  cancel/reschedule flow uses — make it generic
  (`calculate(tenantId, providerId, totalCost, dueAt)`, not
  appointment-specific) so `POST /api/lab-orders/{id}/cancel` (pre-collection
  only) resolves any fee against the clinic's existing `fee_policies`, no
  new mechanism. Parallel `lab_order_cancellations` audit table.
- **Payments** — reuse the real `payments` table: a nullable
  `payments.lab_order_id`, `appointment_id` relaxed to nullable, and a
  `chk_payments_exactly_one_owner` CHECK
  (`(appointment_id IS NOT NULL) <> (lab_order_id IS NOT NULL)`) enforced at
  the DB level. A **separate** `LabOrderPaymentController` at
  `/api/lab-orders/{orderId}/payments(/{id})` (the appointment payment
  controller's base path is hard-coded to a different nesting), reusing the
  existing payment request / entity shapes. No `DELETE`.
- **One public, unauthenticated endpoint** — `GET
  /api/lab-orders/track/{orderRef}?phone=` — two-factor (ref + phone must
  match the patient's phone on that order), any mismatch or unknown ref
  `404`s identically. Response is a deliberately narrow
  `LabOrderTrackingView`: **status and timestamps only — never result
  values, reference ranges, abnormal flags, ID numbers, or provider
  names.** Same `SecurityConfig` `permitAll()`-ahead-of-`/api/**` and BFF
  `PUBLIC_ROUTES` wiring as the appointment track endpoint.
- **Patient-initiated requests** — a two-phase flow (a specimen has to be
  physically collected at the clinic, so a patient can't issue a priced
  order themselves). `POST /api/my-lab-orders` (`patient`,
  `CreateLabRequestRequest` — a `clinicId` picker, requested tests by name,
  no pricing, no encounter) creates an order with `status = "requested"`,
  routed to that one clinic so `tenant_id` is set from creation. Staff
  review via `GET /api/lab-orders/requests`
  (`findAllByTenantIdAndStatus(tenantId, "requested")`) and turn one into a
  real order via `POST /api/lab-orders/{id}/confirm-and-order` (assigns the
  ordering provider and encounter, optionally re-selects the tests after
  clinical review, runs the same unchanged pricing, flips
  `requested → ordered`). Idempotent past `requested` (409 via a
  `RequestNotIssuableException` on anything out of order). `GET
  /api/my-lab-orders` unions two ownership paths: orders on an encounter the
  patient owns, and orders they requested directly.
- **Tests** — `LabOrderIntegrationTest` (multi-test create/price,
  empty-tests 400, test-list replace `PATCH` pre/post-collection,
  restricted-test 400), `LabOrderPaymentIntegrationTest` (cross-tenant 404,
  CHECK-constraint violation via a raw JDBC insert),
  `PatientLabRequestIntegrationTest` (request → confirm-and-order round
  trip, role checks, idempotent re-confirm, ID-mismatch 409, cross-patient
  404). Extend `TenantIsolationIntegrationTest` with every lab-order path.

## Frontend

`node-bff/frontend/` — a React + Vite + Tailwind SPA with its own
`package.json` / lockfile / `npm install` / `npm run build`, **not** an npm
workspace of `node-bff` (which gets zero new dependencies). Nested under
`node-bff/` specifically so `docker-compose.yml`'s existing `node-bff` build
context covers it — a Dockerfile can't `COPY` outside its context. The
Dockerfile becomes multi-stage: a `frontend-build` stage runs
`npm ci && npm run build`, its `dist/` is `COPY --from=`'d into the runtime
stage as `./public`; `src/index.js` serves `public/` and falls back to
`public/index.html` for any GET that isn't `/health`, `/auth/*`, or `/api/*`.

- **API client** — `@tanstack/react-query` over a thin `apiFetch()` wrapper.
  No generated typed client, no TypeScript (match the rest of the repo).
  Error bodies are read as text (every `@ExceptionHandler` returns a plain
  `String`, not a JSON envelope). A `401` from any `/api/*` call triggers a
  central full redirect to `/auth/login`.
- **Auth in the SPA** — role info for UX-only nav/route gating from
  `GET /auth/me`. The OIDC **ID** token carries no `realm_access.roles` on
  this realm — only the **access** token does; decode its payload in the BFF
  callback (no signature check needed, the OAuth exchange already
  established authenticity) and merge `realm_access.roles` into the session
  user. Real authorization stays entirely server-side (`@PreAuthorize`).
- **Design system** — semantic Tailwind tokens (`brand`, `accent`,
  `success` / `danger` / `warning`), the brand/accent ones as
  `rgb(var(--brand) / <alpha-value>)` channel form so branding overrides
  work. A shared `SlotGrid` / calendar view groups a provider's flat slot
  list into day columns and time rows.
- **Pages by role** — patient portal (search / availability → pick slot →
  book → confirmation → self-cancel/reschedule → My Appointments → My Lab
  Orders → public track pages); front-desk (search → book → counter
  check-in worklist → appointment detail with staff cancel + payment
  recording); provider (today's schedule → encounter documentation → lab
  ordering); clinic-admin (providers / rooms / appointment types / fee
  policies / lab rates / settings hub with tabs / branding); platform-admin
  (clinic onboarding — the one create form that reaches out to Keycloak,
  slower and failing in more ways). A `RequireRole` supporting both a
  single `role` and a `roles` array (the `/lab` tree is reachable by
  `provider` and `clinic_admin` alike).
- **Dashboards** — a role-appropriate landing page per staff role
  (aggregate stats endpoints, each `@PreAuthorize`'d and scoped like the
  rest of the API); patients fall through to the portal home. Charts, if
  any, lazy-loaded so a patient's first load isn't paying for them.

## Testing

- Focused unit tests for the pure logic: slot generation from working hours,
  fee-tier selection, the tenant filter's claim extraction.
- Integration tests via `@SpringBootTest` / `MockMvc` against Postgres +
  Redis in **Testcontainers** (images matching docker-compose), driving
  requests through the *real* filter chain — Spring Security,
  `TenantContextFilter`, `@PreAuthorize` all run for real. Only JWT issuance
  is faked, via Spring Security Test's `jwt()` request post-processor
  pulling authorities from the app's real `JwtAuthenticationConverter` bean
  (don't re-implement the `ROLE_` mapping). Use a Testcontainers singleton
  so the DB survives the whole suite.
- Cover: availability search, provider/room/appointment-type CRUD, the
  booking flow (both channels, idempotency, slot conflict, cross-tenant
  front-desk 403), cancellation (both endpoints, fee calculation,
  already-cancelled 409, cross-tenant/ownership 404), the check-in machine
  (match / mismatch / too-late), reschedule (fee, notice gate, cross-clinic
  block), per-clinic settings (override merge, agent 403), and the full
  `TenantIsolationIntegrationTest`.
- Set up GitHub Actions CI (`mvn verify` + node-bff tests + frontend build)
  from early on.

## Known gaps to track in CLAUDE.md (don't pretend these are done)

- `payments` is not auto-wired into the booking/cancellation flow — recording
  one is a separate deliberate staff action.
- Email is a `LoggingEmailSender` stub.
- Clinic reactivation is direct-SQL only (no `platform_admin` endpoint).
- A deactivated clinic's availability may still appear in a cross-clinic
  search — only the final booking call and staff API access are blocked
  (mirror the bus app's intentional residual, or decide otherwise).
- Whatever the PHI-audit and external-lab-integration decisions defer.

## How I want you to work

1. **Start in plan mode.** Propose the repo layout, the first Flyway
   migration, the auth/BFF skeleton, and a phase plan — expect: infra +
   auth skeleton → patient booking flow → front-desk/counter + check-in →
   provider clinical flow → clinic-admin config + settings/branding →
   platform-admin onboarding → (later, separately) lab orders module. Do
   **not** write code until I approve the plan.
2. Maintain a `CLAUDE.md` from the first commit — "what this is", the
   architecture diagram, the commands, the tenancy model, and a running log
   of every design decision and *why*. Update it every session; convert
   relative dates to absolute.
3. For every non-obvious design fork — patient scope, PHI audit, recurring
   appointments, multi-provider appointments, insurance claim handling,
   external lab integration, how reschedule maps to a different provider —
   **ask me a multiple-choice question before implementing.** Don't assume.
4. Once a convention is established, keep it consistent: no DTO layer
   (controllers return entities / purpose-built read shapes), explicit
   tenant parameters on every scoped repository method, the DB write in a
   split `@Transactional` bean, `@PreAuthorize` on every non-public
   endpoint, "exists but not yours" always reads as `404`, public endpoints
   wired ahead of both `SecurityConfig`'s blanket rule and the BFF session
   gate.
5. Verify each feature **live against the running stack**, not just
   `mvn test` — and record in `CLAUDE.md` what was verified and how.

Give me the plan.
