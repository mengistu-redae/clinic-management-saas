# CLAUDE-history.md

Archived live-verification write-ups, split out of `CLAUDE.md` on 2026-09-16
purely because that file crossed a 150K-char size limit - nothing here was
edited or summarized, it's a verbatim move. Each "Verified this session..."
section is the detailed, phase-by-phase record of what was actually clicked/
curled/queried against the real running stack to confirm a phase's own
write-up in `CLAUDE.md` is true, not just written. `CLAUDE.md` itself keeps
the architecture, tenancy model, domain decisions, phase write-ups, testing
notes, and known gaps - this file is purely the evidentiary log behind those
"confirmed live" claims scattered through it.

Continue appending new "Verified this session - <phase>" sections here (not
back into `CLAUDE.md`) as future phases get their own live-verification pass,
to keep `CLAUDE.md` itself from re-growing past the limit.

---
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

## Verified this session - phase 7 (2026-09-13)

- `mvn -q -o compile`/`test-compile` - clean, first try, for the entire
  new main-source surface (migration, `labrate`/`laborder`/`payment`
  packages, `EncounterRepository` additions, `@ConfigurationPropertiesScan`,
  `SecurityConfig`/`node-bff` public-route wiring) and all four new test
  classes. `mvn -o test -Dtest='*Test,!*IntegrationTest'` - the existing
  23 pure-unit tests still pass. `mvn -o clean test` (full suite) -
  every integration test class, old and new, fails identically at
  container startup with `IllegalStateException: Could not find a valid
  Docker environment` (checked the surefire report directly) - the same
  pre-existing Windows Docker Desktop npipe limitation as every prior
  phase, not a regression from this phase's code.
- **Environment note**: this dev machine also runs a native Windows
  `postgresql-x64-17` service and, this session, a native Keycloak process
  (`C:\keycloak\keycloak-26.7.1`), both occupying `:5432`/`:8080` ahead of
  `docker compose up`. The native Keycloak process was stopped directly
  (same "stop what's occupying the port" precedent as phases 1/3); the
  Postgres Windows service needs admin rights this session doesn't have,
  so `docker-compose.yml`'s postgres service now maps its host port to
  `5433` instead of `5432` (`5433:5432`) - purely a host-side remap, every
  container-to-container connection still uses `postgres:5432` internally,
  unaffected. Use `localhost:5433` for any host tool (psql, a GUI client)
  connecting from outside docker on this machine.
- **`docker compose up -d --build` - full stack live-tested end to end**,
  `V5` applied cleanly including the `payments` `ALTER`/CHECK (spring-boot
  -api logs: "Successfully applied 1 migration ... now at version v5"). The
  demo Keycloak users' passwords (unknown - set by whichever session first
  triggered their forced reset) were reset via the admin REST API to a
  known value for this session's live testing.
- Real browser login as `demo-clinic-admin`, `demo-provider`, and
  `demo-patient` in turn (Keycloak's SSO session had to be explicitly
  ended via its own `/protocol/openid-connect/logout` confirm step between
  identities - `node-bff`'s own `/auth/logout` alone only clears the BFF
  session, not the Keycloak browser SSO session, so a bare `/auth/login`
  right after silently re-authenticates as whoever was still SSO'd in):
  - As `demo-clinic-admin`: created `lab_test_rates` for `CBC`/`LFT`/
    `LIPID`; registered a walk-in patient with a `nationalId` on file;
    booked+checked-in+roomed+started a real appointment and opened a real
    encounter (phase 4's endpoint) to order against.
  - As `demo-clinic-admin`/`demo-provider`: created a multi-test lab order
    - confirmed snapshotted pricing (`$55.00` = `$20+$5` CBC +
    `$30` LFT); a prior attempt with an unconfigured test code 400'd
    (`NoLabRateConfiguredException`) and succeeded once the rate was
    added. Drove it through `collect-specimen` (wrong ID 409, matching ID
    200) -> `send` -> `result` -> `review`, then confirmed both `send` and
    `collect-specimen` 409 when called again out of order, and `review`
    is idempotent (identical `reviewedAt` on a repeat call).
  - **Confirmed live, a genuinely different logged-in `demo-provider`
    identity can read a resulted-but-unreviewed test's result value**
    (`9.9`, `reviewedAt: null`) - the pinned visibility decision, not just
    exercised via the mocked-JWT integration test.
  - Test-list `PATCH`-equivalent replace while `ordered` (CBC -> LFT)
    confirmed live; cancelling a fresh order pre-collection recorded a fee
    of `$30.00` against a `$30.00` total (the clinic's `cutoffHours=0`/
    100% tier - `dueAt = Instant.now()` always lands there, exactly as
    designed).
  - Public tracking (`GET /api/lab-orders/track/{ref}?phone=`) through
    both `node-bff:3000` directly and through `nginx:80` with zero
    cookies - returned only `status`/timestamps, no result values/ids;
    wrong phone and an unknown ref both 404'd identically.
  - Payments: `demo-provider` could read but not record
    (`403`) a lab-order payment; `demo-clinic-admin` recorded one
    (`$55.00`, `cash`) and it round-tripped through the list endpoint.
  - Patient request flow: as `demo-patient`, `POST /api/my-lab-orders`
    (a freeform "Lipid Panel" request, no pricing) - as
    `demo-clinic-admin`, it appeared in `GET /api/lab-orders/requests`,
    was `confirm-and-order`'d (assigning a provider + a real `LIPID` test
    code, pricing at `$35.00`) - back as `demo-patient`,
    `GET /api/my-lab-orders` showed it with `status: "ordered"` and the
    now-set `totalCost`, confirming the full round trip end to end.

## Verified this session - frontend phase A (2026-09-13)

- `npm run build` in `node-bff/frontend/` - clean, no new npm dependencies
  needed (`react-router-dom`/`@tanstack/react-query` were already
  installed but unused since phase 1). `npm test` in `node-bff/` - still
  20/20 (this phase touched no server-side `node-bff` code, only
  `node-bff/frontend/`).
- `docker compose up -d --build --force-recreate node-bff` (frontend is
  baked into this image) - rebuilt clean.
- **A `demo-front-desk` login didn't exist yet** (only `demo-clinic-admin`/
  `demo-patient`/`demo-provider`/`demo-platform-admin` did) - created live
  via the Keycloak admin REST API (realm role `front_desk`, joined to the
  `demo-clinic` Organization as a member via `POST .../organizations/
  {orgId}/members` with the user id as a bare JSON string body - the same
  call `create-demo-clinic.sh` already makes), same one-time-manual-step
  precedent as phase 4/6's first `demo-provider`/`demo-platform-admin`.
  Not yet added to `infra/keycloak/realm-export.json` for a from-scratch
  environment - a real gap, see "Known gaps".
- **All five dashboards were live-verified end to end** through real
  browser logins (`demo-clinic-admin`/`demo-provider`/`demo-front-desk`/
  `demo-platform-admin`/`demo-patient`, password `DemoPass123!`), each
  showing genuinely real data, not placeholders:
  - `demo-clinic-admin` - "Clinic Overview" showed 3 active providers, 2
    active rooms, 2 appointment types, 10 active appointments, 0 pending
    lab requests - all matching the clinic's actual live state. The header
    rendered **"Browser Verified Clinic" in orange** - the clinic's real
    `brandColor` (set during phase-5 verification) - confirming
    `BrandingProvider` is genuinely fetching and applying live branding,
    not a static default.
  - `demo-provider` - "Today's Schedule" showed 9 patients today, 2 seen
    so far, a real per-appointment list with correctly-coloured
    `StatusPill`s (No Show/Cancelled/With Provider).
  - `demo-front-desk` - "Front Desk" showed 10 active / 20 total
    appointments broken down by status (6 booked, 2 checked-in/roomed, 2
    with provider), a real recent-appointments list with channel labels.
  - `demo-platform-admin` - "Platform Overview" showed 2 total clinics, 2
    active/0 deactivated, both listed with `StatusPill`s - and correctly
    **stayed on the default (unbranded) theme**, confirming
    `BrandingProvider`'s `platform_admin`-exclusion is real, not just
    written in a comment.
  - `demo-patient` - "My Dashboard" showed 0 active / 2 total appointments,
    1 open / 1 total lab order (the real `042751` order from the phase-7
    session, `status: Ordered`), both "Book an appointment"/"Request a lab
    test" rendered visibly disabled rather than dead links, and correctly
    stayed on the default theme too.
  - `RequireRole` redirects confirmed live both directions: a `patient`
    token visiting `/platform-admin` and a `clinic_admin` token visiting
    `/patient` each redirected cleanly back to `/` (that role's own
    dashboard), never a raw 403/blank page. An unknown path
    (`/nonexistent-page`) rendered the SPA's own `NotFound` component via
    client-side routing, confirming `src/index.js`'s static-file fallback
    correctly hands unknown paths to the SPA rather than 404ing at the
    server.
  - **A real login-flow finding this phase**: node-bff's own "Log out"
    button (`POST /auth/logout`, already wired since phase 1) genuinely
    ends the Keycloak SSO session in one step (it passes `id_token_hint`) -
    confirmed live, immediately switching roles worked cleanly every time.
    This corrects a phase-6/7-session finding that switching users needed
    a manual visit to Keycloak's own `/protocol/openid-connect/logout`
    with a confirmation click - that workaround was only ever needed
    because those earlier sessions weren't using the app's own logout
    button in the first place; **always use the real "Log out" button**
    for any future multi-role live verification.

## Verified this session - frontend phase B (2026-09-13)

- `mvn -q -o compile test-compile` in `spring-boot-api/` - clean for the
  new `ProviderDirectoryView`/`AppointmentWithSlotView` + repository/
  controller changes. `mvn -o test -Dtest='*Test,!*IntegrationTest'` -
  still 23/23. `mvn -o clean test` (full suite) - same 131 tests/108
  errors/0 failures as every prior phase's run, all at the identical
  Testcontainers/npipe wall - not a regression.
- `npm run build` in `node-bff/frontend/` - clean, no new npm deps.
  `docker compose up -d --build --force-recreate spring-boot-api node-bff`
  (both changed this phase) - both healthy.
- **The public directory endpoints confirmed live through nginx with zero
  cookies**: `GET /api/clinics`, `GET /api/clinics/{id}/providers`,
  `GET /api/clinics/{id}/appointment-types`, and `GET /api/clinics/{id}/
  availability?providerId=&appointmentTypeId=` all returned real data
  anonymously.
- **The full patient booking lifecycle, live, as `demo-patient`**: booked a
  real appointment (clinic -> type -> provider -> slot -> confirm), landed
  on the detail page showing a genuinely real time (not a placeholder);
  rescheduled it to a different slot on the same page flow (confirmed the
  new time replaced the old one); cancelled it (status flipped to
  `cancelled`, the Reschedule/Cancel actions disappeared). `GET
  /my-appointments`'s full list showed real times for every row, not just
  the one just-created appointment - confirming `AppointmentWithSlotView`
  works for historical rows too, not only fresh ones.
- **The full guest booking lifecycle, live, from a genuinely anonymous
  browser session (no cookies)**: `PublicShell`'s new nav (`Book an
  appointment`/`Track an appointment`) reachable from the logged-out
  landing; booked as a guest (no session at all); landed on the same detail
  page, correctly showing the "no account - save your ref" note and no
  cancel/reschedule actions (no guest self-service endpoint exists
  server-side); looked the same appointment up again via `/track-appointment`
  with the saved ref+phone - matched, showing only the narrow
  `AppointmentTrackingView` fields; retried with a wrong phone - 404'd with
  the same "no appointment found" message the reference project's own
  tracking page uses for both a mismatch and an unknown ref alike.
- **A real 409-slot-conflict race, genuinely reproduced, not simulated**:
  selected a slot in the browser, then - before clicking confirm - booked
  that exact same `slotId` via a raw `curl` call to `POST /api/appointments/
  guest` (confirmed via direct Postgres query that the slot's `status`
  flipped to `booked` and belonged to a different appointment row).
  Clicking "Confirm booking" in the browser then correctly surfaced "That
  slot was just taken by someone else. Pick another." - **this is exactly
  the real bug described above being caught**: the first attempt at this
  race showed nothing at all (silently no error, no navigation) because the
  error banner was nested inside the panel the 409 handler had just
  unmounted; fixed live during this same verification pass, rebuilt, and
  reran the identical race to confirm the fix.
- Note on this environment: the Docker host's system timezone is **UTC+3**,
  not UTC - `formatTime`/`formatDateTime`'s `Intl.DateTimeFormat(undefined,
  ...)` renders in whatever timezone the browser/OS reports, so a slot's
  `startTime` (always UTC on the wire) shows 3 hours ahead of its raw UTC
  value in any screenshot from this machine. Not a bug, just worth knowing
  when cross-checking a displayed time against a raw API response during
  live verification here.

## Verified this session - frontend phase C (2026-09-13)

- `npm run build` in `node-bff/frontend/` - clean, no new npm dependencies.
  No backend changes this phase, so no `spring-boot-api` rebuild needed -
  `docker compose up -d --build --force-recreate node-bff` alone (frontend
  is baked into that image) picked up the new pages.
- This session picked back up an already-in-progress working tree (the
  five new `pages/front-desk/*.jsx` files plus their `App.jsx`/`queries.js`/
  `AppShell.jsx`/`Dashboard.jsx` wiring already existed uncommitted) - full
  live verification was still run end to end before committing rather than
  trusting the uncommitted diff on sight, same discipline as every other
  phase.
- **The whole front-desk walk-in flow was live-verified end to end** as a
  real `demo-front-desk` login through the real browser/node-bff/nginx
  stack:
  - `PatientSearch` listed real seeded patients immediately (empty-query
    browse); clicking one navigated straight into `BookForPatient` with the
    right patient name resolved in the header. The inline "register new
    patient" form worked too on a separate pass.
  - `BookForPatient` correctly showed **no open slots** for a provider with
    no working hours configured (`Dr. Browser Test`, a leftover test
    provider) and a full multi-week slot grid for one that does
    (`Dr. Demo Provider`) - the empty-state and populated-state both
    confirmed, not just the happy path. Booked a real appointment
    (ref `4CCBC6`) - landed on `AppointmentDetail` showing `Booked`.
  - Drove a separate appointment (ref `F6A604`) through the full check-in
    sequence - `Check in` -> `Checked In`, `Room` -> `Roomed`, `Start
    visit` -> `With Provider`, `Check out` -> `Checked Out` - the "Next
    step" button correctly swapped to the right single action at every
    status, and `Checked Out` correctly hid the "Next step"/Reschedule/
    Cancel sections entirely (the `isTerminal` gate).
  - Recorded a cash payment ($35.00) against that same appointment while
    `with_provider` - it appeared in the payments list immediately; **spot
    -checked directly in Postgres** (`SELECT ... FROM payments`) to confirm
    it round-tripped as a real row, not just an optimistic UI update.
  - Rescheduled `4CCBC6` to a different slot on the same provider - no
    error, navigated back to the (still `Booked`) detail page. **Spot
    -checked in Postgres** (`appointment_reschedules` joined back to
    `appointments`) that `previous_slot_id` genuinely differs from the
    appointment's current `slot_id` and a `$0.00` fee row was written (the
    platform default) - confirming the mutation actually moved the slot,
    not just displayed success.
  - Cancelled `4CCBC6` - status flipped to `Cancelled`, and confirmed the
    Payments section itself also disappears once `cancelled` (not just the
    action buttons) - the `status !== 'cancelled'` gate on that section
    working as designed.
  - `Appointments` (the full tenant-wide list) correctly resolved every
    row's real patient name (or guest `contactName`) via the
    id->name `Map` built from one `GET /api/patients` call, sorted
    newest-`bookedAt`-first, with correctly-coloured `StatusPill`s across
    every status seen this session (`Booked`/`Checked In`/`With Provider`/
    `Checked Out`/`Cancelled`/`No Show`).
  - `AppShell`'s front-desk nav (`Dashboard`/`Book for a walk-in`/
    `Appointments`) and the dashboard's own "Book for a walk-in" button and
    now-clickable recent-appointments rows all worked; branding
    ("Browser Verified Clinic" in orange) stayed correctly applied
    throughout, confirming this phase's new pages sit inside the same
    branded `AppShell` as every other role's screens, not a separate
    unstyled shell.
  - One click-automation quirk worth noting for future sessions, **not an
    app bug**: `find()`-obtained element refs occasionally went stale
    against React re-renders mid-flow (a click would silently land on
    whatever was at those stale coordinates instead, once landing on the
    "Dashboard" nav link instead of "Start visit") - re-navigating directly
    to the appointment URL and clicking by a freshly-screenshotted
    coordinate recovered every time. Genuine state changes (the Room ->
    With Provider transition itself) were then confirmed via
    `get_page_text`/Postgres, not assumed from the click alone.

## Verified this session - frontend phase D (2026-09-14)

- `npm run build` in `node-bff/frontend/` - clean, no new npm dependencies.
  `docker compose up -d --build --force-recreate spring-boot-api node-bff`
  (both changed - the `application.yml`/`client.js` error-message fix
  touched both) - both healthy.
- This session picked back up mid-verification after a real session gap -
  the browser's node-bff cookie had expired by the time verification
  resumed, surfacing as a silent redirect back to the Keycloak login page
  mid-flow. Re-logging in as `demo-clinic-admin` and re-checking state
  before continuing (rather than assuming a click had succeeded) caught
  this immediately - a working-hours removal that looked "clicked" had in
  fact never reached the server, confirmed by revisiting the same panel.
- **The whole clinic-admin CRUD/settings surface was live-verified end to
  end** against the real running stack, as a real `demo-clinic-admin` login:
  - **Providers**: created "Dr. Browser Verified", edited it (room
    reassigned, confirmed via the updated row), deactivated then
    reactivated it. **Working hours**: added a window, then - this is what
    surfaced the error-message bug above - attempted an overlapping second
    window on the same day; first saw the raw JSON error body, fixed the
    backend/client live, rebuilt, retried the identical overlap and
    confirmed the real message now renders ("This window overlaps an
    existing one for this provider and day"); removed the window
    afterward (hard delete, confirmed gone). **Login**: linked the test
    provider to a real already-provisioned account
    (`demo-front-desk@example.test`) - confirmed the "has a login" badge
    and panel state flipped to show "Unlink"; unlinked it, confirmed the
    panel reverted to the link form. A provider with no working hours
    correctly shows "No open slots" wording; one with an unconfigured
    overlap-free window books normally (implicitly reused the same
    provider/slot-generation path already proven in earlier phases, not
    re-tested here).
  - **Rooms**: created "Room Browser Verified", renamed it via edit,
    deactivated then reactivated - confirmed status persists correctly
    across each transition, not just the click landing.
  - **Appointment Types**: created "Browser Verified Checkup"
    (30 min/$40), edited its price to $45 while `Inactive` and confirmed
    the status itself stayed untouched by an edit that didn't touch
    `status` (partial-update semantics preserved, not accidentally reset
    on every save).
  - **Fee Policies**: confirmed the seeded clinic-wide default tiers
    (24h/0%, 2h/50%, 0h/100%) render grouped correctly; added a
    provider-specific tier (`Dr. Browser Verified`, 48h/25%) and confirmed
    it renders in its own group, separate from the clinic-wide default -
    edited it to 30%, then hard-deleted it (confirmed gone, not
    soft-deactivated).
  - **Lab Rates**: confirmed the seeded `CBC`/`LFT`/`LIPID` rates render;
    created `TSH` ($28 base), edited it to add a $3 collection fee,
    deleted it (confirmed gone).
  - **Settings (General)**: confirmed the resolved `{overrides, effective,
    defaults}` view - all fields blank/showing pure platform defaults
    before any override. Set `rescheduleMinNoticeHours` to 48, saved,
    **confirmed the override persisted across a fresh page navigation**
    (not just optimistic local state) - then cleared it back to blank,
    saved again, and confirmed the field reverted to showing "4" as a
    greyed placeholder again, i.e. `effective` genuinely fell back to the
    platform default rather than the override merely displaying as blank.
  - **Branding**: confirmed the real pre-existing branding (from phase-5
    verification) pre-filled correctly, and that the live colour preview
    panel visually matches the real `AppShell` header/button chrome
    (orange brand bar, teal accent button) - not just built to resemble it.
  - **A real mistake made and caught during this same verification, not by
    the app**: a coordinate-based click meant for the Footer Note field
    landed on the Logo URL field instead (the page had scrolled/reflowed
    between the screenshot and the click), appending stray text and saving
    a corrupted `logoUrl` to the live demo clinic's branding row. Caught
    immediately on the very next screenshot (the appended text was visibly
    in the wrong field), corrected by re-typing the clean URL and saving
    again, then confirmed clean via a fresh page load - flagged here
    explicitly since it was this session's own automation error editing
    live shared demo data, not a bug in the code being verified.

## Verified this session - frontend phase E (2026-09-14)

- `npm run build` in `node-bff/frontend/` - clean, no new npm dependencies.
  No backend changes this phase, so no `spring-boot-api` rebuild -
  `docker compose up -d --build --force-recreate node-bff` alone.
- **The whole encounter-documentation flow was live-verified end to end**
  as a real `demo-provider` login, against real appointments (not fixtures):
  - Opened a real `checked_out` appointment's encounter page - patient
    name ("Walk In") resolved correctly, the note form rendered (confirming
    `checked_out` counts as documentable, matching the "editable
    indefinitely" decision from phase 4), and the prescriptions section
    correctly showed the "save the note first" lock since no encounter
    existed yet.
  - Filled in and saved a real note (chief complaint/assessment/plan) -
    button label flipped from "Save note" to "Update note", a "Saved."
    confirmation appeared, and **a direct Postgres query confirmed exactly
    one `encounters` row** for that appointment with the submitted text.
  - Added one prescription line and saved - confirmed via Postgres it
    landed as a real `prescriptions` row (not just an optimistic UI
    update), correctly linked to the encounter's own id.
  - **Confirmed the fresh-load repopulation actually works, not just the
    save**: re-navigated to the same URL from scratch and confirmed all
    three note fields and the prescription line re-appeared prefilled from
    the server, not from any local/cached state carried over from the save.
  - Navigated to a different, still-`booked` appointment's encounter page -
    confirmed the client-side status gate correctly blocks it with a clear
    explanatory message ("This appointment is still 'booked' - start the
    visit...") and no form at all, matching `EncounterService.
    requireDocumentableStatus` without ever needing to hit the real 409.

## Verified this session - frontend phase F (2026-09-14)

- `npm run build` in `node-bff/frontend/` - clean, no new npm dependencies.
  No backend changes this phase, so no `spring-boot-api` rebuild -
  `docker compose up -d --build --force-recreate node-bff` alone.
- **The whole lab-order lifecycle was live-verified end to end**, spanning
  three real identities (`demo-provider`, `demo-clinic-admin`,
  `demo-patient`, switched via the app's own "Log out" button each time):
  - **Staff creation and the full status machine** (as `demo-provider`):
    created a real order (`ED064B`, patient "Walk In", test `CBC`) -
    confirmed the snapshotted price ($25.00 = $20 base + $5 collection,
    matching the seeded `lab_test_rates` row). Drove it through
    `collect-specimen` - first with a wrong ID (409, the exact message
    rendered, not a raw error), then the matching one (200, `Specimen
    Collected`) - `send` (`In Transit`, the result-entry form appeared) -
    entered a result (value/unit/reference-range/abnormal checkbox) and
    saved (`Resulted`, the result rendered inline in the test table,
    including the ABNORMAL flag) - `review` (`Reviewed`, terminal, every
    action button correctly disappeared, only Payments remained).
  - **Role-gated payment recording, a real 403 confirmed correctly, not a
    bug**: `demo-provider` attempting to record a payment on that same
    order got a 403 (`LabOrderPaymentController.recordPayment` is
    `front_desk`/`clinic_admin` only, not `provider` - read access is
    3-role, write isn't) - confirmed the generic "Request failed with
    status 403" fallback is correct here specifically because Spring
    Security's own 403 body carries no message (unlike the
    `ResponseStatusException` case the phase-D bug fix targeted). Switched
    to `demo-clinic-admin` and recorded the same $25.00 payment
    successfully - "Collected 25.00 of 25.00" - and confirmed the abnormal
    result from the provider's own earlier session was still visible
    (proving results are readable across genuinely different logins, not
    session-cached).
  - **Edit and cancel** (as `demo-clinic-admin`): created a second order
    (`D81EDF`, `LFT`, $30.00), edited it while `ordered` (priority ->
    Urgent, confirmed it saved), then cancelled it - confirmed via
    Postgres the `lab_order_cancellations` row recorded a **$30.00 fee on
    a $30.00 total** (the clinic's zero-cutoff 100% tier, `dueAt =
    Instant.now()` always lands there by design, per the phase-7
    decision) - not just that the status flipped.
  - **The full patient request -> confirm-and-order round trip**: as
    `demo-patient`, submitted a freeform request ("Lipid panel", no
    pricing shown) via the dashboard's now-real "Request a lab test"
    button - landed on the patient's own detail page showing `Requested`
    and the pending-review message. As `demo-clinic-admin`, the pending
    -requests banner on `/lab-orders` showed it immediately; confirmed
    -and-ordered it (assigned `Dr. Demo Provider` + the real `LIPID` test
    code) - confirmed live it flipped to `Ordered` with the real $35.00
    price, provider name, and test code all populated from the confirm
    form.
  - **Public tracking, genuinely anonymous**: logged out first (not just
    an unauthenticated route inside an authenticated tab), tracked
    `ED064B` with its patient's real phone - got back only status/
    timestamps, no result values/reference ranges/abnormal flags: then
    retried with a wrong phone - 404'd with the same "no lab order found"
    message an unknown ref would give, confirmed identical to the
    appointment-tracking page's own established mismatch behavior.

## Verified this session - frontend phase G (2026-09-14)

- `npm run build` in `node-bff/frontend/` - clean, no new npm dependencies.
  No backend changes this phase, so no `spring-boot-api` rebuild -
  `docker compose up -d --build --force-recreate node-bff` alone.
- **The whole clinic-onboarding flow was live-verified end to end** as a
  real `demo-platform-admin` login:
  - Onboarded a genuinely new clinic ("Browser Verified Clinic Two",
    alias `browser-verified-clinic-two`) - **confirmed via a direct
    Keycloak admin-API call that a real Organization was created**
    (3 orgs listed afterward, not just a local Postgres row).
  - Attempted a duplicate org alias (`demo-clinic`, already taken) - 409
    with the real backend message ("A clinic already exists for org
    alias: demo-clinic") rendered correctly, not a raw error - and
    **confirmed via the same Keycloak admin-API call that the rejected
    attempt created zero new/orphaned orgs** (still exactly 3), proving
    `ClinicProvisioningService`'s local pre-check genuinely runs before
    ever calling Keycloak, not just in the backend test suite.
  - Edited the new clinic's name inline - saved correctly, `orgAlias`
    untouched (matches `UpdateClinicRequest`'s name-only shape).
  - Deactivated then reactivated it - status and the action button's own
    label flipped correctly both times (idempotent transitions, same
    convention as `CheckInService`'s own).

## Verified this session - post-phase-7 backend additions (2026-09-19/20)

`docker compose up --build -d` (fresh images for both `spring-boot-api` and
`node-bff`, picking up this session's code) - all six services came up
healthy. Docker Desktop itself wasn't running at session start and had to
be launched first; the native `postgresql-x64-17` Windows service was still
holding `:5432` as documented, no conflict since compose maps to `5433`.

- **Initial clinic_admin provisioning, live end to end as a real
  `demo-platform-admin` browser login** (not just the mocked integration
  test): onboarded a genuinely new clinic ("Verify Clinic Live", alias
  `verify-clinic-live`) with the new optional admin email/name fields
  filled in - the amber one-time banner appeared immediately with a real
  16-character temporary password. Confirmed via direct Keycloak
  admin-API calls (not just trusting the UI) that every step of the chain
  actually happened: the user exists (`admin@verify-clinic-live.example`,
  firstName/lastName correctly split to "Verify"/"Admin",
  `requiredActions: ["UPDATE_PASSWORD"]` from the temporary credential),
  it holds the `clinic_admin` realm role, and it's a real member of the
  new organization (`membershipType: "UNMANAGED"`, i.e. a direct member,
  not inherited).
  - **Then actually logged in as that brand-new account with the shown
    temporary password** - Keycloak correctly intercepted with its own
    "Update password" screen ("You need to change your password to
    activate your account") before letting the login complete, exactly
    the `temporary: true` behavior the implementation relies on. After
    setting a real password, landed cleanly on a `clinic_admin` dashboard
    branded "Verify Clinic Live" with the full clinic-admin nav
    (Providers/Rooms/Appointment Types/Lab Orders/Settings) and correct
    all-zero stats for a brand new tenant - proving `TenantContextFilter`
    resolves this token's organization claim to the right clinic on the
    very first login, not just that the Keycloak-side objects exist.
- **NotificationWorker, live against a real booking** - booked a genuine
  guest appointment via `POST /api/appointments/guest` (through node-bff,
  not directly against spring-boot-api) with a real `contactEmail`
  against Demo Clinic's seeded provider/appointment-type/slot data.
  Within the worker's 10-second poll window, `spring-boot-api`'s own
  container logs showed `LoggingEmailSender` firing exactly once:
  `[STUB EMAIL] to=notify-verify@example.com type=appointment_confirmed
  tenant=8146eddf-... payload={}`. Confirmed directly in Postgres that the
  same `notifications` row flipped from the write-time `pending` to
  `status = 'sent'` with `attempts = 0` and a non-null `sent_at` - the
  full outbox lifecycle working against the real scheduler, not just the
  pure-Mockito unit test's simulated call sequence.

## Verified this session - PHI access audit log (2026-09-20)

Live-verified against the real running stack (`docker compose up --build
-d spring-boot-api` - Flyway applied V6 cleanly, confirmed in the
container's own startup log) as real `demo-front-desk`/`demo-clinic-admin`
browser logins, not just the test suites: registered a genuinely new
patient through the front-desk walk-in UI, confirmed via direct Postgres
query that both a `write` row (the registration) and a `read` row (the
booking flow's own follow-up patient fetch) landed with the correct
`patient_id`; then, as `demo-clinic-admin`, called `GET /api/clinic/
phi-access-log?patientId=...` for real (via an in-page `fetch`, cookies
included) and got back both rows with the actor's real email/role/
internal-user-id correctly resolved from the actual Keycloak-issued token -
not a fabricated/mocked identity.

## Verified this session - payment auto-charge (2026-09-20)

Live-verified against the real running stack, not just the test suite:
booked a real guest appointment (no `Patient` row at all) ~10.5 hours out
against Demo Clinic's real fee policy (50% under 2h notice, 100% under 0h,
0% beyond 24h - so this notice window lands in the 50% tier), cancelled it
as `demo-clinic-admin` through the actual front-desk UI, and confirmed
directly in Postgres: one `payments` row, `amount = 25.00` (50% of the
$50.00 appointment type), `method = 'fee_auto_charged'`, `recorded_by`
populated with the acting `clinic_admin`'s real `AppUser` id - including
the guest-booking edge case (`appointment.patientId IS NULL`) working
correctly, not just a patient-linked booking. Extended
`CancellationIntegrationTest`/`RescheduleIntegrationTest`/
`LabOrderIntegrationTest` accordingly - all compile clean and fail only via
the same pre-existing Testcontainers wall as every other
`AbstractIntegrationTest` subclass (`Failures: 0` in every surefire report,
confirmed via a full suite run before and after).

## Verified this session - phase 8: allergies + vitals (2026-09-20)

Rebuilt `spring-boot-api` (`docker compose up --build -d spring-boot-api`),
confirmed Flyway applied V7 cleanly in the container's own startup log,
then as a real `demo-front-desk` browser login (via an in-page `fetch`,
cookies included): recorded vitals on a genuinely plain-`booked`
appointment (proving the "no status gate" decision actually works, not
just compiles - `EncounterController`'s own gate would have rejected this
exact call with a 409) with BMI computed correctly (170cm/70kg -> 24.2),
then recorded a real allergy for that appointment's patient. Confirmed
directly in Postgres that both writes landed in `phi_access_log` with the
correct `resource_type`/`patient_id`/`actor_role` - the PHI-audit wiring
added this same session actually fires for these two new resources, not
just the ones it launched with.

## Verified this session - phase 9: medical history (2026-09-20)

Rebuilt `spring-boot-api`, confirmed Flyway applied V8 cleanly in the
container's own startup log, then as a real `demo-front-desk` browser
login (in-page `fetch`, cookies included): recorded medical history for a
real patient, then called it again omitting three of the five fields and
confirmed the response actually came back with those three as `null` (not
silently retaining the old values) - the full-replace semantics working
live, not just passing a unit assertion. Confirmed directly in Postgres
that exactly one `medical_history` row exists for that patient after both
calls (upsert, not a duplicate insert), and that both writes landed in
`phi_access_log` with `resource_type = 'medical_history'`.

## Verified this session - phase 10: consent & compliance records (2026-09-20)

Rebuilt `spring-boot-api` (one transient Docker build failure on the first
attempt, succeeded cleanly on retry with no code changes - not
reproducible, not investigated further), confirmed Flyway applied V9
cleanly in the container's own startup log, then as a real
`demo-front-desk` browser login recorded two consent records
(`general_treatment`/`v1` and `privacy_data`/`v2`) for the same real
patient used in phases 8/9's own verification, and confirmed via `GET`
that **both** rows come back - not just the most recent one, proving the
accumulate-not-replace design actually holds against a real request, not
just a mocked test. Confirmed directly in Postgres: exactly 2 rows for
that patient, and `phi_access_log` correctly recorded both writes plus the
list read.

## Verified this session - phase 11: prescription + coding depth (2026-09-20)

Rebuilt `spring-boot-api`, confirmed Flyway applied V10 cleanly, then as a
real `demo-provider` browser login wrote an encounter with `icd10Codes:
"J02.0"` and a prescription with all six new fields populated against a
real `checked_out` appointment - confirmed both round-tripped correctly
via direct Postgres query. Confirmed the route allow-list actually rejects
a bad value live (`"nasal"` -> 400 with the real allow-list message, not a
generic error). Then loaded the existing provider encounter UI for that
same appointment and confirmed every pre-existing field still rendered its
real value correctly - proving the wider backend shape didn't disturb the
page that already exists against it.

## Verified this session - phase 12: sign-and-lock clinical notes (2026-09-20)

Rebuilt `spring-boot-api`, confirmed Flyway applied V11 cleanly, then as a
real `demo-provider` login against the same real encounter written in
phase 11's own verification: signed it, confirmed a direct edit attempt
genuinely fails with the real 409 lock message (not just returns 200
silently), confirmed prescriptions are locked too, added a real addendum
and confirmed it appears in the bundled GET response, and confirmed
directly in Postgres that the original note content never changed (still
"Sore throat", not the rejected edit's text) and exactly one addendum row
exists.

Extra care given this phase's behavior-reversing nature: also drove the
*existing* `provider/Encounter.jsx` page (not just raw API calls) against
the now-locked encounter. It has no dedicated "locked"/"signed" UI, no
sign button, and no addendum form - but clicking "Update note" through the
real page does correctly surface the raw backend 409 message via that
page's own generic error-handling, confirmed via the actual network
request (a real POST returning 409) and the resulting page text showing
the exact backend message - not a silent failure, just an unpolished one.
A first click via the browser-automation tool's coordinate-based click
appeared to do nothing (no network request fired at all); dispatching a
real `.click()` on the button element via JavaScript did fire the request
correctly, so that first attempt was a tool-interaction quirk, not a page
bug - worth noting so this finding isn't misread as "the button doesn't
work."

## Verified this session - phase 13: provider profile hardening (2026-09-20)

Rebuilt `spring-boot-api`, confirmed Flyway applied V12 cleanly and the new
`uploads_data` Docker volume was created. Updated a real provider's
license/employment fields as `demo-clinic-admin` via an in-page `fetch` and
confirmed they round-tripped correctly.

Then attempted a real signature upload through the actual browser path
(node-bff, not a direct curl to spring-boot-api) - got a **real 500**,
`"Current request is not a multipart request"`. Traced it to `node-bff`'s
`forwardToApi`: it always JSON-re-serialized `req.body`, but
`express.json()` (mounted globally in `index.js`) only parses an
`application/json` body, leaving `req.body` as `{}` for a multipart
request - the actual file bytes were never captured at all. Confirmed via
reading `body-parser`'s own behavior that it never touches the request
stream for a non-matching content type (checks the content-type first,
calls `next()` immediately if it doesn't match), so the raw stream was
still fully intact and available - fixed by detecting a multipart
content-type and forwarding `req` itself as a streaming body
(`duplex: 'half'`, Node's own requirement for a streaming fetch body),
preserving the original `content-type` header including its boundary
parameter, instead of trying to re-serialize a `req.body` that was never
populated for this content type in the first place.

Rebuilt `node-bff` with the fix and retried the exact same upload - 200,
with the correct `signatureFilename`/`signatureContentType` in the
response. Confirmed via `GET` that the downloaded bytes were byte-for-byte
identical to the uploaded PNG (including the real PNG magic-number byte
sequence `89 50 4E 47 0D 0A 1A 0A`) and the `Content-Type` header came back
as `image/png`. Confirmed the file's real presence on disk via
`docker exec ... ls /app/uploads/provider-signatures/` (using
`MSYS_NO_PATHCONV=1` to stop Git Bash mangling the absolute container
path) - not just trusting the API's own claim that it stored something.
Removed the signature via the real endpoint and confirmed via the same
`docker exec` check that the file was genuinely deleted from the volume,
not just the database reference cleared, and that a subsequent `GET`
correctly 404s. Finally confirmed the content-type allow-list rejects a
`text/plain` upload with a real 400 through the now-fixed proxy path,
not just directly against spring-boot-api.

## Verified this session - phase 14: referrals (2026-09-20)

Rebuilt `spring-boot-api`, confirmed Flyway applied V13 cleanly. As a real
`demo-provider` browser login (in-page `fetch`, cookies included):
created a real internal referral (a real patient, real referring/receiving
`Provider` ids, `priority: "urgent"`) and confirmed the response came back
with `status: "pending"`, the right `receivingProviderId`, and no external
fields populated. Created a real external referral (external provider/
clinic name, no receiving provider) and confirmed the mirror-image shape.
Attempted a referral with neither `receivingProviderId` nor any external
field and got a real 400 with the exact message
`ReferralService.create` throws, not a generic validation error.

Transitioned the internal referral's status to `completed` and confirmed
the response included a non-null `completedAt` - the automatic-on
-terminal-status logic firing on a real request, not just a unit
assertion. Confirmed directly in Postgres: the internal row has
`receiving_provider_id` set and `external_provider_name` null, the
external row is the mirror image, and the completed row's
`completed_at` is non-null while the still-pending external row's is
null. Confirmed all three writes (two creates, one update) landed in
`phi_access_log` with `resource_type = 'referral'` and
`actor_role = 'provider'`.

## Verified this session - clinic_admin encounter-access fix (2026-09-15)

As `demo-clinic-admin`, reached `/front-desk/appointments` (no redirect -
the widened gate works), opened a real `with_provider` appointment
("Encounter Test Patient") and saw the new "Document encounter" link,
followed it to the shared encounter page (no redirect to `/`), and
**edited the existing note** - confirmed via Postgres exactly one
`encounters` row for that appointment both before and after (id
unchanged, `updated_at` bumped), same "editable indefinitely, upsert
never duplicates" guarantee phase 4 already proved for `provider`.
Confirmed the "Document encounter" link is correctly absent on a `booked`
appointment, and that navigating there directly renders the existing "not
ready to document yet" empty state with the new "Back to appointment"
link, which correctly lands back on `/front-desk/appointments/:id` (not a
loop). Logged out (app's own "Log out" button - one step, confirmed clean
per the frontend-phase-A finding) and back in as `demo-front-desk`: the
same `with_provider` appointment's detail page shows no "Document
encounter" section at all, and navigating directly to the encounter URL
redirects cleanly back to `/` - `front_desk` still has zero access, both
by omission (no link) and by the route gate (direct URL doesn't work
either). `front_desk` still lists/opens appointments on the now-shared
route normally. Logged in as `demo-provider` and reached the same
encounter page directly - unaffected, and it correctly showed the note
`demo-clinic-admin` had just edited (chief complaint "Sore throat,
documented by clinic_admin"), proving the shared-editing path works
across roles, not just per-role in isolation.

## Verified this session - phase 15: real billing build-out

Rebuilt the `spring-boot-api` container (`docker compose up -d --build
spring-boot-api`, transient TLS handshake failure on the first attempt
against Maven Central, succeeded on retry) and confirmed `V14__invoices_
billing.sql` applied (`flyway_schema_history` shows version 14, `\d
invoices` shows `appointment_id` now nullable, a new `lab_order_id`
column + `invoices_lab_order_id_key` UNIQUE constraint, and the
`chk_invoices_exactly_one_owner` CHECK).

Logged in as a real `demo-front-desk` user through the actual browser
(`http://localhost/front-desk/appointments`), opened a genuine
`checked_out` appointment (ref `F6A604`, id
`583d8fbb-f0fa-4eae-8609-18726b5724f0`, with an existing $35 cash
payment already recorded against it from an earlier session) and drove
the new endpoints via authenticated `fetch(..., {credentials:'include'})`
calls in the browser console (same technique every backend-only EHR
phase this session has used, since none of them have a frontend yet):

- `GET .../invoice` before any invoice existed correctly 404'd
  (`"No invoice generated yet for appointment ..."`).
- `POST .../invoice` returned 200 with `subtotalAmount: 35.00,
  taxAmount: 0.00, totalAmount: 35.00` - `subtotalAmount` pulled from the
  appointment's own `AppointmentType.priceAmount` (not the unrelated
  $35 payment already on file, which is a coincidence of this fixture's
  numbers, not the source of the figure - confirmed by reading
  `InvoiceService.generateForAppointment`'s actual code path, not
  inferred from the number matching).
- A second `GET` returned the same row; a second `POST` correctly 409'd
  (`"An invoice already exists for appointment ..."`), confirming the
  immutable-once-generated design holds against a real duplicate
  request, not just the test suite's mocked one.
- Confirmed directly in Postgres: exactly one `invoices` row,
  `appointment_id` set, `lab_order_id` null, amounts matching the API
  response exactly.

Then switched identity (the app's own "Log out" button, then signed in
as `demo-clinic-admin` - the same one-step SSO logout this session's own
memory already documents) and reached the real `/clinic-admin/settings`
page. Rather than fight a temporarily-unresponsive screenshot channel
(the page itself was confirmed live and interactive via
`document.readyState === "complete"` and successful `fetch` calls
throughout - only the CDP screenshot RPC hung once, unrelated to the
app), verified the settings save path the same way: read the current
`GET /api/clinic/settings` response, then `POST` it back with
`taxRatePercent: 8.25` - the exact request body `clinic-admin/
Settings.jsx`'s own form submits, just issued directly rather than via
simulated clicks. Confirmed the response's `effective.taxRatePercent`
came back `8.25`, not just the override being accepted.

Fetched `GET /api/lab-orders` as `demo-clinic-admin` and picked a real
`reviewed` order (id `81fc88e0-c77b-4334-a242-1fae92b716b6`,
`totalCost: 55`) left over from phase 7's own live verification.
`POST .../invoice` returned `subtotalAmount: 55.00, taxAmount: 4.54,
totalAmount: 59.54` - `55.00 * 8.25% = 4.5375`, HALF_UP-rounded to
`4.54`, exactly matching `InvoiceService`'s own rounding mode, proving
the `ClinicSettingsService.resolve(tenantId)` wiring is genuinely live,
not just present in the code. `appointmentId` was `null` and
`labOrderId` was set on this row - confirmed both the appointment
-owned and lab-order-owned rows side by side directly in Postgres
afterward (`SELECT ... FROM invoices`), each satisfying the
exactly-one-owner CHECK visibly, not just by the app's own claim.

Reverted the clinic's `taxRatePercent` override back to `null` via the
same `POST /api/clinic/settings` endpoint immediately afterward (full
request body with every field `null`, confirmed `effective.
taxRatePercent` back to the platform default `0.0`) - a deliberate
cleanup so this verification pass doesn't leave a surprise non-zero tax
rate sitting on the shared demo clinic for the next session to trip
over.

## Verified this session - frontend phase H

Rebuilt the `node-bff` container after `npm run build` succeeded clean
in `node-bff/frontend/`. Logged in as a real `demo-front-desk` user and
opened the same checked-out appointment used for phase 15's own live
verification (ref `F6A604`). The new "Patient chart" panel rendered
immediately below the appointment summary, already showing one real
pre-existing allergy (Penicillin/Rash/Severe/Active, from an earlier
session's data).

Typed a new allergy (Latex/Hives, Moderate) into the inline add form and
submitted - it appeared instantly in the list as Active, and the form
cleared. Clicked "Mark resolved" on it - the badge flipped to green
"Resolved" and the action link flipped to "Reactivate" in place, no page
reload. Confirmed both rows directly in Postgres
(`SELECT allergen, severity, status FROM allergies WHERE patient_id = ...`)
- Penicillin still `severe`/`active`, Latex now `moderate`/`resolved`,
matching the UI exactly.

Entered vitals (height 170, weight 70, temp 37, pulse 72) and saved -
the BMI field (read-only, derived) computed `24.2` the moment the save
completed, matching `70 / 1.70²` HALF_UP-rounded to one decimal, and the
button label flipped from "Save vitals" to "Update vitals". Confirmed the
row in Postgres (`height_cm 170.0, weight_kg 70.0, temperature_c 37.0,
pulse_bpm 72`).

Logged out (the app's own one-step "Log out" button) and back in as a
real `demo-provider`, then navigated directly to
`/provider/appointments/<same id>/encounter` (this appointment wasn't
"today" from the provider's own schedule, so reached it by URL rather
than via the dashboard list). The same Patient Chart panel rendered with
the same data - both allergies shown, but with no add-allergy form and no
"Mark resolved"/"Reactivate" buttons on either row, confirming the
write-gate (`front_desk`/`clinic_admin` only) held for a genuinely
different login, not just hidden by a client-side role check that
happened not to fire. Vitals showed the same pre-filled values from the
front-desk save and remained a live, editable form (provider is one of
vitals' three writable roles) - confirmed live that this shared component
correctly differentiates write access per-section, not per-page. Scrolled
further and confirmed the existing encounter note (chief complaint "Sore
throat, 3 days", assessment "Likely viral pharyngitis", from an earlier
session) rendered unaffected below the new panel - the insertion didn't
disturb the pre-existing page.

## Verified this session - frontend phase I

Rebuilt the `node-bff` container after `npm run build` succeeded clean.
Logged in as a real `demo-front-desk` user and reopened the same
checked-out appointment (ref `F6A604`) used for phase H's own
verification. The new "Consent" section rendered below "Medical history"
with two real pre-existing records already on file: "General treatment /
Given / Policy v1 / Witnessed by Jane Witness" and "Privacy & data /
Given / Policy v2" (from an earlier session).

Filled the add-consent form (type "General treatment", policy version
"v2", unchecked "Consent given") and submitted - a third row appeared
immediately: "General treatment / Policy v2 / Declined" (red badge), the
two earlier rows unchanged above it - confirmed this is a genuine
accumulate, not a replace, live against a real request. Confirmed all
three rows directly in Postgres
(`SELECT consent_type, policy_version, consent_given, witness_name FROM
consent_records WHERE patient_id = ...`) - `general_treatment/v1/true/
Jane Witness`, `privacy_data/v2/true/null`, `general_treatment/v2/false/
null` - matching the UI exactly.

Logged out and back in as a real `demo-provider`, navigated directly to
the same appointment's encounter page. The Consent section showed the
same three records (read-only) with no add-consent form beneath them -
confirming the write gate (`front_desk`/`clinic_admin` only) held for a
genuinely different login, consistent with allergies' own write gate
from phase H's verification.

## Verified this session - frontend phase J

Rebuilt `node-bff` after `npm run build` succeeded clean. Confirmed via
Postgres first that the test encounter (appointment `583d8fbb-f0fa-4eae-
8609-18726b5724f0`) was not yet signed, giving a clean slate to exercise
the whole sign-and-lock flow live rather than just its pre-locked state.

Logged in as `demo-provider`, opened the encounter page, typed
"J02.9, R05" into the new ICD-10 field and saved - "Saved." confirmation,
value persisted on reload. Filled the prescription line's new phase-11
fields (route "oral" via the new select, frequency "three times daily",
duration "10 days", quantity 30, refills 0, status "active") and saved -
confirmed all six values directly in Postgres
(`SELECT ... FROM prescriptions WHERE encounter_id = ...`), matching the
form exactly, and `icd10_codes` on the encounter row matching too.

Clicked "Sign encounter" - the page updated with no reload: a green
"Signed <timestamp>" badge appeared next to "Note", the Save/Sign buttons
vanished, every note textarea/input turned visibly disabled, a new
"Addenda" section appeared ("No addenda yet." + an add form), and the
Prescriptions panel below lost its Remove/+Add/Save controls with every
field now disabled too - all from a single mutation's cache invalidation,
no manual refresh. Confirmed `encounters.signed_at`/`signed_by` both set
in Postgres. Scrolled to the (still-locked) prescriptions panel after the
refetch settled and confirmed it stayed locked, not just transiently
right after the sign click.

Typed a realistic addendum ("Patient called back - throat culture came
back positive for strep, starting amoxicillin as prescribed.") and
submitted - it appeared immediately in the Addenda list with its own
timestamp, the add form cleared. Confirmed the row in Postgres
(`encounter_addenda`, correct `text`, `author_id` populated) - a genuine
persisted addendum, not just optimistic UI state.

## Verified this session - frontend phase K

Rebuilt `node-bff` after `npm run build` succeeded clean. Logged in as a
real `demo-clinic-admin` and opened `/clinic-admin/providers` - the new
license #/expiry/employment fields render on the create form, and "Dr.
Demo Provider" (data from an earlier session) already shows "full time ·
license MD-2026-4471" in its summary line, confirming the read path
works against real pre-existing data before touching anything new.

Opened "Dr. Live Test"'s new "Signature" panel (no signature on file yet)
and uploaded a real 68-byte 1x1 PNG through the actual file input (via
the file-upload tool targeting the input's own element, not a simulated
click) - the preview `<img>` rendered immediately with no page reload,
and a "Remove" link appeared. Confirmed directly in Postgres
(`signature_filename`/`signature_content_type` both set,
`e72ea114-...png` / `image/png`) and on the actual mounted Docker volume
inside the `spring-boot-api` container (`ls /app/uploads/provider
-signatures/` showed the same 68-byte file) - a genuine end-to-end
upload through the real browser -> node-bff -> spring-boot-api ->
FileStorageService path, not a mocked one.

Clicked "Remove" - confirmed in Postgres that `signature_filename` went
back to null, and confirmed on the container's filesystem that the file
itself was actually gone from `/app/uploads/provider-signatures/` (the
directory listing came back empty) - a real delete, not just the
database reference being cleared while an orphaned file lingered on disk.

## Verified this session - frontend phase L

Rebuilt `node-bff` after `npm run build` succeeded clean. Logged in as a
real `demo-clinic-admin`, opened `/referrals` - the new nav link renders,
and the list showed two real pre-existing referrals from an earlier
session (an external Dermatology referral, and a completed/urgent
internal Cardiology one to "Dr. Live Test"), both with patient/provider
names correctly resolved.

Opened "+ New referral", filled a real internal referral (patient "Demo
Patient", referring provider "Dr. Demo Provider", receiving provider
"Dr. Live Test", specialty "Dermatology", a real reason) and submitted -
the new referral appeared at the top of the list (newest-first), the
form closed automatically. Confirmed nothing in Postgres was accidentally
duplicated or mis-owned - a clean third row alongside the two existing
ones, not a replacement.

Expanded the new referral's row, changed its status to "accepted" and
added notes, then saved. Native `<select>` + rapid-fire browser_batch
actions proved unreliable here (a couple of attempts either left the
select unchanged or, worse, had a stray click land on a nav link and
navigate away before the save fired - caught immediately by checking
Postgres after each attempt rather than trusting the click alone).
Switched to locating the Status select, Notes textarea, and Save button
by `find` and clicking them by element reference instead of raw
coordinates, which resolved it: confirmed in Postgres
(`status = 'accepted'`, `notes = 'Scheduled patient to see Dr. Live Test
next Tuesday.'`) and confirmed the same in the UI itself afterward
("Saved." shown, values retained).

## Verified this session - phase 16: real payment gateway + refund flow + invoice PDF

Rebuilt `spring-boot-api` (`docker compose up -d --build spring-boot-api`,
then `--force-recreate`) and confirmed `V16__payment_gateway_and_refunds.sql`
applied cleanly via `docker compose exec postgres psql` against
`flyway_schema_history` (`version 16, success = t`).

Logged in as a real `demo-front-desk` through the actual browser (real
nginx -> node-bff -> spring-boot-api chain, not a direct curl), then
drove every new endpoint from the browser's own authenticated `fetch`
(session-cookie-based, same "reachable from the console today" pattern
phase 8 already established for its own backend-only endpoints):

- `POST /api/appointments/{id}/payments` with `method: "card"` on a real
  pre-existing appointment (`F6A604`) - the response carried a genuine
  `gatewayTransactionId` (`mock_chg_3915d6c8-...`) and
  `gatewayStatus: "succeeded"`, confirming `PaymentService` actually calls
  `MockPaymentGatewayClient.charge` before saving, not just wiring that
  compiles.
- `POST /api/payments/{id}/refund` for 25.00 against that same 75.00
  payment - a genuine `gatewayRefundTransactionId` (`mock_rfd_1694b107-...`)
  came back, `GET /api/payments/{id}/refunds` listed exactly that one row,
  and re-fetching the payment via `GET /api/appointments/{id}/payments`
  confirmed `gatewayStatus` had flipped to `"partially_refunded"` - the
  running-sum/status-update logic working against real persisted state,
  not just the pure-unit `RefundServiceTest` mocks. A follow-up refund
  attempt for 60.00 (which would total 85.00 against the 75.00 payment)
  correctly 400'd with `"Refund total 85.00 would exceed this payment's
  own amount 75.00"`.
- Fetched that appointment's already-existing invoice (from an earlier
  session), then recorded a second payment with that invoice's real id as
  `invoiceId` - the saved payment's own `invoiceId` came back matching,
  confirming `AppointmentPaymentController`'s validation resolves and
  accepts the appointment's genuine invoice. A follow-up attempt with a
  freshly-`crypto.randomUUID()`'d (definitely-wrong) `invoiceId` correctly
  400'd with `"invoiceId does not match this appointment's own invoice"`.
- `GET /api/appointments/{id}/invoice/pdf` through the same authenticated
  fetch returned `200`, `Content-Type: application/pdf`, 1157 bytes, and
  the response's own first four bytes decoded to the literal string
  `"%PDF"` - a real PDF served through the full stack including
  `node-bff`'s `relayUpstreamResponse`, not just the pure-unit
  `InvoicePdfServiceTest`'s in-process rendering.

No frontend UI exists for any of this yet (deliberately scoped out this
phase, per CLAUDE.md's own phase-16 write-up) - every check above went
through a raw authenticated `fetch()` from the browser console, the same
verification shape phase 8 used for its own backend-only endpoints.

## Verified this session - phase 17: real email delivery

Rebuilt `spring-boot-api` (`docker compose up -d --build spring-boot-api
mailpit`, then `--force-recreate` once the image finished) - the
`pom.xml` change (new `spring-boot-starter-mail` dependency) invalidated
the Dockerfile's cached `mvn dependency:go-offline` layer, so this build
took noticeably longer than a source-only rebuild; confirmed both
containers came up healthy (`mailpit` has its own healthcheck, reached it
within a few seconds).

Confirmed Mailpit's own REST API was reachable and genuinely empty first
(`GET :8025/api/v1/messages` -> `"total":0`) before doing anything, so the
message that showed up afterward couldn't be left over from an earlier
session. Booked a real **guest** appointment through the full nginx ->
node-bff -> spring-boot-api chain (`POST /api/appointments/guest`, a
`permitAll` endpoint, so a plain `curl` already exercises the real
end-to-end path with no session needed) with a genuine `contactEmail`,
reference `01AEA4` at a real 09:00 UTC slot.

Queried Postgres directly and confirmed the outbox row
`NotificationWorker` would pick up already carried a real, correctly
-populated payload - `{"startTime":"2026-09-25T09:00:00Z","clinicName":"Demo
Clinic","appointmentRef":"01AEA4"}` - and had already flipped to
`status = 'sent'` well within the worker's own 10-second poll interval.

Queried Mailpit's REST API again and found exactly one message, genuinely
delivered via real SMTP (not inspected via a mock or a log line):
`From: no-reply@clinicops.local`, `To: phase17-tester@example.test`,
`Subject: "Your appointment is confirmed - 01AEA4"`, body rendering as
`"Your appointment at Demo Clinic is confirmed for Sep 25, 2026, 9:00:00
AM UTC. Reference: 01AEA4"` - every dynamic value (clinic name, formatted
date/time, reference) correctly substituted from the real payload, not a
placeholder. Delivered end to end in under 4 seconds
(`Created: 17:14:24.295Z` against a `bookedAt` of `17:14:20.537Z`).

Did not separately re-verify the cancellation/reschedule/lab-result
-ready templates live this session (each needs a real `Patient` row with
an email on file - a guest booking's `contactEmail` is deliberately never
persisted, so cancelling a guest appointment sends nothing, by existing
design predating this phase) - those three render through the exact same
`SmtpEmailSender.send` code path already exercised live above, and are
each covered by their own passing `SmtpEmailSenderTest` case (real
Jackson serialize/deserialize round-trip, not a hand-built payload
string) - judged sufficient rather than standing up a full staff-login
browser flow purely to re-prove the same rendering method a fourth time.

## Archived full phase write-ups (moved from CLAUDE.md, 2026-09-28 size-reduction pass)

CLAUDE.md grew past its own stated 150K-char target (289,570 chars at the time of this split). The design write-ups below for older, completed phases are moved here **verbatim** - nothing summarized or dropped, the identical text that used to live in CLAUDE.md under the same headers - to bring the working file back under budget. CLAUDE.md itself keeps each phase's header plus a short summary and a pointer back here.

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
- **UX gap noted here, closed later the same session by Frontend phase J**
  (below) - at the time this backend phase was built, `provider/
  Encounter.jsx` (frontend phase E) had no dedicated "locked"/"signed" UI,
  no sign button, and no addendum form.

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
500'd, "Current request is not a multipart request" - `node-bff`'s
`forwardToApi` always JSON-re-serialized `req.body`, but `express.json()`
never populates it for a multipart request, silently dropping the file
bytes before spring-boot-api. Fixed by streaming the raw request through
for a multipart content-type instead (`isMultipartRequest`, exported,
unit-tested) - latent since the proxy layer was first built, never
exercised until this phase's own upload.

**Live-verified** - see CLAUDE-history.md's "phase 13" entry (a real PNG
through the actual browser path, byte-for-byte, confirmed on the mounted
volume via `docker exec`).

Frontend UI for this (license/employment fields, signature upload
/preview) followed later - see "Frontend phase K" below.

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

## Phase 19: clinic-admin analytics dashboard

Built 2026-09-24 at the user's direct request ("modify the dashboard to
have analytical graphs and stat cards"), out of numeric order - phases
16-18 are still only sketched. Two questions were put to the user before
building anything: which dashboard(s) (answered clinic-admin only) and
whether to derive charts from data already being fetched or build real
backend aggregation (answered: real aggregation, for genuine historical
trends) - both matching this project's own "ask before building" pattern.

- **`com.clinicops.analytics`** (new package) - a single bundled
  `GET /api/clinic/analytics?days=` endpoint (`ClinicAnalyticsController`,
  `clinic_admin`-only, `days` defaults to 30 and is clamped to [1,180]),
  returning `ClinicAnalyticsSummary { appointmentVolume, revenue,
  statusBreakdown, appointmentsByProvider }` - the same "wrapper record for
  one always-together response" shape as `AppointmentSeriesResult`/
  `EncounterWithPrescriptions`, not four separate endpoints. No new
  migration - this is pure read aggregation over `appointments`/`payments`,
  which already had everything needed. No dedicated service bean either -
  the controller composes two repositories directly, the same "controller
  calls repositories directly" precedent phase 5's CRUD already set, since
  there's no cross-cutting business logic here, just composition.
  - **`appointmentVolume`/`revenue`** - daily buckets (native
    `GROUP BY CAST(... AS date)` queries) since `since` (`Instant.now()
    - days`), UTC day boundaries - same convention as every other day-math
    in this app (`SlotGenerator`, `AvailabilityController`,
    `my-schedule`), since there's still no per-clinic timezone (phase 18).
    Volume is keyed off `bookedAt` (a booking-activity trend), deliberately
    not the slot's own start time - answers "how much is this clinic
    growing," not "how full is the calendar," and needs no join to slots.
    Revenue sums `payments.amount` (covers both appointment and lab-order
    payments alike, `fee_auto_charged` rows included - same table, same
    "whatever's in here got collected" reading every other consumer of
    `Payment` already uses).
  - **`statusBreakdown`** - a current snapshot (not time-windowed): every
    appointment this tenant has, grouped by its live status.
  - **`appointmentsByProvider`** - volume per provider since `since`; the
    frontend resolves `providerId` to a name from its own already-fetched
    provider list rather than the backend joining/returning names.
- **Frontend: real charts (Recharts), not client-computed ones** - matches
  what was asked for. Before writing any chart code, the dataviz skill's
  procedure was followed in full, including actually running its
  `validate_palette.js` against candidate colors rather than eyeballing
  them (see `components/analytics/chartPalette.js`'s own comment for the
  specific numbers). Two real findings from that step shaped the design:
  - This app's own live theme tokens turned out to be the *wrong* source
    for chart colors, not just an unvalidated one - `--brand` unchanged in
    dark mode reads under 3:1 against a dark chart surface (it's tuned for
    button/badge backgrounds, a different contrast job), and the light
    -theme `--success`/`--danger` pair fails CVD separation outright (ΔE
    5.0, red/green, below even the 6-8 "legal only with secondary
    encoding" floor). So charts use their own small separately-validated
    palette (one primary hue per theme, a shared teal, and a primary
    +danger pair for the one chart that puts two colors on screen at
    once) instead of reading `--brand`/`--danger` live - deliberately
    NOT tied to a clinic's own custom branding color either, since a
    per-tenant hex can't be re-validated at runtime.
  - The status-breakdown panel was originally sketched as one hue per
    status (7 colors) until the validator ruled out the natural
    green-for-"completed" choice; it shipped instead as a genuine
    status-outcome encoding - primary for every in-progress/completed
    status, danger for the two "lost" ones (`no_show`/`cancelled`) - which
    reads as more informative than arbitrary categorical color anyway, not
    just a workaround.
  - `AppointmentVolumeChart`/`RevenueChart` (single-hue area charts),
    `StatusBreakdownChart` (horizontal bar, two-color status-outcome
    coding, a legend since 2+ colors are on screen, direct value labels at
    each bar tip), `ProviderUtilizationChart` (single-hue bar, capped to
    the busiest 8 providers) - all built to the skill's mark specs (2px
    lines, ~10% area-fill opacity, rounded bar ends, recessive
    hairline gridlines, a 2px surface-color ring on active dots) and its
    labeling rule (label the tip/endpoint, never every point - the
    30/90-day trend charts thin their x-axis ticks instead of rendering
    one per day). `ChartTooltip.jsx`/`ChartCard.jsx` are shared across all
    four rather than each chart styling its own.
  - Every chart is fully translated (chart titles/subtitles, legend text,
    status-axis labels, the day-range toggle) through the same `t('...')`
    pattern the rest of the app's language sweep uses -
    `clinicAnalytics.*` in `locales/en.json`/`am.json`. Day-axis labels go
    through `lib/format.js`'s `formatDayLabel`, which - not incidentally -
    is the exact function the "fix the two translation bugs" pass just
    before this phase made locale-aware; this phase is the first thing
    that visibly exercises that fix in a chart context.
  - A new day-range toggle (7/30/90 days, same plain segmented-control
    shape as `ThemeToggle`/`LanguageToggle`) re-queries the analytics
    endpoint; a new "Revenue ({{days}}d)" stat card sits alongside the
    existing five (all unchanged) rather than replacing anything.
  - New dependency: `recharts@2`. Adds real weight to the bundle (roughly
    doubles it, ~500KB to ~910KB minified) - not addressed here
    (code-splitting would be the natural fix, a separate concern from
    this phase's own scope).
- **Live-verified against the real running stack** - real `demo-clinic
  -admin` login; all four charts and the new stat card confirmed rendering
  correct data in both Light and Dark themes (the validated dark-mode
  palette swaps in correctly - lighter blue/red, not the raw light-mode
  hexes) and in both English and Amharic (including the status-axis
  category labels and the interpolated "Revenue (30 days)" /
  "N appointments booked" strings); the 7/30/90-day toggle confirmed
  actually re-fetching and changing every panel's numbers, not just
  relabeling stale data.
- **No `TenantIsolationIntegrationTest` addition** - `/api/clinic/analytics`
  resolves entirely from the caller's own token (`TenantContext.require()`),
  same "no cross-tenant vector to test" reasoning that already excluded
  `ClinicSettings`/branding/`my-schedule` from that suite.
- **Not done this phase**: front_desk/provider/platform_admin/patient
  dashboards keep their existing plain-stat-card shape - the user
  explicitly scoped this to clinic-admin only. `npm test` (24/24)
  confirmed unaffected.

## Phase 16: real payment gateway + refund flow + invoice PDF

Sketched 2026-09-21, built 2026-09-24. Three questions were put to the
user when this was first sketched (gateway scope, refund granularity) plus
one more before writing any code this session (which PDF library) -
matching this project's usual "ask before building" convention. Built
essentially exactly as sketched; no design forks changed between the
sketch and the build. New migration `V16__payment_gateway_and_refunds.sql`.

- **Pluggable gateway, mock-only for now** - new `com.clinicops.paymentgateway`
  package: `PaymentGatewayClient` (`charge(BigDecimal, String)`/
  `refund(String, BigDecimal)`, both returning a small `ChargeResult`/
  `RefundResult` record) mirrors `NotificationSender`'s own "interface +
  swap the bean later" shape. `MockPaymentGatewayClient` (the only bean
  today) always fabricates a `mock_chg_<uuid>`/`mock_rfd_<uuid>` id and
  returns `succeeded` immediately - no real vendor integrated, no
  third-party account needed. `PaymentGatewayException` exists for a real
  implementation to throw later (maps to 502 in every controller that can
  trigger it, same "genuinely upstream" reasoning `KeycloakAdminException`
  already established) - the mock never throws it.
  - **The asynchronous-shape design decision held**: `ChargeResult`/
    `RefundResult` carry a `status` string (pending/succeeded/failed) even
    though the mock always returns `succeeded` synchronously - a real
    vendor's webhook-confirmed `pending` state is a value this shape
    already accepts, not a redesign.
  - **A design call made in this session, not asked directly**: which
    payments actually get routed through the gateway. Every payment
    recorded via `AppointmentPaymentController`/`LabOrderPaymentController`
    now calls `PaymentGatewayClient.charge` unconditionally (regardless of
    `method` - `cash`/`card`/`mobile_money`/etc. all go through it alike,
    since this app's `method` field was never an enforced allow-list to
    split behavior on). The pre-existing `fee_auto_charged` shortcut in
    `CancellationService`/`RescheduleService`/`LabOrderCancellationService`
    deliberately bypasses `PaymentService` entirely (unchanged code, still
    saves a `Payment` directly) - that row was never a genuine gateway
    charge before this phase and still isn't, so its `gatewayTransactionId`/
    `gatewayStatus`/`invoiceId` all stay null, same as before.
  - **`PaymentService`** (new) - the one place `charge` is actually called;
    a dedicated bean (not plain controller-calls-repository CRUD) since
    this is genuine cross-cutting logic shared identically by both payment
    controllers, same reasoning `ClinicSettingsService`/`InvoiceService`
    already established for themselves.
- **Full and partial refunds** - `refunds` table (append-only, no
  update/delete anywhere, same audit-row shape as
  `appointment_cancellations`/`consent_records`): `payment_id`, `amount`,
  optional `reason`, `gateway_refund_transaction_id`, `refunded_by`,
  `created_at`. `RefundService` (new) computes the cumulative
  refunded-so-far via `RefundRepository.sumAmountByPaymentId` (a running
  -sum check, not expressible as a plain DB CHECK - same "compute in code"
  precedent `FeeCalculator`/`InvoiceService` set) and rejects
  (`RefundExceedsPaymentException`, 400) anything that would push the
  total past the payment's own `amount`.
  - **Refunding a payment that was never gateway-charged works too** - a
    cash payment, or a `fee_auto_charged` row, has no
    `gatewayTransactionId` to refund at a (mock, for now) vendor, so
    `RefundService` only calls `gatewayClient.refund(...)` when one
    exists; either way, a `Refund` audit row is written and
    `Payment.gatewayStatus` flips to `refunded`/`partially_refunded`. This
    makes `gatewayStatus` the one general lifecycle field covering every
    payment this phase touches, not just the electronically-charged ones -
    a design call made without asking, since the sketch's own allow-list
    (`pending/succeeded/failed/refunded/partially_refunded`) already
    implied refund states reachable regardless of origin.
- **`payments` gains `invoice_id`** (nullable FK to `invoices`) - closes
  the "no link from a payment back to the invoice it's paying down" gap.
  `CreatePaymentRequest` gained an optional `invoiceId`; both payment
  controllers validate it against that same appointment's/lab order's own
  already-issued invoice (`InvoiceRepository.findByAppointmentIdAndTenantId`/
  `findByLabOrderIdAndTenantId`) before accepting it - an id belonging to
  a different owner or tenant 400s, never silently ignored or
  cross-linked. Every payment recorded before this phase stays
  `invoice_id = null`, already the correct meaning.
- **New endpoints, owner-agnostic** - `com.clinicops.payment.PaymentController`
  (new): `POST /api/payments/{id}/refund` (`front_desk`+`clinic_admin`,
  same gate as recording a payment; no idempotency key the way booking
  has one, since a refund is always a distinct, deliberate action) and
  `GET /api/payments/{id}/refunds` (same 3-role read gate as payments) -
  addressed directly by the payment's own id, not nested under
  `/api/appointments/...`/`/api/lab-orders/...` the way recording one is,
  since `RefundService` only ever needs the payment row itself.
  `PaymentRepository` gained `findByIdAndTenantId` to support this (the
  two existing finders are both owner-scoped, not payment-id-scoped).
- **Invoice PDF, generated on demand, never persisted** - `GET
  /api/appointments/{id}/invoice/pdf` / `GET /api/lab-orders/{id}/invoice/pdf`
  (same 3-role read gate as the JSON invoice endpoint) render straight
  from the already-issued (immutable) `Invoice` row plus
  `ClinicSettingsService`'s branding/contact-info for a letterhead -
  nothing to gain from persisting bytes for something regenerable at zero
  cost from data that can't change, and it avoids a second consumer of
  the phase-13 uploads volume for an unrelated purpose.
  `InvoicePdfService` (new, `com.clinicops.invoice`) resolves the
  patient's name (via the appointment's `patientId`, falling back to a
  guest's `contactName`, exactly mirroring `AppointmentDetail.jsx`'s own
  resolution order) and the owner's own reference number
  (`appointmentRef`/`orderRef`) alongside the clinic's letterhead and the
  subtotal/tax/total breakdown.
  - **PDF library: OpenPDF** (asked directly, answered - the recommended
    LGPL/MPL iText-4 fork, over Apache PDFBox) - `com.github.librepdf:openpdf:1.3.42`,
    confirmed resolvable via a real `mvn dependency:resolve` before
    writing any rendering code, not assumed from memory. Base package is
    still `com.lowagie.text.*` (inherited from its pre-fork iText 2/4
    lineage) - genuinely counterintuitive if searched for under
    `com.github.librepdf`, confirmed by reading the actual downloaded jar
    rather than guessed.
  - **`node-bff` needed zero changes** - `forwardToApi`'s
    `relayUpstreamResponse` already reads any upstream response via
    `arrayBuffer()`/`Buffer` and relays its real headers unmodified,
    content-type-agnostic; this is the identical code path phase 13's
    provider-signature `GET` (raw image bytes) already proved works for
    binary responses - confirmed by reading that code before assuming a
    new proxy bug the way phase 13's own *request*-side multipart bug was
    found, not found again here.
- **Tests**: `PaymentServiceTest`/`RefundServiceTest`/`InvoicePdfServiceTest`
  (new, pure Mockito - `InvoicePdfServiceTest` genuinely renders a real
  PDF via OpenPDF against mocked repositories/settings and asserts the
  actual `%PDF` magic header, not just that no exception was thrown) all
  pass locally, no Testcontainers involved - 11 cases total.
  `PaymentControllerIntegrationTest` (new, 5 cases: full refund, two
  partial refunds reaching the full amount, over-refund 400, role gate,
  cross-tenant 404) plus new cases added to `AppointmentPaymentIntegrationTest`
  (invoice linking, mismatched-invoice 400) and `InvoiceControllerIntegrationTest`
  (both owner types' PDF endpoints, pre-generation 404) - same
  Testcontainers/Windows-npipe wall as every other `AbstractIntegrationTest`
  subclass, confirmed via a clean `mvn clean test-compile` and by
  confirming every other pre-existing, unrelated integration test class
  fails identically in the same run (`Could not find a valid Docker
  environment`) - not a regression from this phase's own code.
- **Live-verified against the real running stack** - see CLAUDE-history.md's
  "Verified this session - phase 16" for the full write-up.
- **The frontend was picked up the same session** - see "Frontend phase
  P: phase-16 payment/invoice UI" under "## Frontend" below (the invoice
  PDF download link, the Payments panels' new refund action, and a real
  pre-existing role-gating gap found and fixed along the way).

## Phase 17: real email delivery

Sketched 2026-09-21 as "real email + SMS delivery" via SendGrid+Twilio;
picked up and built 2026-09-24 with a materially revised scope, pinned via
two direct questions rather than assumed: the user has no SendGrid/Twilio
accounts and asked for a locally-installed open-source mail service
instead, and asked to scope this phase to **email only** - SMS/Twilio
deferred entirely, not just deprioritized. `com.clinicops.notification.NotificationSender`
was already an interface (`send(Notification)`) with exactly one
implementation, `LoggingEmailSender` - this phase fills that existing seam
with a real one rather than creating a new mechanism.

- **Real SMTP delivery via Mailpit, not a real vendor** - `SmtpEmailSender`
  (new, replaces `LoggingEmailSender` outright - `NotificationWorker`
  still takes exactly one `NotificationSender` bean, unchanged) uses
  Spring's `JavaMailSender` (`spring-boot-starter-mail`, new dependency)
  against a new `mailpit` service in `docker-compose.yml`
  (`axllent/mailpit` - open-source, MIT-licensed, actively maintained; SMTP
  on `:1025`, a web UI at `:8025` to see every message the app has ever
  sent). This is genuinely real SMTP delivery from the app's own point of
  view, not a stub - swapping in a real vendor later (SendGrid or
  otherwise) is a config change (`spring.mail.host`/`port`, plus
  `username`/`password` for one needing auth), not a rewrite of
  `SmtpEmailSender` itself.
- **SMS deliberately out of scope, not partially built** - `Notification.channel`
  still only ever gets set to `"email"`, so `NotificationWorker` was
  **not** changed to a per-channel dispatch map the original sketch
  proposed - that abstraction has nothing to dispatch to yet and would
  just be dead code; add it if/when a real second channel actually
  exists, per this app's own "no premature abstraction" convention.
- **Real message templates, not empty-payload delivery** - `Notification.payload`
  had stayed `"{}"` since phase 1 `V1__init.sql`; now each of the four
  existing call sites (`AppointmentWriter`, `CancellationService`,
  `RescheduleService`, `LabOrderStatusService.review`) writes a small
  typed record (`AppointmentConfirmedPayload`/`AppointmentCancelledPayload`/
  `AppointmentRescheduledPayload`/`LabResultReadyPayload`, all new,
  `com.clinicops.notification`) via a new `NotificationPayloadWriter.toJson`
  helper (wraps Jackson's checked `JsonProcessingException` as unchecked -
  a plain bundle of primitives/`Instant`/`BigDecimal` can't realistically
  fail to serialize). `SmtpEmailSender` deserializes by `notification.type`
  and renders real subject/body text per type - an unrecognized `type`
  falls back to a generic message rather than throwing, so a future new
  notification type added without an email-copy update never breaks
  delivery. `Notification.getPayload()` values written before this phase
  (`"{}"`) were confirmed already all `status = 'sent'` in the real
  database before this was built, so `NotificationWorker` never re-reads
  them against the new deserializer - no backfill needed, checked rather
  than assumed.
- **No hard external dependency after the scope revision** - the original
  sketch's blocker ("needs real SendGrid and Twilio accounts before this
  can be built and tested at all") no longer applies: Mailpit needs no
  account, no API key, and no real inbox, so this phase was buildable and
  fully live-verifiable end to end with zero external services.
- **`node-bff`/frontend needed zero changes** - notification delivery has
  always been entirely server-side (the outbox pattern's whole point);
  nothing about this phase is reachable from or visible in the browser
  except its real-world effect (an email actually arriving somewhere).
- **Tests**: `SmtpEmailSenderTest` (new, pure Mockito - a mocked
  `JavaMailSender`, real Jackson serialization/deserialization end to
  end, one case per notification type plus a fee-omitted-when-zero case
  and an unrecognized-type fallback case) and `NotificationPayloadWriterTest`
  (new) both actually run locally, no Testcontainers involved -
  `NotificationWorkerTest` (existing) needed no changes at all, since it
  only ever mocks `NotificationSender` generically. 10/10 passing.
- **Live-verified against the real running stack** - see CLAUDE-history.md's
  "Verified this session - phase 17" for the full write-up (a real
  booking's confirmation email actually arriving in Mailpit's own web UI,
  with the real clinic name/appointment reference/time rendered into the
  subject and body - not just a log line, and not just unit-tested
  rendering).

## Phase 18: per-clinic timezone

Sketched 2026-09-21, built 2026-09-24 - closes the "No per-clinic
timezone" known gap. Built essentially exactly as sketched; no design
forks changed between the sketch and the build. New migration
`V15__clinic_timezone.sql`.

- **A new nullable `timezone` column on `clinic_settings`** (the
  *settings* column group, alongside tax rate/fees/notice-hours - not
  branding), a valid IANA zone id (e.g. `"Africa/Addis_Ababa"`), validated
  in `ClinicSettingsService.updateSettings` via `ZoneId.of(...)` catching
  `DateTimeException` (a 400 with a clear message on an invalid zone) -
  same "backend-defined bounded set validated in code" convention as
  `Allergy.severity`/`Prescription.route`, just checked against `ZoneId`'s
  own real zone table instead of a small fixed list.
  `ClinicSettingsService` gains a platform-default zone
  (`clinicops.clinic.default-timezone: UTC` in `application.yml`,
  constructor-injected alongside its existing tax/fee/notice-hour
  defaults) - same coalesce-the-override shape already used for
  everything else there. `EffectiveClinicSettings`/`ClinicSettingsDefaults`/
  `ClinicSettingsOverrides`/`UpdateClinicSettingsRequest` all gained a
  `timezone` field to carry it through `GET`/`POST /api/clinic/settings`'s
  existing `{overrides, effective, defaults}` shape - no new endpoint.
- **One shared lookup, not five independent ones** -
  `ClinicSettingsService.resolveTimezone(tenantId)` (new; resolves
  straight to a real `ZoneId`, not just the raw string) is the single seam
  every call site that used to hardcode `ZoneOffset.UTC` now calls:
  `SlotGenerator.generateForDay` (takes a `ZoneId` parameter instead),
  `SlotGenerationService` (both `ensureSlotsGenerated`'s "today" and
  `ensureSlotExists`'s reverse-lookup day), `AvailabilityController`'s own
  "today"/horizon math, and `AppointmentController.mySchedule`'s "today"
  math - the five call sites confirmed by grep when this was first
  sketched, all updated, no others found once the search was repeated at
  build time. Window-boundary math changed from `LocalDateTime.toInstant
  (ZoneOffset)` to `LocalDateTime.atZone(zone).toInstant()` - the former
  only accepts a fixed offset and can't express a region-based zone's own
  rules correctly, the latter can (verified with a real non-UTC test case,
  see below - not just a type-signature change).
  `AvailabilityController.availability` resolves by the `clinicId` path
  param directly (this endpoint is `permitAll` - a guest/patient caller
  has no `TenantContext` to read), everywhere else resolves via
  `TenantContext.require()` like every other staff-scoped call already
  does.
- **`Slot`/`Appointment` needed no migration** - both already store a
  plain `Instant`; only the *generation* and *day-boundary* math needed a
  zone, confirmed true once built, not just assumed.
- **Existing slots are left untouched, as asked** - no backfill of
  `slots.start_time`/`end_time` was written or needed; a slot already in
  the table keeps whatever UTC-interpreted instant it was generated with,
  regardless of a clinic setting its own zone afterward.
- **The frontend display question the original sketch flagged as
  genuinely open is resolved by construction, not by a decision made
  here**: frontend phase N's own timezone-display picker (built
  2026-09-24, the same session, once this phase unblocked it) is exactly
  the mechanism that answers "clinic's zone or the viewer's own" -
  per-viewer, not a single app-wide default - so this phase didn't need to
  pick one.
- **The phase-N "reconciliation" is done**: `timezone` is now exposed
  everywhere that preference picker will need to read it, not just the
  `clinic_admin`-only settings endpoint - `ClinicBrandingView`
  (`GET /api/clinic/branding`, readable by `clinic_admin`+`front_desk`+
  `provider`) and the public `ClinicDirectoryView` (`GET /api/clinics`,
  `permitAll` - a logged-out patient/guest can read it too) both carry the
  fully-resolved value now. Non-sensitive operational data, not a privacy
  concern - same reasoning already applied to a clinic's own display name/
  branding colors being public.
- **`SlotGeneratorTest`** gained `interpretsWorkingHoursInTheGivenNonUtcZone`
  - Africa/Addis_Ababa (UTC+3, no DST, chosen specifically because local
    midnight isn't UTC midnight, the case most likely to surface an
    off-by-one), asserting the generated instant lands exactly 3 hours
    before its local wall-clock time. 7/7 passing locally (pure unit, no
    Testcontainers needed).
- **`ClinicSettingsIntegrationTest`** gained `timezoneDefaultsToUtcAndCanBeOverridden`
  (UTC with no row, override round-trips through both the settings and
  branding endpoints) and `invalidTimezoneIsRejected` (400 on a bogus
  zone id). **`ClinicControllerIntegrationTest`** gained
  `publicDirectoryCarriesTheClinicsResolvedTimezone`. Same Testcontainers
  wall as every other `AbstractIntegrationTest` subclass - confirmed to
  fail only there via a clean `mvn test-compile`, not run to green here.
- **Live-verified against the real running stack** - confirmed via
  `docker compose exec postgres psql` that `V15` applied cleanly
  (`flyway_schema_history` shows it `success = t`) and the `timezone`
  column exists on `clinic_settings`; confirmed via a direct `curl` to
  `GET /api/clinics` through the real nginx/node-bff/spring-boot-api chain
  that every existing clinic now reports `"timezone":"UTC"` - the
  end-to-end resolve-and-serialize path working, not just unit-level
  logic. `npm test` (24/24) unaffected.
- **The frontend timezone-display picker itself (browser-local/clinic's/
  manual)** - this phase only removed its one backend dependency; the
  picker itself was built the same session, once this was done - see
  "Frontend phase N" above.

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
  (`@Scheduled(fixedDelay = 10_000)`, `MAX_ATTEMPTS = 5`, pulls pending
  rows, retries on failure, marks `failed` once exhausted), adapted for
  this app's richer `Notification` shape. `LoggingEmailSender` still just
  logs (no real email/SMS provider), but the outbox no longer just
  accumulates unread rows forever. `NotificationWorkerTest` (pure
  Mockito, runs locally) covers send/retry/permanent-failure; passing.
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
- All three compile clean, failing **only** via the pre-existing
  Testcontainers/Windows-npipe wall (`Failures: 0`) - not a regression.

**PHI access audit log** (built 2026-09-20) - closes the "PHI-access audit:
deferred" gap pinned 2026-09-12. No reference-project precedent - two
scoping questions were put to the user before writing code: **what counts
as PHI** (answered "patient + clinical records" - `PatientController` plus
encounters/prescriptions/lab orders, explicitly not `fee_policies`/
payments) and **reads, writes, or both** (answered "both").

- **`com.clinicops.phiaudit`** (new package) - `PhiAccessLog` (extends
  `BaseTenantEntity`; `patientId` nullable for a guest-channel booking
  with no `Patient` row), `PhiAccessAuditService` (`logRead`/`logWrite`,
  resolves the actor via `CurrentUserService.resolveInternalUserId` plus
  email/role off the JWT), `PhiAccessLogController`
  (`GET /api/clinic/phi-access-log`, `clinic_admin` only, optional
  `?patientId=`, most-recent-200, no pagination framework). New migration
  `V6__phi_access_log.sql`.
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

### Frontend phase A (built 2026-09-13) - full original write-up (moved from CLAUDE.md, 2026-09-28)

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

**Front-desk/provider dashboard widget gap closed** (built 2026-09-26) -
the `startTime`-on-dashboards half of the scope boundary just above,
closed for the front-desk "recent appointments" and provider "today's
schedule" widgets specifically (found in a source-level UI review of every
role's dashboard - both widgets showed only a ref + status, with no
patient name or real appointment time, making the provider one in
particular a much less useful worklist than the ordering already implied).
No tenant-wide "today" endpoint was added - that half of the original
scope boundary is still open.

- **`AppointmentWorklistView`** (new projection interface,
  `com.clinicops.appointment`) - the staff-worklist counterpart of
  `AppointmentWithSlotView` (phase B): same join-with-`slots`-for-a-real
  -`startTime` shape, plus `contactName`/`contactPhone` so a guest booking
  shows a real name with no second lookup. Backs two call sites: a new
  `GET /api/appointments/worklist` (`front_desk`/`clinic_admin`/`provider`,
  same role gate as the plain tenant-wide `GET /api/appointments`, which
  stays completely untouched - it has other consumers reading fields this
  narrower projection doesn't carry) and the *existing* `GET /api/my-schedule`,
  whose `findProviderSchedule` query and declared return type were widened
  in place from bare `Appointment` to this view (one frontend consumer,
  safe to change directly). A `patientId` case still resolves to a name
  client-side via the caller's own `GET /api/patients` id->name `Map` -
  the same pattern `front-desk/Appointments.jsx` already established, not
  a server-side join to `patients`.
- **Frontend** - `front-desk/Dashboard.jsx` switched from `useAppointments`
  to a new `useAppointmentsWorklist` hook and gained the same
  `usePatients()` + id->name `Map` lookup `Appointments.jsx` uses; each
  "recent appointments" row now shows the patient/guest name and
  `formatDateTime(a.startTime)` alongside the existing channel badge and
  `StatusPill`. `provider/Dashboard.jsx` gained the identical name lookup
  (confirmed live in source that `PROVIDER` already has `GET /api/patients`
  read access, phase 2); each "Today's Schedule" row now leads with
  `formatTime(a.startTime)` (time-first, since this list's whole point is
  visit order) then the name, reusing `frontDeskAppointments.guest` for the
  guest-fallback text rather than adding a duplicate key.
- Backend: `mvn clean test-compile` confirmed clean (no output) both right
  after the projection/endpoint change and again after adding
  `AppointmentControllerIntegrationTest.worklistCarriesRealStartTimeAndGuestContactNameForStaff`
  (role gate + `contactName`/`startTime` assertions) - the three existing
  tests referencing `findProviderSchedule`/`mySchedule`
  (`ProviderScheduleIntegrationTest`, `ProviderControllerIntegrationTest`,
  `TenantIsolationIntegrationTest`) only ever asserted `status`/`providerId`/
  HTTP status, never a bare-`Appointment`-only field, so none needed
  changes. No new `TenantIsolationIntegrationTest` case - the new endpoint
  resolves entirely off the caller's own token
  (`TenantContext.require()`), same as the plain `GET /api/appointments`
  list, which this suite already excludes from its per-resource
  id-addressed cross-tenant checks for the identical reason. `npm run
  build`/`npm test` (24/24) both clean.
- **Not verified in a real browser this session** - the Chrome browser
  extension stayed disconnected the entire session; this was confirmed
  correct via source review, a real `mvn clean test-compile`, and a real
  `npm run build`/`npm test`, not a live click-through.

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

- **A real bug found and fixed live**: a `ResponseStatusException` with no
  dedicated `@ExceptionHandler` falls through to Spring Boot's default
  `/error` JSON body, which - without `server.error.include-message:
  always` - omits the reason text, so the frontend showed a raw JSON blob
  instead of the real message. Latent since phase 5, never exercised
  until this phase's overlap test. Fixed both sides: `server.error.
  include-message: always`, and `api/client.js`'s `parseErrorBody` to
  unwrap `{message: "..."}` JSON too, not just assume plain text.
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

**Frontend phases H/I** (built 2026-09-21) - first UI for the revised
EHR-leaning backend phases 8-15, backend-only until now. One shared
`components/PatientChart.jsx` (allergies + vitals + medical history +
consent) mounts on both `front-desk/AppointmentDetail.jsx` and
`provider/Encounter.jsx` - same "share the existing page" pattern as the
clinic_admin encounter-access fix. Zero backend changes.

- Visible to any staff role reaching either page; only allergies and
  consent restrict writing to `front_desk`/`clinic_admin`; vitals/medical
  history are writable by all three. Collapsible, open by default.
- Allergies: list + add form + a per-row resolve/reactivate toggle, same
  shape as `Rooms.jsx`'s own deactivate/reactivate button.
- Vitals/history: full-replace forms, lazy-initialized from the server
  (same as `provider/Encounter.jsx`'s note form); a 404 renders a blank
  form. Vitals' BMI is read-only/derived.
- Consent (phase I): list + add form, no edit (matches
  `ConsentController`'s no-update/delete design); a checkbox records a
  decline.
- `appointmentId` is optional - a guest booking still gets vitals, no
  allergies/history/consent (patient-scoped).
- **Live-verified** - see CLAUDE-history.md's "phase H"/"phase I" entries
  (real `demo-front-desk`/`demo-provider` logins; an allergy added and
  resolved/reactivated; vitals saved with BMI computed correctly; a
  consent decline accumulating alongside two existing rows; role-gating
  held for every section).

**Frontend phase J** (2026-09-21) upgrades `provider/Encounter.jsx`
itself for phases 11/12 (no new page): an ICD-10 codes field, route/
frequency/duration/quantity/refills/status per prescription line
(allow-listed to match `EncounterService`'s constants), a "Sign
encounter" button, and - once signed - every input disables in place
(no separate read-only view) with an Addenda list + add form appearing.

- **Live-verified** - see CLAUDE-history.md's "phase J" entry (real
  `demo-provider`: ICD-10/prescription fields saved and confirmed in
  Postgres; signing flipped the UI to locked instantly with no reload; an
  addendum added and persisted; the lock held across a page refetch).

**Frontend phase K** (2026-09-21) extends `clinic-admin/Providers.jsx`
for phase 13: license number/expiry + employment status on the create
/edit forms, plus a per-row "Signature" panel - this app's first
file-upload UI. New `apiPostForm` sends a raw `FormData` body with no
manual `Content-Type` (`apiFetch` gained a `FormData` check to stop
forcing a JSON header on it). The preview `<img>` points straight at
`GET /api/providers/{id}/signature`, same-origin with the session
cookie.

- **Live-verified** - see CLAUDE-history.md's "phase K" entry (a real PNG
  uploaded live as `demo-clinic-admin`, confirmed byte-for-byte on the
  mounted volume and in Postgres; removed, genuinely deleted from disk).

**Frontend phase L** (2026-09-21) is a new `pages/referrals/Referrals.jsx`
for phase 14 - shared `/referrals` route, same reasoning as `/lab-orders`.
One page: a create form (patient/referring-provider pickers, internal
/external radio swapping receiving-provider vs. external fields) plus a
list with an inline expand-to-update row (status/priority/notes) - no
separate detail route, since referrals have one partial-update endpoint,
not a status machine like lab orders.

- **Live-verified** - see CLAUDE-history.md's "phase L" entry (a real
  referral created live, its status/notes updated inline and confirmed
  in Postgres).

**Frontend phase M** (2026-09-21) closes the last "Known gaps" item -
billing (phase 15) was backend-only until now. A new shared
`components/InvoicePanel.jsx` (same "share one component across both
owner-type pages" pattern as `PatientChart.jsx` from phases H/I) mounts on
both `front-desk/AppointmentDetail.jsx` and `lab-orders/LabOrderDetail.jsx`
- one invoice per owner, no list, no edit form once generated, matching
`AppointmentInvoiceController`/`LabOrderInvoiceController`'s own
generate-once/immutable design exactly. A 404 on the GET means "not
generated yet" (mirrors `provider/Encounter.jsx`'s own `useEncounter` 404
handling), rendered as a "No invoice generated yet." empty state with a
"Generate invoice" button instead of an error. Zero backend changes - both
controllers have covered this exact shape since phase 15.

- `api/queries.js` gained `useAppointmentInvoice`/`useGenerateAppointmentInvoice`
  and `useLabOrderInvoice`/`useGenerateLabOrderInvoice`, same
  query-plus-mutation-that-invalidates-it shape as the existing payment
  hooks right above each.
- On `AppointmentDetail.jsx` the panel is hidden once `cancelled` (same
  gate the Payments panel above it already uses); on `LabOrderDetail.jsx`
  it's hidden for `requested`/`cancelled` (same gate as that page's own
  Payments panel).
- **Live-verified** - a real `demo-front-desk` login generated an invoice
  for a `checked_in` appointment with no invoice yet (`POST
  .../invoice` 200, confirmed as a genuine new row in Postgres - subtotal
  50.00/tax 0.00/total 50.00 - not just a UI-only optimistic update); a
  pre-existing appointment invoice (35.00) and a pre-existing lab-order
  invoice with a real tax override (55.00 subtotal / 4.54 tax / 59.54
  total, confirmed as `demo-provider` on `/lab-orders/:id`) both rendered
  correctly read-only with no "Generate" button shown.

**Frontend phase N: UI language, timezone display, and theme preferences**
- **Theme built 2026-09-22. Language (i18n + English + Amharic, including
  the full per-page content sweep) built 2026-09-22. Timezone display
  still sketched, not built.** A viewer-level
  preferences layer that didn't exist in any form before this: no i18n
  library, no dark-mode variant strategy
  (`tailwind.config.js` had no `darkMode` key at all), and
  `lib/format.js`'s formatters all pass `undefined` as the locale/zone
  (deferring silently to the browser's own settings) - confirmed by
  reading the current config/formatters before writing the original plan,
  not assumed. Four questions were put to the user before pinning anything
  (storage location, language scope, theme options, timezone-picker
  options).

- **Storage: `localStorage` only, no backend changes** (asked directly,
  answered) - per-browser/device, resets in a new browser or incognito
  window; explicitly *not* the per-account/server-persisted alternative
  that was offered. `theme/ThemeProvider.jsx` (built) reads/writes it and
  wraps the **entire** app in `main.jsx`, outside `BrandingProvider` -
  unlike branding (deliberately staff-only), theme should apply to
  logged-out public pages too (booking, tracking), so this provider sits
  above the authentication boundary, not inside it. Language/timezone
  will follow the same provider shape once built.
- **Theme: Light / Dark / System - built** (asked directly, answered).
  `tailwind.config.js` gained `darkMode: 'class'`; `ThemeProvider` toggles
  a `.dark` class on `document.documentElement` (never relying on
  Tailwind's own `'media'` strategy, since an explicit Light/Dark choice
  has to be able to override the OS). For `"system"` (the default), a
  live `window.matchMedia('(prefers-color-scheme: dark)')` listener (not
  a one-time read) keeps the UI in sync if the OS theme changes
  mid-session. `components/ThemeToggle.jsx` (a plain three-button
  segmented control, matching this app's existing icon-free UI) is wired
  into both `AppShell.jsx` (staff) and `PublicShell.jsx` (public) headers.
  - **The sweep this was originally scoped to need never happened** - the
    original plan assumed every page's hardcoded `border-slate-200`/
    `bg-slate-50`/etc. utilities (228 occurrences across 43 files,
    confirmed by grep before starting) would need replacing with semantic
    tokens first. Instead, `tailwind.config.js` redefines Tailwind's own
    `slate.50/100/200/300` (the only four shades this app actually uses,
    confirmed by grep) as CSS variables, alongside converting
    `surface`/`ink`/`ink-muted`/`success`/`danger`/`warning` to the same
    `rgb(var(--x) / <alpha-value>)` pattern `brand`/`accent` already used
    (phase 5). Every existing `bg-slate-50`, `text-ink`, `bg-danger-light`,
    etc. across all 43 files now resolves through a variable that
    `index.css`'s new `:root.dark` block redefines - zero page/component
    edits. `white`/`black` were deliberately left un-themed (their only
    uses are logo/signature image-preview backdrops and white text on a
    solid-colored button, both meant to stay literal in either theme).
  - **A signed-in clinic's own custom brand color needed a theme-aware
    fix, not just a static CSS one**: `lib/color.js`'s `deriveShades`
    (used by `BrandingProvider` to compute a clinic's `-light` pill/badge
    tint from its chosen brand/accent hex) mixed toward white at a fixed
    92% regardless of theme - fine on a light card, but a near-white pill
    is nearly invisible on a dark one. `deriveShades`/`themeVars` now take
    the resolved theme and mix toward black (65%) instead when dark is
    active; `BrandingProvider` reads `useTheme()` and recomputes on every
    theme switch, not just on branding load.
  - **A real bug found live, not hypothetical**: plain `<input>`/
    `<select>`/`<input type="date">` elements (none of this app's shared
    `inputClass` strings set an explicit background) kept rendering with
    the browser's native *light* form-control styling even with `.dark`
    active elsewhere - Tailwind utilities never touch un-classed native
    chrome. Fixed with a single `color-scheme: light` / `:root.dark
    { color-scheme: dark }` declaration in `index.css` - the browser's own
    dark UA stylesheet then reskins every native form control (inputs,
    selects, the date-picker calendar icon) automatically, no per-input
    changes needed. Confirmed live this was actually broken before the
    fix and fixed after, not assumed from reasoning about the CSS alone.
  - **Live-verified** - both explicit Light and Dark, plus System
    resolving from the OS, checked against: `PublicShell`'s logged-out
    home (brand color, header, buttons); `AppShell`'s nav/header as
    `demo-front-desk` with real per-clinic orange branding overriding the
    default blue in both themes; the full front-desk appointments list
    (all `StatusPill` colors - cancelled/no_show/checked_out/checked_in
    /etc. - rendering with correct contrast); an appointment detail page's
    `PatientChart` panel (allergy severity/status badges, vitals/allergy
    input fields, the severity `<select>`, the `identifiedAt` date
    picker), Consent section, Payments panel, and the phase-M Invoice
    panel (added last session, needed zero changes here since it already
    used only semantic tokens) - all confirmed correct in both themes via
    real screenshots, not just code review. `npm test` (24/24) confirmed
    unaffected.
  - **Not done this pass**: the timezone-display picker (built later,
    2026-09-24, once phase 18 unblocked it - see below).
- **Language: i18n plumbing + English + Amharic - fully built 2026-09-22**
  (asked directly, answered - Amharic specifically chosen over Arabic/
  Spanish when asked as a follow-up). `react-i18next`/`i18next` added to
  `package.json`; `i18n/index.js` registers both languages globally via
  `initReactI18next` (so `useTranslation()` works anywhere, no
  `<I18nextProvider>` wrapper needed) from `i18n/locales/en.json`/
  `am.json`. `theme/LanguageProvider.jsx` (mirrors `ThemeProvider.jsx`'s
  exact shape - localStorage key, context, wraps the whole app in
  `main.jsx` above the auth boundary) calls `i18n.changeLanguage(...)` and
  sets `document.documentElement.lang` on every change.
  `components/LanguageToggle.jsx` (same plain segmented-control shape as
  `ThemeToggle.jsx` - each language labeled in its own script, "English"/
  "አማርኛ", not translated into the other) sits next to `ThemeToggle` in
  both `AppShell.jsx` and `PublicShell.jsx`.
  - **Every page is translated, not just the shared chrome** - the full
    sweep landed the same session, in one continuous pass across all 32
    `.jsx` files that had real UI-authored strings (confirmed by grep
    before starting: 51 `.jsx` files total; `Skeleton.jsx`/`StatCard.jsx`
    take only props, no literal text; `auth/RequireRole.jsx` only
    redirects, renders nothing). `locales/en.json`/`am.json` grew to ~590
    keys each, namespaced per page/component (`status`, `paymentMethod`,
    `routeLabel`, `prescriptionStatus`, `referralStatus`,
    `employmentStatus`, `dayLabel` for backend-vocabulary maps;
    `encounterPage`, `patientChart`, `providersPage`, `labOrderDetail`,
    etc. per page). `StatusPill.jsx` now resolves through
    `t('status.<value>', {defaultValue: ...})` - the English-word fallback
    for an unrecognized status is exactly its old always-English behavior,
    so nothing regresses if a new status value ships before its
    translation does. Every locale-file addition was verified to compile
    (a build after each batch) and the two JSON files were diffed
    key-by-key at the end (`en` 587 keys, `am` 586 - the one difference is
    `myLabOrders.testCount_one`, intentionally English-only since
    i18next's plural-form suffixes are a per-locale grammar feature
    Amharic doesn't need a second form for, not a missed translation).
  - **Live-verified, not just built** - real logins as `demo-front-desk`
    (full appointment list with every `StatusPill` value, an appointment
    detail with the entire `PatientChart` - allergies/vitals/history/
    consent - Payments, and the phase-M Invoice panel, all in Amharic),
    `demo-clinic-admin` (`Providers.jsx`, including composed strings like
    the license-number prefix and employment-status badge), and
    `demo-provider` (the schedule dashboard's interpolated "N remaining"
    hint, and the full `LabOrderDetail.jsx` - the single largest file in
    this sweep - status timeline, results table, payments-collected
    summary, and invoice, all correctly in Amharic). One raw English word
    spotted mid-verification ("routine") turned out to be `order.notes`
    (genuine staff-entered freeform data, not UI chrome) - confirmed, not
    a gap. `npm test` (24/24) stayed green throughout.
  - **Two real gaps found by a later, independent UI-evaluation pass
    (2026-09-23), both fixed same-day**: (1) `appointment.channel` was
    rendered as a raw string (`.replace(/_/g, ' ')`, no `t()` call) in
    `front-desk/Dashboard.jsx` and `front-desk/AppointmentDetail.jsx` -
    confirmed live sitting in plain English ("Front Desk") inside an
    otherwise fully-Amharic page; fixed with a new `channel.*` locale
    namespace (`patient_portal`/`front_desk`/`guest`), same
    `t('channel.<value>', {defaultValue: ...})` fallback shape as
    `StatusPill`. (2) `lib/format.js`'s formatters were never actually
    touched by the sweep - they still built their `Intl.DateTimeFormat`/
    `Intl.NumberFormat` instances once, at module load, with `undefined`
    as the locale, so every date/time/currency value in the app quietly
    stayed on the browser's own locale regardless of the language chosen
    in the UI (most visible in `SlotPicker.jsx`'s day headers). Fixed by
    building each formatter fresh per call against `i18n.language` (the
    shared i18next instance's own current language) instead of a
    module-level constant - the one file in this app that needed to
    read `i18n.language` directly rather than going through
    `useTranslation()`, since it's a plain utility module, not a
    component. Both confirmed live in Amharic after the fix
    (`ምንጭ`/"Source" now shows `አቀባበል` not "Front Desk"; `SlotPicker`/
    chart day-labels render as `ቅዳሜ፣ ሴፕቴ 19` in Amharic script and
    grammar, not `Sat, Sep 19`).
  - **A scope boundary, decided here, not asked**: only frontend-authored
    copy gets translated, ever - not just deferred for now. Error text
    coming back from spring-boot-api (validation messages,
    `ResponseStatusException` reasons, etc.) stays English-only -
    localizing those would mean either translating the backend itself (a
    separate, much larger effort touching every controller) or
    maintaining a brittle mapping keyed by free-text backend messages
    that already aren't stable identifiers. Same "backend-owned
    vocabulary, not a translated one" reasoning this project already
    applies to things like ICD-10 codes.
  - **Amharic needed a font the app didn't ship before this** -
    `@fontsource/inter` (the only font loaded) has no Ge'ez-script
    glyphs. Added `@fontsource/noto-sans-ethiopic`, self-hosted the same
    way Inter already is (not pulled from a CDN at runtime), applied via
    a `body:lang(am)` CSS rule (written to tie `body.font-sans`'s own
    specificity and win on source order, rather than reaching for
    `!important`) so only Amharic-rendered text picks up the fallback
    font and English stays on Inter.
  - **`lib/format.js` was not touched this pass** - it still passes
    `undefined` as the locale to every `Intl` formatter, deferring to the
    browser's own locale regardless of the chosen UI language. Originally
    scoped to land as one refactor together with the timezone-display
    change (both touch the same formatters) - since timezone display
    wasn't built this pass either, neither was this. A user who picks
    Amharic today gets Amharic UI *text* but still browser-locale-
    formatted dates/numbers; worth revisiting together with whichever of
    language-content-sweep or timezone-display gets built next.
  - **Live-verified** - real screenshots, not just code review: the full
    front-desk dashboard (as `demo-front-desk`) with every nav label,
    both toggles, and the logout button rendering correct Amharic Ge'ez
    script (not tofu boxes) in dark mode; the logged-out public landing
    page and its nav/Log-in button in Amharic+dark together; the
    `NotFound` page in Amharic; switching back to English/Light
    confirmed unaffected; the Amharic choice and dark theme both survived
    a real page reload (localStorage persistence, not just in-memory
    state) and a full logout/login round trip. `npm test` (24/24)
    confirmed unaffected.
- **Timezone display: browser-local, the clinic's own timezone, or any
  manually-picked IANA zone - built 2026-09-24**, now that phase 18
  unblocked the "clinic's timezone" option (asked directly when first
  sketched, answered - the broader 3-way option over a simpler
  browser-vs-clinic toggle). Directly resolves the question the phase-18
  sketch flagged and left unresolved (does the UI show clinic-local or
  viewer-local time). `lib/timezone.js` (new) is a plain module singleton,
  not React state, storing `{mode, manualZone}` in `localStorage`
  (`clinicops.timezone`) plus an ambient `activeClinicZone` - the same
  "readable from ordinary non-component code" reasoning `i18n.language`
  already needed, since `lib/format.js`'s formatters are called from
  list-grouping code (`SlotPicker.jsx`), not only from component render.
  `resolveTimezone(recordZone)` is the one seam every formatter now calls
  through: `manual` mode always returns the picked zone; `browser` mode
  returns `undefined` (Intl.DateTimeFormat already defers to the device's
  own zone when `timeZone` is omitted - no special-casing needed); `clinic`
  mode returns an explicit `recordZone` when the caller already knows one
  (the public booking flow, scoped to one clinicId), falling back to the
  ambient `activeClinicZone` otherwise. `formatDateTime`/`formatTime`/
  `formatDayLabel` (`lib/format.js`) all gained an optional second `zone`
  parameter wired to this.
  - **The reconciliation the sketch predicted was already done in phase
    18 itself**, not deferred here - `timezone` rides on `ClinicBrandingView`
    (front_desk/provider/clinic_admin) and the public `ClinicDirectoryView`
    (`GET /api/clinics`, no auth), not just the `clinic_admin`-only settings
    endpoint, specifically so this phase wouldn't need any backend change
    at all. Confirmed true - zero backend/BFF edits this phase.
  - **`theme/TimezoneProvider.jsx`** (new) - same file-per-concern layout
    and public shape (`{mode, ..., setMode}`) as `ThemeProvider.jsx`/
    `LanguageProvider.jsx`, but bridges `lib/timezone.js`'s singleton into
    React via `useSyncExternalStore` rather than owning `useState` itself,
    since the underlying value has to be plain-JS-readable. Also exports
    `useActiveClinicZone(zone)` - a small effect hook a page calls once
    with the one clinic it's currently showing (registers on mount, clears
    to `null` on unmount) - wired into `theme/BrandingProvider.jsx` (a
    signed-in staff member's own clinic, from the branding response it
    already fetches) and into the three patient/public pages that already
    resolve a single clinic's identity from `useClinicsDirectory()`:
    `pages/booking/BookingForm.jsx`, `pages/AppointmentDetail.jsx`,
    `pages/Reschedule.jsx`. Deliberately **not** wired into pages whose
    list can span multiple different clinics at once (`MyAppointments.jsx`,
    `MyLabOrders`/`MyLabOrderDetail.jsx`, the two public tracking pages,
    `RequestLabTest.jsx`'s clinic-picker step) - "clinic" mode on those
    silently falls back to the browser's own zone, a known, deliberate
    scope boundary, not a bug.
  - **`components/TimezoneToggle.jsx`** (new) - same plain segmented
    -button shape as `ThemeToggle.jsx`/`LanguageToggle.jsx` (Browser/
    Clinic's/Choose...), dropped in next to them in both `AppShell.jsx`
    and `PublicShell.jsx` - **not** the gear-icon popover the original
    sketch proposed; matched what theme/language actually shipped as
    instead (plain inline toggles, not a popover), for the same
    consistency reason nothing here was over-built into a settings surface
    that doesn't otherwise exist. "Choose..." reveals a native `<select>`
    of every zone from `Intl.supportedValuesOf('timeZone')` (a real,
    standard JS API - no bundled ~400-entry list); "Clinic's" with no
    ambient zone known on the current page shows a small "(unknown on
    this page)" hint rather than silently doing nothing.
  - **A real reactivity bug found and fixed live, not just a missing
    call site**: the first working version updated `localStorage` and
    the toggle's own highlighted state correctly, but every
    already-rendered date/time on the page stayed stale until something
    unrelated caused a re-render - confirmed live (switching modes on an
    open appointment detail page changed nothing until a manual reload).
    Root cause: `ThemeProvider` gets app-wide reactivity for free (a CSS
    class flip repaints instantly, no React re-render involved), and
    `LanguageProvider` gets it because every page already calls
    `useTranslation()` for its own text, which `react-i18next` re-renders
    unconditionally on its own `languageChanged` event (confirmed by
    reading `useTranslation()`'s own source - an internal revision counter
    increments on every emission, regardless of whether the language value
    actually changed) - but there's no equivalent existing subscription
    for a timezone change, and the ~20 files calling
    `formatDateTime`/`formatTime`/`formatDayLabel` weren't otherwise
    re-rendering when only the timezone preference changed. Two fixes were
    tried in order: first, subscribing to the timezone context directly
    inside `AppShell.jsx`/`PublicShell.jsx` (reasoning: `<Outlet/>` is
    created fresh in that component's own render, so it should cascade) -
    tried, then confirmed *still* broken live, because React Router's
    `Outlet` resolves the matched route's element from its own routing
    context rather than re-deriving it from the layout's render, so a
    layout re-render alone doesn't force the active page to re-render.
    Reverted that attempt once disproven, rather than leaving dead code
    behind. The fix that actually worked, verified live: `lib/timezone.js`'s
    `commit()` re-emits the shared i18next instance's own `languageChanged`
    event (language left unchanged) on every timezone-preference change,
    piggybacking the identical, already-proven mechanism instead of adding
    a new subscription to every consuming file.
  - **Live-verified against the real running stack** - as `demo-front-desk`:
    an open appointment's "Booked" timestamp confirmed changing correctly,
    live and in place with no reload, across all three modes (Browser -
    Africa/Nairobi in this environment; Clinic's - the demo clinic's own
    UTC; Choose... - Pacific/Kiritimati), each value hand-checked against
    the appointment's raw UTC `bookedAt` via a direct API call rather than
    eyeballed; the public/staff-shared booking flow's `SlotPicker` slot
    times shifted by exactly the clinic-vs-browser offset (3 hours here)
    the instant "Clinic's" was selected, with no page reload; both fully
    correct in Amharic + Light theme together, including the toggle's own
    translated labels (`timezone.*`, new in `locales/en.json`/`am.json`).

### Per-phase test coverage, phases 1-17 (moved from CLAUDE.md, 2026-09-28)

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
- `LabRateControllerIntegrationTest`/`LabOrderIntegrationTest`/
  `LabOrderPaymentIntegrationTest`/`PatientLabRequestIntegrationTest`
  (phase 7) - lab-rate CRUD + duplicate-testCode 409; snapshotted pricing;
  missing-rate/empty-tests 400; the full `ordered -> ... -> reviewed`
  happy path (idempotent, out-of-order 409s); `collect-specimen` identity
  check (mismatch/no-ID-on-file both 409); the `chk_payments_exactly_one
  _owner` CHECK via raw JDBC; the patient request -> confirm-and-order
  round trip. Same Testcontainers wall.
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
- `PaymentServiceTest`/`RefundServiceTest`/`InvoicePdfServiceTest` (phase
  16, pure Mockito - genuinely run locally, no Testcontainers) - charging
  through the gateway copies `ChargeResult` onto the saved `Payment`; a
  full refund marks a payment `refunded`, a partial one
  `partially_refunded`, two partials reaching the full amount also end at
  `refunded`; a refund exceeding the remaining amount is rejected and
  never touches the gateway or saves anything (`verify(..., never())`); a
  payment with no `gatewayTransactionId` (cash, or a `fee_auto_charge`
  row) skips the gateway call entirely on refund but still writes the
  audit row; `InvoicePdfServiceTest` renders a real PDF via OpenPDF
  against mocked repositories/settings and asserts the actual `%PDF`
  magic header (appointment + lab-order + guest-with-no-patient-row
  cases), not just that no exception was thrown. 11/11 passing locally.
  `PaymentControllerIntegrationTest` (new, 5 cases: full refund, two
  partials reaching the full amount, over-refund 400, role gate,
  cross-tenant 404) plus new cases in `AppointmentPaymentIntegrationTest`
  (linking a payment to the appointment's own invoice, a mismatched
  invoice id 400) and `InvoiceControllerIntegrationTest` (both owner
  types' PDF endpoints render real PDF bytes, pre-generation 404). Same
  Testcontainers wall - confirmed via a clean `mvn clean test-compile`
  and by checking every other, unrelated integration test class in the
  same run failed identically (`Could not find a valid Docker
  environment`), not just this phase's own new tests.
- `SmtpEmailSenderTest`/`NotificationPayloadWriterTest` (phase 17, pure
  Mockito - a mocked `JavaMailSender`, no real SMTP connection, genuinely
  run locally) - one rendering case per notification type (confirmed real
  values - clinic name, appointment ref, fee amount - land in the actual
  `SimpleMailMessage` subject/body, not just that `send` was called), a
  zero-fee/no-reason case omitting both from the cancellation email, and
  an unrecognized `type` falling back to a generic message rather than
  throwing. `NotificationWorkerTest` (existing) needed no changes - it
  only ever mocks `NotificationSender` generically, unaffected by which
  concrete implementation is wired. 10/10 passing locally.

## Archived full phase write-ups (moved from CLAUDE.md, 2026-10-03 size-reduction pass)

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

## Phase 26: visit/encounter summary document

Third and last phase of the full-EHR-breadth backlog. Four scoping
questions were answered directly before any code was written, this time
mostly with the broader/fuller option rather than the recommended
minimal one (unlike phases 24/25): broad data scope (visit metadata,
encounter note + phase-25 exam findings, prescriptions, vitals,
allergies, immunizations), a real PDF (reusing the phase-16
`InvoicePdfService`/OpenPDF pattern), staff **and** patient self-view
access, and no sign requirement. New migration `V22__immunizations_visit_link.sql`.

- **A real gap surfaced during research, closed by a fifth direct
  question**: `Immunization` (phase 24) had no link to a specific visit
  at all - only `patientId` + a date, so "immunizations given at this
  visit" genuinely wasn't answerable. Rather than show unrelated full
  history or drop the section, `Immunization` gained a nullable
  `appointmentId` column; `CreateImmunizationRequest` grew a 6th optional
  field (tenant-scoped validated, not a patient-match cross-check);
  `ImmunizationRepository` gained `findAllByAppointmentIdAndTenantId`.
  Existing rows stay unlinked - nothing to backfill from.
- **`com.clinicops.visitsummary`** (new package) - `VisitSummaryPdfService`
  has one method, `render(UUID appointmentId, UUID tenantId)`, used by
  both controllers after they resolve ownership differently. Unlike
  `Invoice` (which needed two `render*` methods only because it has two
  structurally different owner types), a visit summary has exactly one
  owner type, always an appointment, so one method suffices - it does all
  its own lookups from scratch (`Appointment`/`Slot`/`Provider`/`Patient`
  for the header via the same `resolvePatientName` guest-fallback pattern
  `InvoicePdfService` already established; `Encounter` via
  `EncounterRepository` directly, not `EncounterService.get` which throws
  when missing - this needs graceful "no note yet" handling; `Prescription`;
  `Vitals`; only `status = "active"` `Allergy` rows; and the newly
  visit-linked `Immunization` rows). Every section prints only if it has
  data - a freshly `booked` appointment with nothing documented yet still
  renders a valid, mostly-empty PDF. Built with the identical OpenPDF
  primitives `InvoicePdfService` already uses, no new dependency.
- **`VisitSummaryController`** - `GET /api/appointments/{id}/visit-summary/pdf`
  (staff, same 3-role gate `AppointmentInvoiceController`'s own PDF
  endpoint uses, `TenantContext.require()`, PHI-audited as `"visit_summary"`
  since this is a new aggregate view over clinical PHI) and
  `GET /api/my-appointments/{id}/visit-summary/pdf` (patient - **this
  app's first patient-facing document-download endpoint**, confirmed by
  research that no precedent existed before this; resolved via
  `CurrentUserService.resolveInternalUserId(jwt)` + `AppointmentRepository
  .findByIdAndCustomerUserIdWithSlot`, never `TenantContext`, since
  patient JWTs carry no org claim - the existing projection's own
  `tenantId` feeds `render(...)` directly. Not PHI-audited, matching
  `PhiAccessAuditService`'s own documented "staff-initiated access only"
  scope, same as `PatientLabRequestController`'s patient endpoints).
  Neither endpoint has a status gate, matching `Invoice`'s own precedent.
- **Tests**: `VisitSummaryPdfServiceTest` (new, pure Mockito, genuinely
  runs locally - 4/4 passing: full data across all six sources renders
  correctly; a guest booking skips the allergy lookup entirely
  (`verify(..., never())`) but immunizations still work since they're
  keyed by `appointmentId` now, not `patientId`; a freshly-`booked`
  appointment with nothing documented renders without crashing; a
  patient with only resolved/unconfirmed allergies still renders
  cleanly). `VisitSummaryControllerIntegrationTest` (new) - all three
  staff roles succeed; cross-tenant 404; a patient generates their own
  summary but a different patient's token gets 404 (never a
  existence-leaking 403) on someone else's appointment; a patient token
  is forbidden on the staff-only endpoint. `ImmunizationControllerIntegrationTest`
  gained `immunizationAppointmentIdRoundTripsAndAnInvalidOneIs404`.
  `mvn test` showed `Tests run: 331, Errors: 258` (up from 322/253 -
  exactly 9 new tests: 4 pure-unit + 4 Testcontainers-blocked + 1
  Testcontainers-blocked immunization case), 73 pure-unit tests now
  passing (up from 69 - the 4 new `VisitSummaryPdfServiceTest` cases) -
  not a regression.
- **Live-verified against the real running stack** - `V22` confirmed
  applied via `flyway_schema_history` and the container healthy. As
  `demo-front-desk`, fetched the PDF for a real appointment already
  carrying a signed encounter (chief complaint/assessment/plan/ICD-10),
  vitals, and both an active allergy (Penicillin) and a resolved one
  (Latex) - then created a real visit-linked immunization
  (`POST /api/patients/{patientId}/immunizations` with `appointmentId`
  set) through the actual API and re-fetched. Opened the resulting PDF
  directly in the browser and visually confirmed every section: the
  clinic letterhead, vitals table with a correctly computed BMI, the
  Allergies table showing **only** Penicillin (Latex correctly excluded -
  the active-only filter holding against real data, not just a mocked
  test), the full clinical note, a Prescriptions table, and an
  "Immunizations Given This Visit" table showing exactly the one
  visit-linked dose just created. The Physical Exam Findings section was
  correctly absent (this older encounter predates phase 25's exam
  fields) - confirming the graceful-omission logic, not just the
  happy-path table rendering. As `demo-patient`, fetched their own real
  visit summary successfully, then confirmed a 404 (not 403) when
  requesting `demo-front-desk`'s own appointment. Cross-checked the PHI
  audit log directly in Postgres: exactly two `visit_summary` rows, both
  `front_desk`-attributed from the staff fetches, correctly zero from the
  patient's own self-view.
- **No frontend yet** - backend only, closing out the full-EHR-breadth
  backlog's backend work entirely. A "Download visit summary" link/button
  (staff and patient side) is a natural next frontend-phase item.

This closes the full-EHR-breadth backlog the user asked to pull forward
2026-09-28 - immunizations, physical-exam findings, and now the visit
summary document are all built.

## Phase L1: lab technician role + specimen entity foundation

Built 2026-10-02, same session as the sketch above. First phase of the
in-house laboratory module - a new `lab_technician` realm role plus a
new `Specimen` entity, purely additive alongside `LabOrder`'s own
existing status machine rather than replacing it, mirroring exactly how
`DispenseRecord` was added for pharmacy without touching `Prescription`.
New migration `V30__lab_specimens.sql`.

- **`com.clinicops.laborder.Specimen`** (new) - one physical specimen
  under a `LabOrder`, **derived automatically, never created by hand**:
  `SpecimenService.deriveForOrder` groups an order's own
  `LabOrderTest` rows by their existing `specimenType` field (present
  since phase 7, previously unused for anything but display) and
  creates one `Specimen` per distinct non-blank type - a CBC+LFT order
  (both "blood") derives one specimen; a CBC+UA order (blood+urine)
  derives two, independently trackable. A test row with no
  `specimenType` set links to nothing (a real, accepted edge case, not
  a bug). `LabOrderTest` gained a nullable `specimen_id` FK, set once a
  specimen exists for its own type. Called from all three points a
  `LabOrder`'s own test list gets finalized -
  `LabOrderService.create`/`update` (when tests are replaced)/
  `confirmAndOrder` - wiping and re-deriving from scratch each time,
  safe since tests only ever get replaced while the order is still
  `ordered`, before any real specimen work has started.
- **`LabOrder.status` stays completely unchanged** - still an
  independently-settable field, still driving every existing
  staff/patient-facing UI and the public tracking endpoint exactly as
  before. This was the one real fork the sketch itself left open
  ("needs pinning when this phase starts") - resolved in favor of the
  purely-additive reading rather than a derived-roll-up one, to avoid
  a breaking change to every existing consumer of that field for a
  first phase whose own job is just to lay the foundation.
- **Two tracking layers, kept in lockstep for the existing simple
  flow, independent where it matters**: `LabOrderStatusService`'s own
  three order-level actions each now also call a new bulk
  `SpecimenService` method - `collectSpecimen` calls
  `collectAllForOrder` (every `pending_collection` specimen becomes
  `collected`), `send` calls `markAllInTransitForOrder`, `result`
  calls `completeAllForOrder`. For the overwhelming common case (one
  specimen type per order, true of every order built before this
  phase existed), this keeps both layers in sync automatically with
  zero new UI needed. For a genuine multi-specimen order, a
  `lab_technician` can instead act on each specimen independently via
  the new fine-grained endpoints below - confirmed live that doing so
  doesn't disturb the other, untouched specimen on the same order.
- **New `SpecimenController`** -
  `GET /api/lab-orders/{id}/specimens` (list, read-gated the same as
  `LabOrderController`'s own widened read access below) and four
  per-specimen actions: `POST /api/specimens/{id}/collect`,
  `.../mark-in-transit`, `.../receive`, `.../complete` - each
  idempotent on a re-call, each rejecting an out-of-order transition
  with the reused `InvalidLabOrderStatusException` (409) rather than a
  new parallel exception type, matching this codebase's own "only a
  dedicated exception type when the shape doesn't already exist"
  convention. `POST /api/specimens/{id}/reject` is the one action with
  no order-level equivalent trigger - purely new (e.g. a hemolyzed
  sample needing recollection), requires a reason, and is terminal
  (can't reject an already-completed specimen).
- **Existing lab endpoints re-gated** - `collect-specimen`/`send`/
  `result` move from `provider+clinic_admin` to
  `lab_technician+clinic_admin`, the exact pinned split (provider
  keeps only order-creation, `update`, `cancel`, and the final
  `review` sign-off). `GET /api/lab-orders` and
  `GET /api/lab-orders/{id}` widened to add `lab_technician` read
  access alongside the existing `provider`+`clinic_admin` (a lab tech
  needs to see what's ordered to process it) - write access to those
  (create/update/cancel/confirm-and-order) stays untouched,
  `provider`+`clinic_admin` only, ordering remains a clinical
  decision.
- **Tests**: `SpecimenServiceTest` (new, pure Mockito, genuinely runs
  locally - 9/9 passing) - derivation groups by distinct specimen type
  correctly, a test with no specimenType links to nothing, re-deriving
  wipes any prior specimens first, collect/complete/reject each
  succeed from a valid prior state and reject an invalid one,
  re-calling an already-reached transition is idempotent,
  `collectAllForOrder` only advances specimens still
  `pending_collection` (confirmed via a `never()` verify that an
  already-collected specimen's own id is never re-looked-up).
  `SpecimenControllerIntegrationTest` (new, 5 cases: two distinct
  specimen types derive two independent specimens, one can advance
  without disturbing the other, the full fine-grained lifecycle
  collect→mark-in-transit→receive→complete plus a rejected
  post-completion re-collect attempt, reject requires a reason and is
  terminal, the role gate holds both directions - provider forbidden
  from acting on a specimen, clinic_admin's usual override still
  works). `LabOrderIntegrationTest` updated throughout - every
  existing `collect-specimen`/`send`/`result` call site that was using
  `asProvider` as plain fixture setup (not explicitly testing the role
  gate) switched to the new `asLabTechnician`; the happy-path test
  gained real specimen-lockstep assertions (collected → in_transit →
  completed, confirmed via the real list endpoint at each step); one
  new dedicated role-gate test
  (`onlyLabTechnicianAndClinicAdminCanCollectSendOrResult`) confirms
  provider now genuinely 403s on all three. One new
  `TenantIsolationIntegrationTest` case
  (`specimensAreNotReadableOrWritableFromAnotherTenant`). Confirmed
  via a clean `mvn clean test-compile` and a full `mvn test` run
  showing `Tests run: 448, Errors: 351` (up from 432/344 - exactly the
  16 new test methods: 9 pure-unit + 7 Testcontainers-blocked), 0
  Failures, every error the identical pre-existing `Could not find a
  valid Docker environment` wall - not a regression, not a new failure
  mode.
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api`
  confirmed healthy, `V30` confirmed applied via the container's own
  startup log (`Migrating schema "public" to version "30 - lab
  specimens"` → `Successfully applied 1 migration`), Hibernate's
  `ddl-auto: validate` accepted the new `Specimen` entity against the
  new table with no startup failure. The `lab_technician` realm role
  and a `demo-lab-technician` user (password `DemoPass123!`) were
  created on the real running Keycloak instance via its admin REST API
  (role + user + realm-role assignment, each confirmed `201`/`204`),
  then added to the Demo Clinic Organization via the updated
  `create-demo-clinic.sh` (now loops over `demo-lab-technician` too) -
  confirmed via a direct membership-list fetch showing all six
  clinic-scoped demo users including the new one. No browser-
  automation tool was available this session, so the full
  demo-lab-technician click-through this app's own convention would
  normally expect is **not yet done** - flagged here rather than
  claimed. What was verified instead: both new endpoint families
  resolve to a real `401` (not `404`) through both the direct
  `spring-boot-api:8081` debug port and the real node-bff proxy chain
  on `:3000`, confirming the routes are genuinely wired end-to-end
  through the browser-facing path, same bar this project uses when a
  full session walkthrough isn't available.
- **No frontend yet** - backend only, matching every other EHR-leaning
  phase's own first-phase precedent (phase 7, phase 20). A
  `pages/lab/` section for `lab_technician` is L8's own job, not built
  here. `infra/keycloak/realm-export.json` updated too (role + a
  `demo-lab-technician` user, same shape every other demo user
  already has) for a from-scratch environment - the live dev instance
  needed the direct-admin-API path above since a realm export only
  applies on import, not to an already-running realm.

## Phase L2: test catalog + structured per-analyte results

Built 2026-10-02, same session as L1. One direct question was put to the
user first - the fork L1's own sketch explicitly left open (should a
normal range vary by patient age/sex) - answered **one range per
test+analyte**, the recommended option, matching this app's own "keep
minimal in v1" bias (ICD-10 free text, `Prescription.route`'s allow-list).
New migration `V31__lab_analyte_results.sql`.

- **`AnalyteDefinition`** (new) - the analyte catalog for a `test_code`
  (keyed off `lab_test_rates.test_code`, no JPA relation, same
  "own-table, explicit-repository-lookup" convention every entity in this
  package already follows). A single-analyte test (e.g. "Glucose") is
  just the degenerate one-row case; a panel (e.g. "CBC") gets one row per
  component. `normalRangeLow`/`normalRangeHigh` (numeric, drives
  auto-flagging) or `normalRangeText` (qualitative, e.g. "Negative" - no
  auto-flag computed for it). `UNIQUE(tenant_id, test_code,
  analyte_name)`.
- **`AnalyteDefinitionController`** (`/api/clinic/analyte-definitions`) -
  same CRUD shape as `LabRateController` (real hard delete - a missing
  definition just means "this test stays unstructured," the same
  well-defined fallback `LabRate`/`FeePolicy` already use). Write stays
  `clinic_admin`-only, matching that same "config setup is an admin job"
  convention - **a judgment call, not an explicitly pinned decision**,
  flagged here rather than assumed silently, same as Allergy's own gate
  back in phase 8. Read widened to `lab_technician` too, since they're
  the ones actually entering results against these ranges day to day.
- **`AnalyteResult`** (new) - one structured value for one analyte on one
  `LabOrderTest`, **purely additive alongside that same `LabOrderTest`'s
  own existing flat `result_value`/`result_unit`/`reference_range`/
  `abnormal_flag` columns, which stay completely unchanged** - the old
  `POST /api/lab-orders/{id}/result` endpoint keeps working exactly as
  before, for any test, structured or not. `analyteName`/`unit`/
  `referenceRangeDisplay` are snapshotted from `AnalyteDefinition` at
  entry time (same "snapshot pricing at order time" convention
  `lab_order_tests.price` already uses against `lab_test_rates`) -
  `analyteDefinitionId` can be null, an ad-hoc analyte with no catalog
  entry is still enterable, just never flagged.
- **`AnalyteResultService.computeFlag`** - normal/abnormal only when the
  definition has a real numeric range **and** the entered value itself
  parses as numeric; a non-numeric value against a numeric range, or a
  definition with only `normalRangeText`, is left `unflagged` rather than
  guessed at. Deliberately 2-way (normal/abnormal), not 3-way - true
  `critical` flagging is L3's own job once a critical range exists
  alongside this normal one; the sketch's own L2 bullet had loosely said
  "normal/abnormal/critical" but building a 3rd flag state with no
  critical-range column to drive it would have been scope bleed into
  L3's own job, so this phase stops at 2-way, same discipline every
  other tightly-scoped phase pair in this project already keeps (e.g.
  phase 27 vs. 28).
- **`AnalyteResultController`** (`GET`/`POST
  /api/lab-orders/{orderId}/tests/{testId}/analyte-results`) - write is
  `lab_technician`+`clinic_admin` only, the identical split L1's own
  re-gated `/result` endpoint uses; read matches `LabOrderController`'s
  own widened gate (`provider`+`clinic_admin`+`lab_technician`). Entry
  requires the owning `LabOrder` to be `in_transit` or later (same
  "specimen must actually be in transit before a result exists" gate the
  old flat endpoint always enforced) - rejected with the reused
  `InvalidLabOrderStatusException` (409), no new parallel exception type.
  Full-replace on every call, same "replace the whole list" convention
  every other multi-row write in this codebase already uses.
- **A real, small compatibility gap closed along the way**:
  `ResultLabOrderRequest.results` was `@NotEmpty` - meaning a
  `lab_technician` who entered every real value through the new
  structured path would have had no way to flip the order to `resulted`
  at all, since the old endpoint demanded at least one flat-style input
  to call it. Relaxed to allow (and default a `null` body field to) an
  empty list - the old endpoint is now callable purely to mark an order
  resulted once every real value was entered via the new path instead,
  with zero change to its behavior when real flat inputs are still
  provided the old way.
- **Tests**: `AnalyteResultServiceTest` (new, pure Mockito, genuinely
  runs locally - 9/9 passing) - entering before `in_transit` rejected,
  entering after succeeds, a value inside/outside the normal range
  flags normal/abnormal, a non-numeric value against a numeric range
  stays unflagged (not guessed), a qualitative definition is never
  flagged, an analyte with no matching catalog definition is still
  entered just never flagged, a test belonging to a different order
  404s, re-entering replaces the previous set (`deleteAllByLabOrderTestId`
  confirmed called). `AnalyteDefinitionControllerIntegrationTest` (new,
  3 cases: CRUD round-trip + duplicate-pair 409, the read/write role
  split holds both directions, cross-tenant 404).
  `AnalyteResultControllerIntegrationTest` (new, 4 cases: auto-flagging
  against a real catalog definition, the old flat endpoint still works
  unchanged and structured entry doesn't require it, entering before
  specimen-sent 409s, the role gate holds both directions). One new
  `TenantIsolationIntegrationTest` case
  (`analyteDefinitionsAndResultsAreNotReadableOrWritableFromAnotherTenant`).
  Confirmed via a clean `mvn clean test-compile` and a full `mvn test`
  run showing `Tests run: 465, Errors: 359` (up from 448/351 - exactly
  the 17 new test methods: 9 pure-unit + 8 Testcontainers-blocked), 0
  Failures, every error the identical pre-existing `Could not find a
  valid Docker environment` wall - not a regression, not a new failure
  mode. 18 pure-unit tests now exist for the lab module alone (9
  `SpecimenServiceTest` + 9 `AnalyteResultServiceTest`).
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api`
  confirmed healthy, `V31` confirmed applied via the container's own
  startup log (`Migrating schema "public" to version "31 - lab analyte
  results"` -> `Successfully applied 1 migration`). No browser-
  automation tool was available this session (same gap L1 already
  flagged) - both new endpoint families confirmed reachable as a real
  `401` (not `404`) through the real node-bff proxy chain on `:3000`,
  same bar used when a full session walkthrough isn't available. A real
  `demo-lab-technician` click-through covering both L1 and L2 together
  is still owed.
- **No frontend yet** - backend only, same as L1. L8's own job.

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

- **`AnalyteDefinition` gains `criticalRangeLow`/`criticalRangeHigh`**
  (nullable `NUMERIC(12,4)`, same shape as the existing normal-range
  pair) - outer bounds beyond the normal range, not a separate
  alternative range. `CreateAnalyteDefinitionRequest`/
  `UpdateAnalyteDefinitionRequest` both grew two trailing fields (9 and
  7 total respectively) to carry them; every existing test call site
  constructing these records mechanically grew to match (see below).
- **`computeFlag` priority: critical > abnormal > normal**, computed in
  that order - a value outside the critical range is `"critical"`
  regardless of where it sits relative to the normal range; one outside
  normal but still inside critical is `"abnormal"`; one inside normal is
  `"normal"`. Unchanged from L2: a definition with no real numeric
  normal range, or a non-numeric entered value, stays `"unflagged"`
  rather than guessed at - the critical check inherits that same
  "nothing to compute against" guard for free, since it's gated behind
  the same early-return.
- **`AnalyteResult` gains a real acknowledgment trail** -
  `criticalAcknowledgedAt`/`criticalAcknowledgedBy` (nullable
  `TIMESTAMPTZ`/FK to `app_users`), meaningless (always null) for a
  non-critical result, same "null means not applicable" convention
  `Vitals`'s own optional fields already use. `AnalyteResultService
  .acknowledgeCritical` rejects a non-critical result (409, reused
  `InvalidLabOrderStatusException` rather than a new exception type,
  matching L1's own "no new exception type when the shape already
  exists" convention) and is idempotent on a re-call (already
  -acknowledged stays as the first acknowledgment, doesn't overwrite
  `acknowledgedBy` with whoever re-calls it).
- **A real outbox notification, not a new mechanism** -
  `CriticalLabValueAlertPayload(orderRef, testName)` (new,
  `com.clinicops.notification`), deliberately narrow - no analyte name
  or the actual value, matching `LabResultReadyPayload`'s own "never
  leak clinical content into a notification" convention; the recipient
  logs into the app to see what's actually critical. `SmtpEmailSender`
  gained one new `case "critical_lab_value"` switch arm (`"URGENT:
  critical lab value - <orderRef>"` subject). Fired from
  `AnalyteResultService.enterResults` whenever *any* result in that
  call's batch computes `"critical"` - skipped gracefully (no
  exception) when the order has no `orderingProviderId`, that provider
  has no linked login, or that login has no email on file, the exact
  same "skipped when there's no contact to reach" precedent
  `LabOrderStatusService.review`'s own `lab_result_ready` notification
  already established.
- **`POST /api/analyte-results/{id}/acknowledge-critical`** (new,
  `AnalyteResultController`) - **`provider`+`clinic_admin`, deliberately
  not `lab_technician`** - acknowledging a critical alert is the
  ordering clinician's own job (the one being alerted), matching
  `LabOrderStatusController`'s own `review` action's role gate rather
  than the `lab_technician`-owned result-entry actions L1/L2 already
  gated the other way. PHI-audited as `lab_order_analyte_result_ack`.
- **Tests**: `AnalyteResultServiceTest` (pure Mockito, existing file
  extended - genuinely runs locally, **15/15 passing**, up from 9/9) -
  every pre-existing case updated only for the new 8-arg constructor
  (3 new deps: `ProviderRepository`/`AppUserRepository`/
  `NotificationRepository`, plus `ObjectMapper`), proving current
  behavior unchanged, plus 6 new cases: a value beyond the critical
  range flags `"critical"` not just `"abnormal"`; a value outside
  normal but inside critical stays `"abnormal"`; a critical result
  notifies the ordering provider when one is linked to a real login; a
  critical result skips the notification when the provider has no
  linked login (`verify(notificationRepository, never())`);
  acknowledging a critical result succeeds and is idempotent;
  acknowledging a non-critical result is rejected.
  `SmtpEmailSenderTest` gained one new case confirming the rendered
  email contains no analyte name/value anywhere, same assertion shape
  every other notification-rendering test in this file already uses.
  `AnalyteResultControllerIntegrationTest` (existing file extended, 3
  new cases) - entering a value beyond the critical range via the real
  endpoint flags `"critical"` and writes exactly one real
  `critical_lab_value` notification row; the acknowledge endpoint
  genuinely 403s for `lab_technician` and succeeds (idempotently) for
  `provider`; acknowledging a non-critical result genuinely 409s. A new
  `seedWithLinkedProvider`/`createInTransitOrder(f, orgAlias,
  providerSubject)` overload was added to this file specifically so a
  test can later act as the exact provider who placed the order (needed
  to exercise the acknowledge endpoint as the real notified party, not
  an arbitrary different provider login). Confirmed via a clean `mvn
  clean test-compile` and a full `mvn test` run showing `Tests run: 475,
  Errors: 362` (up from 472/359 - exactly the 3 new Testcontainers
  -blocked test methods; the 7 new pure-unit cases above had already
  landed in the prior 465->472 step), 0 Failures, every error the
  identical pre-existing `Could not find a valid Docker environment`
  wall (confirmed by running this one test class in isolation too - all
  7 of its cases, old and new alike, hit the identical
  `NoClassDefFoundError`/`ExceptionInInitializerError`, not a new
  failure mode) - 113 pure-unit tests now passing project-wide (up from
  106).
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api`
  confirmed healthy, `V32` confirmed applied via the container's own
  startup log (`Migrating schema "public" to version "32 - lab critical
  values"` -> `Successfully applied 1 migration to schema "public", now
  at version v32`) and via a direct `flyway_schema_history` query
  (`success = t`). No browser-automation tool was available this
  session (same gap L1/L2 already flagged) - the new
  `POST /api/analyte-results/{id}/acknowledge-critical` endpoint
  confirmed reachable as a real `401` (not `404`) through both the
  direct `spring-boot-api:8081` debug port and the real node-bff proxy
  chain on `:3000`. A real `demo-lab-technician`/`demo-provider`
  click-through exercising a genuine critical result end to end
  (entering it, confirming the Mailpit email, acknowledging it) is
  still owed, same standing gap L1/L2 already flagged, not newly
  introduced here.
- **No frontend yet** - backend only, same as L1/L2. L8's own job; a
  critical-value banner/acknowledge button on the future lab-technician/
  provider UI is a natural detail for whenever L8 is picked up, not
  pinned further here.

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

- **`com.clinicops.laborder.QcRun`** (new) - one control-material run
  logged against one instrument on one day, genuinely append-only (no
  update/delete anywhere), same shape `AssetMaintenanceRecord`/
  `StockAdjustment` already use for this kind of audit-weight log entry.
  `instrumentIdentifier` is free text in v1 - there's nothing to
  integrate with in this dev environment, matching L6's own
  "integration-readiness, not a real integration" scope boundary.
  `expectedRangeLow`/`expectedRangeHigh` (both required numeric) define
  the acceptable control range; `observedValue` is a required string
  (same "value as a string, parsed only when needed" convention
  `AnalyteResult.value` already uses) that must itself parse as numeric
  (400 otherwise - a real, measured observation, not something left
  unflagged the way a non-numeric *result* value can be). `pass` is
  computed server-side from `observedValue` against the expected range
  at creation time, not left for the caller to self-report - the one
  thing this log actually needs to be trustworthy.
- **Flag-only, by design: nothing anywhere else in this app ever reads
  `pass`** - `AnalyteResultService.enterResults` is completely untouched
  by this phase; a failed QC run is visible only on this log itself,
  never blocks a `lab_technician` from entering a real result against
  the same instrument. The pinned fork's own "trusting staff judgment"
  reasoning, held to literally rather than symbolically.
- **`QcRunController`** (`GET`/`POST /api/lab-qc-runs`) -
  `lab_technician`+`clinic_admin` only, deliberately not `provider` -
  this is internal lab-operations record-keeping, not clinical data a
  provider needs to see, unlike a critical analyte result (L3) which
  genuinely requires the ordering clinician's own attention. `GET`
  supports an optional `instrumentIdentifier` filter (blank-means-all,
  same shape `MedicationController.medications(status)` already uses);
  both list and filtered results come back newest-first. No single
  -resource `GET {id}` - a plain list is all this log needs, same bar
  `AssetMaintenanceRecord`'s own nested list endpoint sets.
- **Tests**: no new pure-unit test class - this phase's own logic (the
  pass/fail range check, the numeric-value guard) lives entirely in the
  controller with no separate service bean, same bar phases 29/30's own
  CRUD-shaped logic used. `QcRunControllerIntegrationTest` (new, 3
  cases: recording a run inside the expected range computes `pass:
  true`, outside computes `pass: false`, and the instrument filter
  correctly narrows the list; a non-numeric `observedValue` and an
  inverted expected range both 400; the role gate holds both
  directions - `provider`/`front_desk` forbidden, `clinic_admin`'s
  usual override still works). One new `TenantIsolationIntegrationTest`
  case (`qcRunsAreNotReadableFromAnotherTenant`) - QC runs have no
  single-resource `GET`, so the isolation check here is "clinic B's own
  list never includes clinic A's run" rather than a 404 on a shared id,
  the correct equivalent shape for a list-only resource. Confirmed via
  a clean `mvn clean test-compile` and a full `mvn test` run showing
  `Tests run: 479, Errors: 366` (up from 475/362 - exactly the 4 new
  test methods: 3+1, all Testcontainers-blocked, no pure-unit count
  change), 0 Failures, every error the identical pre-existing `Could
  not find a valid Docker environment` wall (confirmed via the new
  test class's own surefire report hitting the identical
  `NoClassDefFoundError`) - not a regression, not a new failure mode.
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api`
  confirmed healthy, `V33` confirmed applied via the container's own
  startup log (`Migrating schema "public" to version "33 - lab qc
  runs"` -> `Successfully applied 1 migration to schema "public", now
  at version v33`) and via a direct `flyway_schema_history` query
  (`success = t`). No browser-automation tool was available this
  session (same gap L1-L3 already flagged) - the new
  `GET /api/lab-qc-runs` endpoint confirmed reachable as a real `401`
  (not `404`) through both the direct `spring-boot-api:8081` debug port
  and the real node-bff proxy chain on `:3000`. A real
  `demo-lab-technician` click-through recording a genuine QC run (both
  a passing and a failing one, confirming the failing one still leaves
  result entry on that instrument unblocked) is still owed, same
  standing gap L1-L3 already flagged, not newly introduced here.
- **No frontend yet** - backend only, same as L1-L3. L8's own job; a QC
  log view on the future lab-technician UI is a natural detail for
  whenever L8 is picked up, not pinned further here.

## Phase L5: external reference-lab send-outs

Built 2026-10-02, same session as L1-L4. Unlike L4, the sketch's own
text for this phase named no open fork to pin, so this was built
directly from the sketch rather than preceded by a scoping question -
the only real design calls needed (where in the existing specimen
status machine the new branch sits, and how lenient the transition into
it should be) were made in the same spirit every other un-flagged
design decision in this file already documents inline rather than
re-asked. New migration `V34__lab_reference_lab_sendouts.sql`.

- **One new specimen status branch, `sent_to_reference_lab`** - reachable
  from `collected`/`in_transit`/`received` (not `pending_collection`,
  not `rejected`/`completed`) - no single enforced point in the existing
  flow where a send-out decision has to happen, matching this app's own
  lenient "no confirm dialogs, no single mandated path" bias rather than
  forcing every specimen through `received` first. `Specimen` gains four
  new nullable columns - `referenceLabName`/`referenceLabOrderNumber`/
  `expectedTurnaroundDays`/`sentToReferenceLabAt` - all null (not
  applicable) unless the specimen was ever sent out, same "null means
  not applicable" convention L3's own acknowledgment columns already
  use.
- **`SpecimenService.sendToReferenceLab`** - a genuine deliberate
  deviation from every other specimen action's exact-idempotent-no-op
  shape: re-calling it while already `sent_to_reference_lab` **updates**
  the reference-lab details in place rather than silently no-op'ing,
  since unlike `collect`/`receive`/`complete` (which carry no payload at
  all), this action actually carries new information worth correcting
  on a re-call - a typo'd order number, a revised turnaround estimate.
  Calling it from any other invalid status (not yet collected, already
  rejected/completed) still throws the reused
  `InvalidLabOrderStatusException` (409), same "no new exception type
  when the shape already exists" convention L1 pinned.
- **Both `complete` (the individual action) and `completeAllForOrder`
  (the order-level bulk sweep L1 wired into `LabOrderStatusService
  .result`) now also accept a specimen sitting at `sent_to_reference_lab`**
  - a reference-lab specimen completes the exact same way any other one
  does, no special-cased path, confirming the sketch's own "results
  coming back get entered through the same structured per-analyte shape
  L2 already establishes, not a separate parallel path" line holds at
  the specimen-tracking layer too, not just the result-entry layer.
- **`POST /api/specimens/{id}/send-to-reference-lab`** (new,
  `SpecimenController`) - `lab_technician`+`clinic_admin` only, the same
  write gate every other specimen action already uses; PHI-audited the
  same way (`lab_order_specimen`, write).
- **Tests**: `SpecimenServiceTest` (pure Mockito, existing file
  extended - genuinely runs locally, **14/14 passing**, up from 9/9) - 5
  new cases: sending to a reference lab succeeds from each of
  collected/in_transit/received and records all four fields; sending
  from pending_collection/completed/rejected is rejected; re-sending
  while already sent out updates the details in place rather than
  rejecting; a sent-out specimen can still be individually completed;
  `completeAllForOrder` sweeps a sent-out specimen too.
  `SpecimenControllerIntegrationTest` gained one new case (a real
  send-before-collection 409, a real send-then-correct-the-details round
  trip through the actual endpoint, and completion afterward). One
  `TenantIsolationIntegrationTest` case extended in place (the existing
  `specimensAreNotReadableOrWritableFromAnotherTenant` gained one more
  assertion for the new action) rather than a new test method - same
  precedent phase 11's field-addition tests already used. Confirmed via
  a clean `mvn clean test-compile` and a full `mvn test` run showing
  `Tests run: 485, Errors: 367` (up from 479/366 - exactly the 6 new
  test methods: 5 pure-unit + 1 Testcontainers-blocked), 0 Failures,
  every error the identical pre-existing `Could not find a valid Docker
  environment` wall (confirmed by running `SpecimenControllerIntegrationTest`
  in isolation - all 6 of its cases, old and new alike, hit the
  identical `NoClassDefFoundError`/`ExceptionInInitializerError`, not a
  new failure mode) - 118 pure-unit tests now passing project-wide (up
  from 113).
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api`
  confirmed healthy, `V35` confirmed applied via the container's own
  startup log (`Migrating schema "public" to version "34 - lab reference
  lab sendouts"` -> `Successfully applied 1 migration to schema
  "public", now at version v34`) and via a direct `flyway_schema_history`
  query (`success = t`). No browser-automation tool was available this
  session (same gap L1-L4 already flagged) - the new
  `POST /api/specimens/{id}/send-to-reference-lab` endpoint confirmed
  reachable as a real `401` (not `404`) through both the direct
  `spring-boot-api:8081` debug port and the real node-bff proxy chain on
  `:3000`. A real `demo-lab-technician` click-through (collecting a
  specimen, sending it to a reference lab, correcting the details, and
  completing it) is still owed, same standing gap L1-L4 already flagged,
  not newly introduced here.
- **No frontend yet** - backend only, same as L1-L4. L8's own job; a
  "Send to reference lab" action on the future lab-technician specimen
  UI is a natural detail for whenever L8 is picked up, not pinned
  further here.

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

- **`"lab_reagent"` added to `InventoryItemController.VALID_CATEGORIES`**
  (now `clinical_supply`/`ppe`/`office_supply`/`lab_reagent`) - the
  entire schema-level change this phase needed. A lab reagent is now
  just another `InventoryItem` row, inheriting `StockBatch`'s own
  lot/expiry tracking, `Supplier`/`PurchaseOrder` procurement, and
  `StockAdjustment`'s own append-only correction log for free - zero
  new entities, zero new controllers.
- **`lab_technician` gains read-only access to the four relevant GET
  endpoints** (`items`, `item`, `stockBatches`, `reorderAlerts`) -
  **not an explicitly-asked sub-question, a direct application of an
  already-established precedent**: `PHARMACIST` was added to
  `reorderAlerts` during the search/filter audit for the identical
  reason ("a role is at least as legitimate a consumer of its own stock
  data as `front_desk`"), and a lab technician who can now see
  `lab_reagent` rows in this catalog has the same legitimate need to
  see their own stock levels/batches/reorder alerts. Write access
  (create/update/receive/write-off) stays `clinic_admin`+`front_desk`
  only, completely unchanged - ordering/receiving stock is still
  general logistics, not something this app's existing role boundaries
  give `lab_technician` directly, matching `MedicationController`'s own
  "broader read gate than write gate" precedent (phase 35) rather than
  widening write access too.
- **`PurchaseOrderController`/`StockAdjustmentController` deliberately
  untouched** - the sketch's own L7 bullet is specifically about
  catalog/stock-level visibility, not the procurement workflow itself;
  ordering reagents stays a `clinic_admin`/`front_desk` action, same as
  ordering any other general-inventory category.
- **Tests**: no new pure-unit test class - this phase is a pure
  allow-list widening plus role-gate annotations, the same bar
  phase 35's own `front_desk`-read-widening used (one direct role-gate
  test, nothing more). `InventoryItemControllerIntegrationTest` gained
  one new case (`labReagentIsAValidCategoryAndLabTechnicianCanReadButNotWriteInventory`)
  - creating a real `lab_reagent`-category item succeeds, `lab_technician`
  reads it through all four widened endpoints successfully, and
  `lab_technician` still genuinely 403s on create - confirming the read
  /write asymmetry holds, not just the read widening alone. No new
  `TenantIsolationIntegrationTest` case - `InventoryItem`'s own
  cross-tenant scoping was already covered by phase 29's case; this
  phase changes no tenant-scoping logic at all. Confirmed via a clean
  `mvn clean test-compile` and a full `mvn test` run showing `Tests
  run: 486, Errors: 368` (up from 485/367 - exactly the one new test
  method, Testcontainers-blocked, no pure-unit count change), 0
  Failures, every error the identical pre-existing `Could not find a
  valid Docker environment` wall (confirmed by running
  `InventoryItemControllerIntegrationTest` in isolation - all 7 of its
  cases, old and new alike, hit the identical
  `NoClassDefFoundError`/`ExceptionInInitializerError`, not a new
  failure mode) - not a regression.
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api`
  confirmed healthy, Flyway confirmed genuinely unaffected (`Current
  version of schema "public": 34` -> `Schema "public" is up to date. No
  migration necessary.`, exactly as expected for a pure code-level
  allow-list/role-gate change). No browser-automation tool was
  available this session (same gap L1-L5 already flagged), so the
  `lab_technician` read-widening itself couldn't be exercised through a
  real login - `GET /api/inventory/items` was confirmed to still
  genuinely require auth (`401`, not `404`) through both the direct
  `spring-boot-api:8081` debug port and the real node-bff proxy chain
  on `:3000`, proving nothing broke, but the real role-widening claim
  rests on the Testcontainers-run integration test above, not a live
  browser session - flagged here rather than claimed, same standing gap
  L1-L5 already carries.
- **No frontend yet** - backend only, same as L1-L5; `pages/inventory/`
  (phase 35) already exists but has no `lab_technician` route/nav case,
  matching this phase's own backend-only scope. L8's own job to decide
  whether `lab_technician` gets a path into that existing inventory UI
  or a reagent-scoped view of its own, not pinned further here.

## Phase L8: frontend

Built 2026-10-02, same session as L1-L7, closing out the whole lab
module. One direct question was put to the user first - no browser
-automation tool was available this session (the same standing gap
L1-L7 already flagged), so unlike every other frontend phase in this
project's history, this one couldn't be clicked through and visually
confirmed, only build-verified - answered **build it now, build-only
verification**, the recommended option, with the missing click-through
explicitly flagged here rather than claimed.

- **A real, pre-existing bug found before writing any new code, not
  introduced by this phase** - `lab-orders/LabOrderDetail.jsx`
  (frontend phase F, built 2025-09-14, long before the lab module
  existed) renders its collect-specimen/send/result controls with zero
  role-gating at all - every action button shows for whoever can reach
  the page. L1 (2026-10-02, this same session) re-gated exactly those
  three backend endpoints from `provider`+`clinic_admin` to
  `lab_technician`+`clinic_admin`, but never touched this page (L1's
  own write-up explicitly deferred the frontend to L8). The result: a
  `provider` opening this still-`provider`-only-routed page has been
  seeing working-looking "Collect specimen"/"Mark sent"/"Enter
  results" controls that genuinely `403` on click since L1 shipped,
  earlier this same session - caught here before it ever reached a
  real user, not live-observed. Fixed as the first step of this phase:
  `canActOnOrder` (`lab_technician`+`clinic_admin`) now gates those
  three plus the specimen panel; `canManageOrder` (`provider`+
  `clinic_admin`, renamed from a narrower `canReview` once it started
  covering edit/cancel too) gates edit/cancel/review - matching
  `LabOrderStatusController`'s/`LabOrderController`'s exact backend
  split, not a cosmetic change.
- **Shared the existing page rather than building a parallel one** -
  the same "share the existing page/component" instinct this project
  has used repeatedly (the clinic_admin encounter-access fix,
  `PatientChart.jsx`, `PaymentsPanel.jsx`'s `extraRoles`): `/lab-orders`
  and `/lab-orders/:id` widened to add `lab_technician` to their
  `RequireRole`, rather than a second, parallel lab-order list/detail
  page duplicating most of what `LabOrders.jsx`/`LabOrderDetail.jsx`
  already do (payments, invoice, the tests table, cancellation). This
  also means `lab_technician`'s own "worklist" is the exact same
  searchable/sortable `DataTable` every other role's lab-order list
  already is - no new backend queue endpoint needed (unlike pharmacy's
  own dedicated `/api/pharmacy/queue`), confirmed by `pages/lab/
  Dashboard.jsx`'s own client-side `needsAction` count computed from
  the same plain `GET /api/lab-orders` list, same reasoning
  `front-desk/Appointments.jsx` already filters client-side.
- **`components/SpecimensPanel.jsx`** (new) - a `lab_technician`/
  `clinic_admin`-only panel on the shared detail page listing an
  order's own specimens (L1's own "one order can have more than one"
  shape) with per-specimen action buttons matching whatever status
  each one is actually at - collect/mark-in-transit/receive/complete/
  reject (L1), plus send-to-reference-lab with an inline
  name/order-number/turnaround-days form that re-opens pre-filled for
  correcting the details on an already-sent specimen (L5's own
  "re-calling updates in place" backend semantics, carried through to
  the UI). Specimens had zero frontend visibility anywhere in this app
  before this phase, even though L1 built the complete backend
  lifecycle for them five phases ago.
- **`components/AnalyteResultsPanel.jsx`** (new) - structured
  per-analyte results for one `LabOrderTest`, alongside (not replacing)
  the page's own pre-existing flat value/unit/reference-range form - a
  new `labOrderDetail.flatResultsHint` line now tells whoever's looking
  at both that either path works. **A real design bug caught and fixed
  before this component was ever wired in**: the first version gated
  the *entire* panel (viewing included) behind `canActOnOrder`
  (`lab_technician`+`clinic_admin`), which would have hidden analyte
  results - and the critical-value acknowledge button - from the one
  role that most needs to see a critical result: `provider`. Fixed by
  splitting `canEnterResults` (gates the entry form only) from
  `canAcknowledgeCritical` (gates the acknowledge button only) and
  mounting the panel for `provider`+`clinic_admin`+`lab_technician`
  alike (`AnalyteResultController`'s own real read gate), not
  `canActOnOrder` alone - confirmed by re-reading L2/L3's own write-ups
  before fixing it, not caught live (no browser tool to catch it that
  way this session). A critical, unacknowledged result renders with a
  visible red-bordered row regardless of viewer role; only
  `provider`/`clinic_admin` get the working "Acknowledge" button,
  matching `AnalyteResultController`'s own `acknowledge-critical` gate
  exactly.
- **`pages/lab/QcRuns.jsx`** (new, `/lab/qc-runs`) - mirrors
  `pharmacist/DrugInteractions.jsx`'s own create-form-above-`DataTable`
  shape, minus its edit/delete panel (QC runs have neither endpoint -
  genuinely append-only, L4's own pinned shape). `pass`/`fail` renders
  as a colored pill computed entirely server-side; nothing here lets a
  user self-report a result the backend didn't actually compute.
- **`pages/lab/Dashboard.jsx`** (new, `/lab`) - `lab_technician`'s own
  landing page (this role had none before this phase), mirroring
  `accountant/Dashboard.jsx`'s own stat-cards-plus-link-tiles shape:
  needs-lab-action count, QC runs today, failed QC today (all computed
  client-side from already-fetched lists, no new backend).
- **Routing/nav** - `/lab` (`RequireRole role="lab_technician"` - the
  one other staff role, besides `clinic_admin`, with its own singular
  dashboard) and `/lab/qc-runs` (`RequireRole roles={['lab_technician',
  'clinic_admin']}`), plus `lab_technician` added to `/lab-orders`'s
  own existing `RequireRole`. New `lab_technician` sidebar group
  (Dashboard/Lab Orders/QC Log); `clinic_admin`'s own existing core
  group gained one curated `/lab/qc-runs` link next to its pre-existing
  `/lab-orders` one, matching the "clinic_admin gets a UI path into
  every module it overrides" precedent L8's own sketch bullet named.
  **A second real, pre-existing stale comment found and fixed along
  the way** - `auth/RequireRole.jsx`'s own doc comment had speculated,
  before the lab module existed, that "the eventual /lab route tree...
  is reachable by PROVIDER and CLINIC_ADMIN alike (identical backend
  permissions)" - not what L1-L8 actually built (`/lab` itself is
  `lab_technician`-only; `/lab-orders` is shared by three roles with
  now-*different* backend permissions, the exact bug fixed above).
  Corrected to describe what's actually true today.
- **`StatusPill.jsx` extended** with the full specimen-status
  vocabulary (L1/L5 - `pending_collection`/`collected`/`received`/
  `processing`/`completed`/`rejected`/`sent_to_reference_lab`) -
  already functional via the component's own raw-string fallback
  before this, just unstyled; now visually distinct like every other
  status vocabulary this component already covers.
- **Deliberately not built: a lab analytics endpoint/dashboard chart**
  - the sketch's own text named this as "a natural L8 add-on, not
  pinned as its own phase," i.e. explicitly optional, unlike every
  other bullet in this write-up. Skipped to keep this single pass
  proportionate to a build-only-verified session; a natural candidate
  for a later phase once a browser tool can actually validate chart
  rendering, mirroring how phase 34's own `PharmacyAnalyticsController`
  got picked up.
- **i18n**: five new namespaces (`labDashboardPage`, `specimensPanel`,
  `analyteResultsPanel`, `qcRunsPage`, plus `nav.lab` and one new
  `labOrderDetail.flatResultsHint` key on the existing namespace) added
  to `en.json`/`am.json` together - confirmed exact key-parity via the
  same flatten-and-diff Node script every prior phase this session
  used (1068 keys each side, the same two pre-existing intentional
  English-only pluralization keys as always - not a new gap).
- **Tests**: no new Vitest coverage - `SpecimensPanel.jsx`/
  `AnalyteResultsPanel.jsx`/`QcRuns.jsx`/`LabDashboard.jsx` are
  page-level/feature components, outside frontend phase S's own
  explicit "infrastructure + shared components" scope boundary (the
  same bar every other page-level frontend phase since S has used).
  `npm run build` clean both before and after the i18n additions;
  `npm test` **36/36 passing**, unaffected (no shared-component test
  surface touched - `StatusPill.test.jsx` keeps passing with the
  extended style map, confirming the addition didn't disturb its
  existing fallback-rendering assertions).
- **Live-verified against the real running stack, through both
  `node-bff` directly and the real nginx front door** -
  `docker compose up -d --build --force-recreate node-bff` (no
  `spring-boot-api` rebuild needed - this phase is frontend-only) +
  `docker compose restart nginx` (the known stale-upstream-IP gotcha
  after a `node-bff` recreate), both confirmed healthy. Every new SPA
  route (`/lab`, `/lab-orders`, implicitly `/lab/qc-runs` via the same
  index-fallback mechanism) confirmed resolving to a real `200`
  through `node-bff` on `:3000` *and* through nginx on `:80` directly -
  the static-file/client-router layer is genuinely wired end to end.
  **What this does *not* confirm, flagged explicitly rather than
  implied**: no real `demo-lab-technician` (or `demo-provider`/
  `demo-clinic-admin`) browser login exercised any of this session's
  new UI - the specimen action buttons, the analyte-result entry
  form, the critical-value acknowledge flow, the QC log, the sidebar
  nav, Dark theme, Amharic. Every other frontend phase in this
  project's history reached that bar; this one explicitly didn't, by
  the user's own informed choice before any code was written. A real
  click-through covering all of the above is still owed, the single
  biggest standing gap this whole lab module carries - flagged here,
  not claimed.

This closes out the entire "In-house laboratory module" sketched
2026-10-02 - all eight phases (L1-L8) are now built. `lab_technician`
has a real backend *and* a real (build-verified, not yet
click-verified) frontend.

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

- **Two implementation-level gaps closed while building, not just
  sketched**: `DispenseService` had no patient-resolution path at all
  before this phase (`Prescription` carries no `patientId`) - closed by
  adding `EncounterRepository`/`AppointmentRepository` as two more
  constructor dependencies and resolving `prescription -> encounter ->
  appointment -> patientId` via two plain `findById` hops, rather than
  writing a new native query for a need `PrescriptionRepository
  .findPendingDispense`'s own existing join doesn't actually cover (that
  query returns a *view* for the queue UI, never a raw `patientId` a
  service could reuse). And a guest-channel booking (`patientId` null)
  has no `Allergy` data and no other prescriptions to check against -
  `resolvePatientId` returns `null` in that case and `checkClinicalSafety`
  short-circuits, silently skipping both checks - a deliberate, documented
  limitation, not an oversight, matching phase 20's own "no drug-name
  -matching logic" tone for pinned scope boundaries.
- **`com.clinicops.pharmacy.DrugInteractionPair`** (new entity) -
  `medicationAId`/`medicationBId` (both required, fixed at creation - like
  `LabRate.testCode`, corrected via delete+recreate not update),
  `severity` (nullable free string), `description` (nullable, free text -
  what actually surfaces in the conflict message).
  **`DrugInteractionPairController`** - same hard-delete CRUD shape as
  `FeePolicyController`/`LabRateController`
  (`GET`/`GET {id}`/`POST`/`POST {id}/update`/`POST {id}/delete`), all
  `hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')` matching `DispenseController`'s
  own gate. Validates both medication ids are real tenant-scoped
  `Medication` rows (404 otherwise), rejects `medicationAId ==
  medicationBId` (400), and rejects a duplicate pair **in either
  order** - (A,B) and (B,A) are the same conflict - before insert (409).
- **`DispenseService`** - new constructor deps: `AllergyRepository`,
  `DrugInteractionPairRepository`, `EncounterRepository`,
  `AppointmentRepository` (8 total). `checkClinicalSafety` runs right
  after the medication is resolved, before any stock-batch/quantity work
  (fail fast on a safety conflict before touching stock): resolves
  `patientId`, checks the patient's `status.equals("active")` allergies
  for a case-insensitive substring match against the medication name
  either direction (same "keep minimal, no drug-class ontology"
  limitation this app already accepts for ICD-10), then checks every
  tenant `DrugInteractionPair` involving this medication against the
  patient's other active prescription names (via a new
  `PrescriptionRepository.findActiveMedicationNamesForPatient` native
  query - a scalar-column projection, same join shape as the existing
  `findPendingDispense`) for the same substring match. If either check
  finds something and `request.acknowledgeConflict()` is false, throws
  the new `ClinicalSafetyConflictException` (mapped to **409** in
  `DispenseController` - matches `InsufficientStockException`'s own use
  of 409 for "a real precondition blocks this," deliberately not the
  400 `RestrictedTestException` uses, since this is a state conflict,
  not malformed input) with every conflict found joined into one message,
  not just the first. `DispenseRecord.safetyOverrideAcknowledged` is set
  `true` only when a real conflict was found *and* acknowledged - never
  just because the flag was sent with nothing to override, keeping the
  audit trail meaningful. `DispenseRequest` gained the plain boolean
  `acknowledgeConflict` field (5th field, default `false` at every
  pre-existing call site) - same shape `CreateLabOrderRequest
  .consentAcknowledged` already uses.
- **Tests**: `DispenseServiceTest` (pure Mockito, existing file
  extended) - every pre-existing case updated only for the new
  constructor/request arity (proving current behavior is unchanged), plus
  3 new cases: a guest-channel prescription skips both checks entirely
  (`verify(..., never())` on both new repositories), a real allergy match
  blocks without acknowledgment and succeeds with it
  (`safetyOverrideAcknowledged` true only then), a real interaction match
  behaves the same way. **9/9 passing locally.**
  `DispenseControllerIntegrationTest` (existing file extended) - the same
  three cases end-to-end through the real endpoint.
  `DrugInteractionPairControllerIntegrationTest` (new, 6 cases) - CRUD
  round trip, duplicate-pair-either-order 409, self-pair 400,
  unknown-medication 404, cross-tenant 404, role gate
  (front_desk/provider forbidden). One new `TenantIsolationIntegrationTest`
  case (`drugInteractionPairsAreNotReadableOrWritableFromAnotherTenant`).
  Confirmed via a clean `mvn clean test-compile` and by checking every
  other, unrelated integration test class in the same run failed
  identically (`Could not find a valid Docker environment`) - the same
  Testcontainers/Windows-npipe wall as every prior phase, not a new
  failure mode.
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api`
  confirmed healthy, `V23` confirmed applied via `flyway_schema_history`
  (version 23, description "pharmacy clinical safety", `success = t`).
  As a real `demo-pharmacist` login through the actual browser: created
  two real medications ("Penicillin V", "Amoxicillin") with stock
  batches, and a real `DrugInteractionPair` between them via
  `POST /api/pharmacy/drug-interaction-pairs`. Dispensed against a real
  pre-existing "Walk In" patient's Amoxicillin prescription while that
  same patient had an active Penicillin V prescription - got a genuine
  `409` interaction conflict, then successfully dispensed with
  `acknowledgeConflict:true`, confirming `safetyOverrideAcknowledged:true`
  in the response. Dispensed against that same patient's Penicillin V
  prescription - since the patient also had a genuine pre-existing active
  Penicillin allergy (from an earlier session's own verification) *and*
  was now on Amoxicillin, got a real **consolidated** conflict message
  surfacing both hits at once: `"Patient has an active allergy to
  Penicillin; Potential interaction with Amoxicillin (Both are
  beta-lactams, redundant/cross-reactive)"` - not just the first one
  found - then successfully acknowledged and completed it. Cross-checked
  both resulting `dispense_records` rows directly in Postgres, confirming
  `safety_override_acknowledged = true` on both, and confirming a
  pre-existing older dispense row (created before this phase existed)
  correctly defaulted to `false` via the migration's own
  `DEFAULT false` - the backfill working, not just the write path for new
  rows.
- **Backend only** - no frontend this phase, matching this module's own
  "backend first" convention; a conflict-warning dialog on the
  pharmacist dispense form is a natural later frontend item, not scoped
  here. No new Keycloak role, no `node-bff`/`PUBLIC_ROUTES` change.

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

- **`Medication.controlledSubstanceSchedule`** (nullable, allow-listed
  `schedule_i`..`schedule_v`) - set via a **new dedicated action
  endpoint**, `POST /api/clinic/medications/{id}/controlled-substance-
  schedule`, deliberately not folded into the existing
  `POST /{id}/update` - **`hasRole('CLINIC_ADMIN')` only**, tighter than
  the catalog's own pharmacist+clinic_admin gate, matching this app's
  existing convention for a consequential/tighter-gated state change
  (deactivate/reactivate, stock-batch write-off) rather than an
  in-controller role check bolted onto the generic update path. `null`
  clears it - correcting a data-entry mistake.
- **`DispenseService` refactored, not rewritten** - the existing stock
  /quantity validation was extracted into two private helpers
  (`requireValidBatch`, `requireWithinPrescribedTotal`) and a shared
  tail end (`finalizeDispense` - decrement the batch, build and save the
  real `DispenseRecord`), so both the plain and the new controlled
  -substance paths share the exact same checks. The controlled path runs
  them **twice**: once read-only at request time (an early,
  non-authoritative check), and again for real at cosign time, since
  stock can move between the two steps - confirmed live below
  (insufficient-stock-at-cosign was also exercised via the pure-unit
  test, not just designed).
  - **`dispense(...)`** (existing, unchanged signature) - a new guard
    right after resolving the medication: a non-null
    `controlledSubstanceSchedule` now 400s with "...use the
    controlled-substance request flow instead," a plain
    `ResponseStatusException` thrown directly, same precedent this
    method's own pre-existing batch-mismatch check already used - not a
    new dedicated exception class.
  - **`requestControlledSubstanceDispense(...)`** (new) - guards the
    *opposite* direction (a non-controlled medication 400s, "use the
    plain dispense endpoint instead"); runs the existing phase-27
    `checkClinicalSafety` for `conflictOverridden` **at request time**
    - this is deliberately where the real "does this look safe, and do
    I acknowledge it" judgment call belongs, not deferred to whoever
    cosigns later; saves a new `PendingControlledSubstanceDispense`
    (`status = "pending"`) with no stock decrement yet - the workflow
    entity captures intent only.
  - **`coSignControlledSubstanceDispense(...)`** (new) - the one real
    gate this whole phase exists for: `coSignedBy.equals(requestedBy)`
    409s ("A different pharmacist or clinic_admin must co-sign this
    request"), and an already-resolved (non-`"pending"`) request also
    409s. Re-runs both validation helpers for real, calls
    `finalizeDispense(..., dispensedBy = requestedBy, coSignedBy =
    coSignedBy)`, then updates the pending row in place (`status =
    "cosigned"`, `coSignedBy`, `coSignedAt`, `dispenseRecordId` = the
    new record's id - direct traceability from the workflow row to the
    permanent record it produced).
  - **`rejectControlledSubstanceDispense(...)`** (new) - same
    already-resolved 409 guard; no stock touched, since it was never
    decremented at request time. No role restriction beyond the
    controller's own pharmacist+clinic_admin gate - the requester's own
    login can reject their own request (a withdrawal, not a
    co-sign-shaped action), confirmed live below.
- **`DispenseRecord.coSignedBy`** (new nullable column, FK to
  `app_users`) - set once at creation, never updated after, same
  append-only invariant every other field on this entity already
  documents; stays `null` for every ordinary (non-controlled) dispense.
- **`ControlledSubstanceDispenseController`** (new) - same
  `hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')` gate as `DispenseController`
  throughout (the *different-person* restriction lives in the service,
  not the role gate, since both signers hold the same role). No response
  DTO, same precedent `MedicationController` itself uses.
  `GET /api/pharmacy/controlled-substance-requests?status=` (blank-means
  -all, same shape as `MedicationController.medications(status)`),
  `POST /api/prescriptions/{id}/controlled-substance-requests`,
  `POST /api/pharmacy/controlled-substance-requests/{id}/cosign`,
  `POST /api/pharmacy/controlled-substance-requests/{id}/reject`.
- **Tests**: `DispenseServiceTest` (pure Mockito, existing file
  extended, **17/17 passing locally**, up from 9/9) - 8 new cases:
  direct dispense of a controlled substance rejected before any stock
  work (`verify(stockBatchRepository, never()).findByIdAndTenantId(...)`);
  requesting one creates a pending row with no stock decrement;
  requesting against a non-controlled medication rejected; co-signing by
  a different user decrements stock and produces a `DispenseRecord` with
  both `dispensedBy`/`coSignedBy` set correctly; co-signing by the same
  user as the requester rejected; co-signing an already-resolved request
  rejected; insufficient stock discovered only at cosign time (not
  request time) rejected there; rejecting leaves stock untouched.
  `MedicationControllerIntegrationTest` gained 2 cases (clinic_admin
  sets then clears the schedule while pharmacist gets 403; an invalid
  schedule value 400). `ControlledSubstanceDispenseControllerIntegrationTest`
  (new, 7 cases) - the full request/list/cosign happy path, same-user
  -cosign 409, reject leaves stock untouched, direct dispense of a
  controlled medication 400, requesting against a non-controlled
  medication 400, role gate, cross-tenant 404 on cosign. One new
  `TenantIsolationIntegrationTest` case
  (`controlledSubstanceRequestsAreNotReadableOrCosignableFromAnotherTenant`).
  Confirmed via a clean `mvn clean test-compile` and a full `mvn test`
  run showing `Tests run: 362, Errors: 278` (up from 344/268 - exactly
  the 18 new test methods: 8 pure-unit + 10 Testcontainers-blocked),
  every failure the identical pre-existing `Could not find a valid
  Docker environment` wall - not a regression, not a new failure mode.
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api`
  confirmed healthy, `V24` confirmed applied via `flyway_schema_history`
  (version 24, description "pharmacy controlled substances",
  `success = t`). As a real `demo-clinic-admin` login: created a real
  controlled medication ("Fentanyl"), marked it `schedule_ii` via the
  new dedicated endpoint, received a real 30-unit stock batch;
  confirmed a direct `/dispense` attempt against a real existing
  prescription now genuinely 400s with the exact designed message;
  created a real controlled-substance request (`status: "pending"`,
  `requestedBy` = the clinic_admin's own AppUser id); attempted to
  co-sign it as that **same** login and got a genuine `409` ("A
  different pharmacist or clinic_admin must co-sign this request").
  Logged out via the app's own `/auth/logout` form submission (not a
  bare fetch - confirmed a fresh username/password prompt on the next
  login, proving the SSO session actually ended) and logged back in as
  a real, different `demo-pharmacist` login; co-signed the same request
  successfully - the response carried a real `dispenseRecordId`.
  Cross-checked directly in Postgres: the stock batch dropped from 30
  to exactly 25 (a single decrement, not double-applied), and the
  resulting `dispense_records` row carries `dispensed_by` = the
  clinic_admin's id and `co_signed_by` = the pharmacist's id - two
  genuinely different AppUser ids, not the same person recorded twice.
  Created a second request and rejected it (as the same `demo-pharmacist`
  login, withdrawing their own request) with a real reason string;
  confirmed the stock batch stayed at exactly 25 afterward - a rejected
  request never touches stock, live-verified, not just asserted by the
  unit test.
- **Backend only** - no frontend this phase, same convention as phase
  27 and every other phase in this module set so far. No new Keycloak
  role - `pharmacist`/`clinic_admin` cover both signers. No
  `node-bff`/`PUBLIC_ROUTES` change.

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

- **`StockBatch` generalized and relocated - the one true refactor this
  phase makes** - moves from `com.clinicops.pharmacy` to
  `com.clinicops.inventory`, its natural home once it serves two
  domains, the same "shared resource gets its own package" precedent
  `Payment`/`Invoice` already established (living outside both
  `appointment` and `laborder`). `medicationId` became nullable; a new
  nullable `inventoryItemId` joins it, with a
  `chk_stock_batches_exactly_one_owner` CHECK - identical style to the
  existing `chk_payments_exactly_one_owner`/`chk_invoices_exactly_one_owner`.
  `CreateStockBatchRequest`/`WriteOffStockBatchRequest` moved with it
  (structurally about `StockBatch`, not `Medication`) -
  `MedicationController` cross-package-imports all three, the same
  direction `DispenseService` already reaches into this package, just
  one hop further out now. **Confirmed genuinely behavior-neutral, not
  just intended to be** - every one of the 14 files that referenced
  `StockBatch` before this phase got an import-only change; the full
  `mvn test` count before and after the relocation itself (before any
  new phase-29 tests were added) was identical, `Tests run: 362,
  Errors: 278` both times, and `DispenseServiceTest`'s own 17/17 needed
  zero changes.
- **`InventoryItemController` gets the identical nested stock-batch
  shape `MedicationController` already has** (`GET/POST
  /api/inventory/items/{id}/stock-batches`, `POST .../stock-batches/{id}/
  write-off`), reusing the exact same relocated `CreateStockBatchRequest`/
  `WriteOffStockBatchRequest` records rather than a parallel pair - keeps
  medications and general items symmetric: both can be restocked
  directly, not just through a purchase order. Plus **`GET
  /api/inventory/reorder-alerts`** - spans both `Medication` and
  `InventoryItem` (a new `MedicationRepository` cross-package dependency,
  the reverse direction of `DispenseService`'s own existing reach): for
  each catalog row with a real `reorderThreshold`, sums `quantityOnHand`
  across its own `active`-status stock batches and flags it if the sum
  is below threshold - a small `ReorderAlert(ownerType, ownerId, name,
  currentQuantity, reorderThreshold)` wrapper record. Closes phase 20's
  own documented simplification ("low-stock stayed a per-medication
  client-side badge, not a cross-catalog endpoint"). `hasAnyRole
  ('CLINIC_ADMIN', 'FRONT_DESK')` throughout - the phase's own pinned
  role decision, deliberately not the pharmacy gate.
- **`Supplier`** + **`SupplierController`** - plain soft-deactivate CRUD,
  same shape as `RoomController`.
- **`PurchaseOrder`**/**`PurchaseOrderLine`** + **`PurchaseOrderController`** -
  `PurchaseOrderWithLines` wrapper record on every response, same
  pattern as `JournalEntryWithLines`. Each line validates exactly one of
  `medicationId`/`inventoryItemId` (400 otherwise) against the real
  catalog row (404 if unowned/unknown), same
  `requireOwnedMedication`-style check `DrugInteractionPairController`
  already established. `POST .../{id}/receive` (dedicated action
  endpoint, only from `status = "ordered"` - 409 otherwise) auto-creates
  one `StockBatch` per line with the right owner; `POST .../{id}/cancel`
  same guard. **All-or-nothing receipt only in v1 - no partial-receiving
  granularity** - a real, documented scope boundary, not a silent gap.
  No update/delete on lines once created - a placed order is a real
  document, same "immutable once issued" reasoning `Invoice`/`LabOrder`
  line items already use.
- **`StockAdjustment`** + `StockAdjustmentController`
  (`POST/GET /api/inventory/stock-batches/{batchId}/adjustments`) - a
  generic, append-only correction against a batch owned by either side
  (only the batch id is ever needed, so one controller covers both
  owner types) - non-medication stock has no `Prescription` driving its
  consumption the way `DispenseRecord` does, so this closes that real
  gap. Rejects an adjustment that would take `quantityOnHand` negative
  (400, same guard spirit as `DispenseService`'s own insufficient-stock
  check), auto-flips to `depleted` at exactly zero, same convention
  `DispenseService` already established.
- **Tests**: no new pure-unit test class - this phase's own logic
  (reorder-alert summing, the exactly-one-owner checks, the adjustment
  guard) is simple enough to cover directly in Testcontainers
  integration tests, same bar phase 20's own CRUD-shaped logic used.
  `InventoryItemControllerIntegrationTest` (new, 6 cases: catalog CRUD,
  invalid category/status 400, nested receive/write-off, reorder-alerts
  correctly flags an under-threshold item and excludes an
  above-threshold one, role gate, cross-tenant 404),
  `SupplierControllerIntegrationTest` (new, 4 cases: CRUD +
  deactivate/reactivate, invalid status 400, role gate, cross-tenant
  404), `PurchaseOrderControllerIntegrationTest` (new, 5 cases: a mixed
  medication+inventory-item order receives into exactly the right
  StockBatch rows, receiving twice 409s, cancel-from-ordered works and
  cancelling an already-received order 409s, a dual/no-owner line 400s,
  cross-tenant 404), `StockAdjustmentControllerIntegrationTest` (new, 4
  cases: a positive correction and a negative usage adjustment both
  apply correctly against a medication-owned batch, a negative-pushing
  adjustment 400s against an inventory-item-owned batch, an exact-zero
  adjustment flips to `depleted`, an invalid reason 400s). Two new
  `TenantIsolationIntegrationTest` cases
  (`inventoryItemsAndTheirStockBatchesAreNotReadableOrWritableFromAnotherTenant`,
  `suppliersAndPurchaseOrdersAreNotReadableOrWritableFromAnotherTenant`).
  Confirmed via a clean `mvn clean test-compile` and a full `mvn test`
  run showing `Tests run: 383, Errors: 299` (up from 362/278 - exactly
  the 21 new test methods: 6+4+5+4+2), every failure the identical
  pre-existing `Could not find a valid Docker environment` wall - not a
  regression, not a new failure mode.
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api`
  confirmed healthy, `V25` confirmed applied via `flyway_schema_history`
  (version 25, description "inventory foundation", `success = t`). As a
  real `demo-front-desk` login through the actual browser: created a
  real `InventoryItem` ("Nitrile Gloves (Box)", PPE, reorder threshold
  20), received an initial 5-unit batch, confirmed it genuinely appeared
  in `GET /api/inventory/reorder-alerts` (5 < 20); received a second
  30-unit batch and confirmed the alert genuinely disappeared (35 ≥ 20).
  Created a real `Supplier`, placed a real `PurchaseOrder` mixing one
  medication line (200 units) and one inventory-item line (40 units),
  received it, and confirmed exactly two new `StockBatch` rows appeared
  with the correct owners and quantities, cross-checked directly in
  Postgres. Recorded a real "wasted" stock adjustment (-6) against the
  new gloves batch and confirmed `quantityOnHand` dropped from 40 to 34,
  cross-checked in Postgres. Logged out via the app's own `/auth/logout`
  form and back in as a genuinely different `demo-pharmacist` login:
  confirmed `GET /api/inventory/items` now correctly 403s (the pinned
  role boundary holding live, not just documented), and confirmed the
  *existing* pharmacy `GET /api/clinic/medications/.../stock-batches`
  flow still works completely unchanged - both the pre-existing batch
  from an earlier session and the new PO-received batch rendered
  correctly, with the relocated entity's `inventoryItemId`/`medicationId`
  fields both serializing as expected (`inventoryItemId: null` for a
  medication-owned row) - zero regression from the relocation.
- **Backend only** - no frontend this phase, same convention as 27/28.
  No new Keycloak role - `clinic_admin`+`front_desk` cover general
  inventory, `pharmacist`+`clinic_admin` still cover pharmacy stock,
  unchanged. No `node-bff`/`PUBLIC_ROUTES` change.

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

- **`Asset`** + **`AssetController`** - `GET`/`GET {id}`/`POST`/
  `POST {id}/update` (partial - `name`/`serialNumber`/`purchaseDate`/
  `purchasePrice`/`warrantyExpiry`/`status`/`notes`; `status` allow
  -listed `in_service`/`under_maintenance`/`retired`/`disposed`, no
  delete endpoint - status transitions only, same "real inventory/audit
  weight" precedent every stock-adjacent entity this session used).
  `assignedRoomId` is deliberately **not** part of the generic partial
  update - it needs real null-clear semantics (unassigning an asset from
  a room), so it gets its own dedicated action endpoint instead, the
  same precedent `MedicationController.updateControlledSubstanceSchedule`
  (phase 28) already established:
  **`POST /api/inventory/assets/{id}/assign-room`**, body `{roomId}`
  (`null` clears it), validated against `RoomRepository
  .findByIdAndTenantId` when non-null (404 if the room isn't this
  tenant's - a real cross-package reference, `com.clinicops.room` read
  from `com.clinicops.inventory`).
- **Nested maintenance log**, same convention as `MedicationController`'s/
  `InventoryItemController`'s own nested stock-batch endpoints:
  `GET/POST /api/inventory/assets/{id}/maintenance-records`.
  `performedAt` is always set server-side to `Instant.now()` (no
  backdating field in v1 - staff records what they just did, matching
  how `DispenseRecord`/`StockAdjustment` never take a client-supplied
  timestamp either); `performedBy` resolved from the JWT via
  `CurrentUserService`, same pattern `StockAdjustmentController` already
  uses. No update/delete - genuinely append-only, confirmed live below
  that two records for the same asset accumulate rather than replace.
- **Tests**: no new pure-unit test class - same bar phase 29 used (plain
  CRUD + one allow-list check, no derived-value computation worth
  isolating). `AssetControllerIntegrationTest` (new, 6 cases: CRUD +
  status transitions `in_service` -> `under_maintenance` -> `retired`,
  invalid status 400, assign-room to a real same-tenant room then clear
  it back to `null`, assign-room to another tenant's room 404,
  maintenance records accumulating rather than replacing with
  `performedBy` correctly resolved, role gate, cross-tenant 404). One
  new `TenantIsolationIntegrationTest` case
  (`assetsAndTheirMaintenanceRecordsAreNotReadableOrWritableFromAnotherTenant`).
  Confirmed via a clean `mvn clean test-compile` and a full `mvn test`
  run showing `Tests run: 390, Errors: 306` (up from 383/299 - exactly
  the 7 new test methods: 6+1), every failure the identical pre-existing
  `Could not find a valid Docker environment` wall - not a regression,
  not a new failure mode.
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api`
  confirmed healthy, `V26` confirmed applied via `flyway_schema_history`
  (version 26, description "equipment asset tracking", `success = t`).
  As a real `demo-front-desk` login: created a real `Asset` (an
  autoclave, with a real serial number/purchase price/warranty date),
  assigned it to a real existing room (`GET /api/rooms`'s own "Room 1"),
  recorded two real maintenance entries ("Annual inspection", "Replaced
  door seal") and confirmed both `performedBy` values resolved to the
  real logged-in user's `AppUser` id; transitioned status
  `in_service -> under_maintenance -> in_service`; confirmed
  `GET .../maintenance-records` returned both records (accumulated, not
  replaced); cleared the room assignment back to `null`. Cross-checked
  the final state directly in Postgres - `status = 'in_service'`,
  `assigned_room_id` genuinely `NULL`, exactly 2 rows in
  `asset_maintenance_records` for this asset. Logged out via the app's
  own `/auth/logout` form and back in as `demo-pharmacist`: confirmed
  `GET /api/inventory/assets` still correctly 403s - the phase-29 role
  boundary holding live for this new resource too, not just the ones
  built last time.
- **Backend only** - no frontend this phase, same convention as every
  phase in this module set. No new Keycloak role - reuses phase 29's
  `clinic_admin`+`front_desk` gate. No `node-bff`/`PUBLIC_ROUTES`
  change. No maintenance-due reminders/scheduling of any kind - the
  pinned "simple log" scope boundary from when this was first sketched.

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

- **A real, deliberate widening of the role gate beyond the
  appointment/lab-order precedent, per the sketch's own explicit
  reasoning**: `AppointmentPaymentController`/`AppointmentInvoiceController`
  give their third role (`provider`) *read-only* access -
  `front_desk`/`clinic_admin` alone can record a payment or generate an
  invoice. Here, `pharmacist` gets the **same read+write access** as
  `front_desk`/`clinic_admin` on both the new payment and invoice
  endpoints - `pharmacist` is genuinely the counter-staff role for a
  pharmacy sale, not a read-only clinical overseer the way `provider`
  is for an appointment. Confirmed live: `demo-provider` gets a real
  403 on both endpoints while `demo-pharmacist` can read, record, and
  generate freely.
- **A real, pre-existing gap found during research, closed as part of
  this phase's own scope**: there was **no way to read a
  `DispenseRecord` at all** before this phase - `DispenseRecordRepository`
  only ever supported an internal prescribed-total sum query; no
  controller exposed dispense history for a prescription. Closed with
  **`GET /api/prescriptions/{id}/dispense-records`** (same
  `pharmacist`+`clinic_admin` gate `DispenseController` already uses -
  a pharmacy-internal lookup, not part of the wider payment/invoice
  gate above) and a new `DispenseRecordRepository.findByIdAndTenantId`.
  Live-verified this gap was genuinely real, not theoretical: fetching
  it for a real prescription surfaced a dispense record from an earlier
  session's own phase-28 verification that had been created but was
  never readable through any API until now.
- **`payments`/`invoices` both gain `dispense_record_id`**, extending
  the existing exactly-one-owner CHECK to a three-way "exactly one of
  three" constraint (a summed-CASE form, since Postgres has no native
  3-way XOR) - `invoices.dispense_record_id` also carries its own
  `UNIQUE` constraint, matching `appointment_id`/`lab_order_id`'s
  existing DB-level "generate once" enforcement, not just the app-level
  409 check.
- **`PaymentService.recordPayment`** gained a `dispenseRecordId`
  parameter (the two existing call sites pass `null`, unchanged
  otherwise); **`JournalService.postForPayment`**'s `sourceType`
  branching grew a third arm (`"dispense_payment"`) - same
  debit-Cash/credit-Revenue posting, unconditionally, matching this
  service's own "the ledger picks up real cash events regardless of
  source" convention already established for `fee_auto_charged` rows.
- **`InvoiceService.generateForDispenseRecord`** (new) - subtotal is
  `Medication.unitPrice * DispenseRecord.quantityDispensed`, the
  "computed suggestion" the sketch describes (a future frontend would
  pre-fill it; the actual `Payment.amount` still stays staff-editable,
  same as every other owner type) - confirmed live: a real Fentanyl
  dispense (unit price $15.00, quantity 5) generated an invoice with
  subtotal/total exactly $75.00. **`InvoicePdfService.renderForDispenseRecord`**
  (new) resolves the owner reference ("Dispense of `<medication>` ×`<
  quantity>`") and the patient name via the same multi-hop chain
  `DispenseService.resolvePatientId` already established
  (`Prescription -> Encounter -> Appointment -> patientId`, falling
  back to the guest `contactName`) - two new constructor deps
  (`PrescriptionRepository`, `EncounterRepository`).
- **`DispensePaymentController`**/**`DispenseInvoiceController`** (new)
  - identical shape to their appointment/lab-order counterparts:
  `GET/POST /api/dispense-records/{id}/payments`,
  `GET/POST /api/dispense-records/{id}/invoice`,
  `GET /api/dispense-records/{id}/invoice/pdf`.
- **Refunds stay unchanged, deliberately** - `PaymentController`
  (`POST /api/payments/{id}/refund`) is owner-agnostic by design
  (addressed by the payment's own id, not its owner type), so widening
  its role gate to include `pharmacist` would also let a pharmacist
  refund an appointment or lab-order payment, out of scope. A
  pharmacist wanting a dispense payment refunded still needs a
  `front_desk`/`clinic_admin` login for that one action - a real,
  documented limitation, not a silent gap.
- **The clinic-admin analytics dashboard's `PaymentRepository.findDailyRevenue`
  needed zero code change** - it already sums `payments.amount` with no
  owner-column branching, so dispense payments are picked up
  automatically now that this phase has shipped.
- **Tests**: `PaymentServiceTest` (existing file, 2 cases updated for
  the new parameter, 1 new case), `JournalServiceTest` (existing file,
  1 new case for the `dispense_payment` source type), `InvoicePdfServiceTest`
  (existing file, 2 new cases - a real PDF with medication/quantity/
  patient name, and a guest-channel fallback to `contactName`) - all
  pure Mockito, **13/13 passing locally** across the three files.
  `DispensePaymentControllerIntegrationTest` (new, 4 cases: record +
  list, `pharmacist` can both read and record while `provider` is
  forbidden, a mismatched invoiceId 400, cross-tenant 404),
  `DispenseInvoiceControllerIntegrationTest` (new, 5 cases: generate
  from `unitPrice * quantityDispensed`, duplicate-generate 409,
  pre-generation 404, a real PDF, cross-tenant 404), one new
  `DispenseControllerIntegrationTest` case for the new
  `GET /api/prescriptions/{id}/dispense-records` endpoint, one new
  `TenantIsolationIntegrationTest` case covering dispense payments and
  invoices together. Confirmed via a clean `mvn clean test-compile` and
  a full `mvn test` run showing `Tests run: 405, Errors: 317` (up from
  390/306 - exactly the 15 new test methods: 4 pure-unit + 11
  Testcontainers-blocked), every failure the identical pre-existing
  `Could not find a valid Docker environment` wall - not a regression,
  not a new failure mode.
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api`
  confirmed healthy, `V27` confirmed applied via `flyway_schema_history`
  (version 27, description "pharmacy billing integration",
  `success = t`). As a real `demo-pharmacist` login: fetched
  `GET /api/prescriptions/{id}/dispense-records` for a real prescription
  and confirmed it surfaced a genuine dispense record from an earlier
  session's own phase-28 verification that had never been readable
  before; generated a real dispense invoice from it and confirmed the
  subtotal/total computed to exactly $75.00 ($15.00 × 5); downloaded
  the real PDF and confirmed the `%PDF` magic header; recorded a real
  $75.00 card payment against it, invoice-linked, gateway-routed
  (`gatewayStatus: "succeeded"`). As a real `demo-accountant` login:
  confirmed `GET /api/clinic/journal-entries` showed a genuine new
  entry with `sourceType: "dispense_payment"` and two balanced $75.00
  debit-Cash/credit-Revenue lines, and that `GET /api/clinic/trial-balance`
  reflected it in both accounts' running totals. As a real `demo-provider`
  login: confirmed both `GET /api/dispense-records/{id}/payments` and
  `GET /api/dispense-records/{id}/invoice` now correctly 403 - the
  deliberately narrower (compared to appointments' own provider
  -readable) gate holding live, not just documented.
- **Backend only** - no frontend this phase, same convention as 27-30.
  No new Keycloak role - `pharmacist` already exists (phase 20). No
  `node-bff`/`PUBLIC_ROUTES` change.

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

- **`GET /api/pharmacy/queue` gains `suggestedMedicationId`** -
  `PrescriptionDispenseView` (the existing queue projection) turned out
  to be a plain interface tied directly to `findPendingDispense`'s
  native-query column aliases, with no room for a computed field
  without wrapping it. New **`PrescriptionQueueEntry`** record
  (`com.clinicops.pharmacy`) carries every field the old view already
  exposed (identical component names, so Jackson serializes
  byte-identical JSON for every pre-existing field - purely additive,
  confirmed via grep that `findPendingDispense` had exactly one
  consumer anywhere in the codebase, so this was safe to change) plus
  the new nullable field. `DispenseController.queue()` fetches the
  tenant's active medications once and maps each row through a small
  private `suggestMedication(...)` helper - same "controller composes
  repositories directly for simple read-only aggregation" precedent
  `InventoryItemController.reorderAlerts`/`ClinicAnalyticsController`
  already established, no new service bean.
- **The match itself is deliberately not real fuzzy matching** - the
  same bidirectional, case-insensitive substring check phase 27's own
  allergy-conflict check already uses, first match wins, no
  scoring/ranking. Same "keep minimal in v1" convention this app
  already applies to ICD-10/`Prescription.route` - a real
  fuzzy-matching library or a Postgres trigram-similarity extension
  would be real added infrastructure for a feature this sketch itself
  only ever asked to be a *hint*.
- **Stock batches for a medication come back FEFO-sorted** - new
  `StockBatchRepository.findAllByMedicationIdAndTenantIdOrderByExpiryDateAsc`
  (a plain Spring Data derived query; confirmed live that Postgres's
  own default NULL-ordering on `ASC` already sorts a no-expiry batch
  last, no explicit `NULLS LAST` clause needed).
  `MedicationController.stockBatches` switches to it - the *only* code
  change; the existing frontend (`useStockBatches`, the `<select>`
  dropdown) needed **zero** changes to benefit, since array order from
  the API flows straight through to the rendered options list. Scoped
  to pharmacy/medication batches only - `InventoryItemController`'s own
  general-inventory stock-batches endpoint is untouched, matching the
  sketch's own "smarter *dispensing*" framing.
- **Frontend: `pages/pharmacist/Dashboard.jsx`'s `DispensePanel`** -
  `medicationId` state now initializes from
  `prescription.suggestedMedicationId || ''` instead of always
  starting empty (the pharmacist can still change the selection
  freely - a plain pre-filled `<select>`, not a locked value); a small
  muted hint line appears under the Medication field only when a
  suggestion is present (`pharmacistPage.suggestedMedicationHint`, new
  key added to both `en.json`/`am.json` in lockstep, confirmed 100%
  key-parity afterward except the two pre-existing intentional
  English-only pluralization keys). No other frontend file changes
  needed - both backend changes reach the UI for free through the
  hooks that already existed.
- **Tests**: no new pure-unit test class or new frontend Vitest
  coverage - `DispensePanel` isn't part of the "shared components"
  scope Frontend phase S deliberately limited itself to, and the
  matching/sort logic is simple enough to cover directly in
  Testcontainers integration tests, same bar this session's other
  CRUD-shaped phases used. `DispenseControllerIntegrationTest` gained 2
  cases (a queue row's `suggestedMedicationId` correctly matches an
  overlapping catalog medication; stays absent when nothing matches).
  `MedicationControllerIntegrationTest` gained 1 case (stock batches
  come back earliest-expiry-first with a no-expiry batch sorting
  last). Confirmed via a clean `mvn clean test-compile` and a full
  `mvn test` run showing `Tests run: 408, Errors: 320` (up from
  405/317 - exactly the 3 new test methods, all Testcontainers-blocked,
  no pure-unit count change), every failure the identical pre-existing
  `Could not find a valid Docker environment` wall - not a regression.
  `npm run build` clean, `npm test` (36/36) unaffected.
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api
  node-bff` + `docker compose restart nginx` (the known stale
  -upstream-IP gotcha after a `node-bff` recreate), both containers
  healthy. As a real `demo-pharmacist` login: confirmed via the real
  API that all three genuine queue rows from earlier sessions'
  verification work now carry a correct `suggestedMedicationId`
  matching a real catalog medication; created three new stock batches
  at different expiry dates (including one with no expiry at all) and
  confirmed via a direct API call they came back earliest-expiry-first
  with the no-expiry batch last. Then confirmed the same thing **in the
  actual rendered browser UI** (Dark theme + Amharic, not simulated) -
  expanding a real queue row showed "Amoxicillin 500mg" already
  pre-selected in the Medication dropdown with the Amharic hint text
  correctly rendered beneath it, and reading the real `<select>`'s own
  option order confirmed the exact same FEFO ordering the API returned
  (`EARLY-VERIFY (2027-01-01)` -> `LATE-VERIFY (2027-06-01)` ->
  `BATCH-001 (no expiry)` -> `NO-EXPIRY-VERIFY (no expiry)`).
- **Backend + one small, targeted frontend change** - the first
  pharmacy-expansion phase this session to touch the frontend, for the
  reason explained above. No new Keycloak role, no
  `node-bff`/`PUBLIC_ROUTES` change, no new migration.

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

- **`com.clinicops.pharmacy.PrescriptionRefillRequest`** (new entity) -
  `prescriptionId`/`patientId`/`requestedBy` fixed at creation, `notes`
  (the patient's own optional note), `status` (requested/approved/
  denied), `reviewedBy`/`reviewedAt`/`reviewNotes` set once by staff.
  **`PatientPrescriptionService`** (new bean, mirrors `LabOrderService`'s
  own "one service for the whole domain" shape) - `myPrescriptions`
  resolves the caller's own owned encounter ids
  (`Appointment.findAllByCustomerUserId` -> `Encounter.findByAppointmentId`,
  exactly `LabOrderService.myLabOrders`'s own chain, minus its
  direct-`customerUserId`-on-the-resource union half, since `Prescription`
  has no such column and never will - a patient can't create their own
  prescription, only a provider can); `createRefillRequest` rejects a
  non-`active` prescription (400) and a duplicate pending request for
  the same prescription (409) - two implementation-level guards not
  explicitly named in the sketch, same "obviously-nonsensical action
  guard" convention `DispenseService`'s own prescribed-total check
  already uses; `approve` writes a `refill_ready` `Notification` row if
  the patient has an email on file, an exact copy of
  `LabOrderStatusService.review`'s own pattern (new
  `RefillReadyPayload(String medicationName)` record plus one new
  `case "refill_ready"` arm in `SmtpEmailSender`'s existing `switch`);
  `deny` **deliberately sends no notification** - the sketch only ever
  names the "ready" message for approval, a documented scope boundary.
  **`PatientPrescriptionController`** (new) mirrors
  `PatientLabRequestController`'s own bundling of patient + staff
  endpoints in one file: `GET /api/my-prescriptions`,
  `POST /api/my-prescriptions/{id}/refill-requests`,
  `GET /api/my-refill-requests` (all `hasRole('PATIENT')`), plus the
  staff review queue `GET /api/pharmacy/refill-requests?status=` and
  `POST /api/pharmacy/refill-requests/{id}/approve`/`.../deny`
  (`hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')`, matching
  `DispenseController`'s own gate, PHI-audited on the two write actions).
- **A real bug found live, not caught by the (locally Windows-blocked)
  integration suite**: `createRefillRequest` originally took `tenantId`
  from `TenantContext.require()` at the controller layer, the same way
  every staff-only endpoint in this app resolves it - but a `patient`
  JWT carries no `organization` claim at all, so `TenantContext` is
  always `null` for a patient token, and `.require()` throws. Every real
  patient request to this endpoint 500'd. Fixed by resolving `tenantId`
  from the prescription's own row instead (`prescription.getTenantId()`),
  exactly matching `LabOrderService.createRequest`'s own precedent -
  patient-facing endpoints in this app never touch `TenantContext`, they
  resolve tenant from an already-owned resource. The (in-memory) fixture
  -built JWTs in `PatientPrescriptionControllerIntegrationTest` would
  have hit this exact bug too, once CI's Testcontainers run reached it -
  this was caught first, live, before that happened.
- **A second real, pre-existing bug found live**: `PhiAccessAuditService
  .STAFF_ROLES` (`com.clinicops.phiaudit`) never had `pharmacist` added
  when that role was introduced in phase 20 - every PHI-audited
  pharmacist action (this phase's own approve/deny included) was logging
  `actor_role = "unknown"` instead of `"pharmacist"`. Fixed by adding it
  to the list; confirmed live via a fresh approve call that the audit row
  now correctly reads `actor_role = "pharmacist"`.
- **Tests**: `PatientPrescriptionControllerIntegrationTest` (new, 11
  cases) - `myPrescriptions` scoped to the caller's own appointments
  only; `quantityAlreadyDispensed` reflects a real prior dispense;
  creating a refill request succeeds for an owned active prescription; a
  non-owned prescription 404s; a non-active prescription 400s; a
  duplicate pending request 409s; `myRefillRequests` lists only the
  caller's own; approving flips status and writes a real notification
  row (a second approve 409s); denying flips status with `reviewNotes`
  and writes no notification; role gates hold on both sides; cross
  -tenant refill actions 404. One new `TenantIsolationIntegrationTest`
  case (`prescriptionRefillRequestsAreNotApprovableOrDeniableFromAnotherTenant`).
  No new pure-unit test class - the ownership-chain/notification logic
  is exercised directly in the Testcontainers integration tests, same
  bar this session's other comparable service-level logic used.
  Confirmed via a clean `mvn clean test-compile` and a full `mvn test`
  run showing `Tests run: 420, Errors: 332` (up from 408/320 - exactly
  the 12 new test methods: 11 + 1), 88 pure-unit tests unaffected, every
  failure the identical pre-existing `Could not find a valid Docker
  environment` wall - confirmed by grepping every surefire report: 49
  files hit that exact wall, the other 16 clean-passed, none failed any
  other way.
- **Live-verified against the real running stack, building the full
  ownership chain live** (a direct Postgres query first confirmed **zero**
  existing patient-portal-owned prescriptions existed anywhere in this
  dev environment, so this phase's own verification couldn't reuse old
  seed data the way several earlier phases did) - `docker compose up -d
  --build --force-recreate spring-boot-api`, `V28` confirmed applied via
  `flyway_schema_history` (version 28, `success = t`). As a real
  `demo-patient` login, booked a genuine new appointment through the
  actual patient-portal booking endpoint; as `demo-front-desk`, walked it
  through the real check-in state machine (`booked -> checked_in ->
  roomed -> with_provider`); as `demo-provider`, documented a real
  encounter and a real Penicillin V prescription against it. Back as
  `demo-patient`: `GET /api/my-prescriptions` correctly surfaced the new
  prescription with `quantityAlreadyDispensed: 0`; submitted a real
  refill request (hit the `TenantContext` bug above, fixed it, redeployed,
  and confirmed the same request now succeeds); an immediate second
  request for the same prescription genuinely 409'd; `GET /api/my-refill-
  requests` showed it. As `demo-pharmacist`: the real request appeared in
  `GET /api/pharmacy/refill-requests`; approved it, and cross-checked
  directly in Postgres that a real `notifications` row now exists with
  `type = 'refill_ready'` - and confirmed a real email ("Your
  prescription refill is ready - Penicillin V") actually landed in
  Mailpit's own web UI (`:8025`), not just that the outbox row was
  written. Created and denied a second request and confirmed no new
  notification row was written for it, matching the passing local test's
  own assertion, now live. Redeployed a second time for the PHI-audit
  role fix and confirmed, via a third fresh request/approve cycle, that
  the resulting `phi_access_log` row now reads `actor_role = "pharmacist"`.
- **Backend only** - no frontend this phase, the direct scoping answer
  above. No new Keycloak role - `patient`/`pharmacist`/`clinic_admin` all
  already exist. No `node-bff`/`PUBLIC_ROUTES` change (patient
  -authenticated, not public).

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

- **`PharmacyAnalyticsController`** (`GET /api/pharmacy/analytics?days=`,
  `hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')` - matches `DispenseController`'s
  own gate) - same `DEFAULT_WINDOW_DAYS=30`/`MAX_WINDOW_DAYS=180` clamp
  as phase 19. `PharmacyAnalyticsSummary(dispensingVolume,
  medicationDispenseCounts)`. `DispenseRecordRepository.findDailyDispenseVolume`
  (new, native query) sums `quantity_dispensed` per day - **units
  dispensed, not a row count** - "volume" reads more usefully as units
  than an event count. `DispenseRecordRepository.countByMedicationSince`
  (new) embeds the medication name directly via a join (same "embed
  what the caller needs, don't force a second lookup" convention the
  phase-20 pharmacist-dashboard bug fix established), backing a new
  `MedicationDispenseCount` projection.
- **`InventoryAnalyticsController`** (`GET /api/inventory/analytics?days=`,
  `hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')` - matches
  `InventoryItemController`'s own gate) - every field here is a
  current-state snapshot, not a historical series, so the `days` param
  currently governs nothing (kept for shape parity with the sibling
  endpoint, documented explicitly as unused rather than silently dead).
  `InventoryAnalyticsSummary(totalValuation, lowStock, expiringSoon,
  assetStatusCounts)`.
  - `totalValuation` - two new native scalar queries on
    `StockBatchRepository` (`sumMedicationValuation`/
    `sumInventoryItemValuation`, `SUM(quantity_on_hand * unit_price)`
    each joined to its own owning catalog table, since `unitPrice`
    lives on `Medication`/`InventoryItem`, not `StockBatch` itself),
    summed together in the controller rather than a UNION query.
  - `expiringSoon` - a **fixed 30-day forward-looking window,
    deliberately not tied to the endpoint's own `days` param** - the
    `days` param is a historical-lookback window everywhere else in
    this app; expiry risk is forward-looking, a semantically different
    question. New `StockBatchRepository.findExpiringSoon` native query,
    backing a new `ExpiringBatch` projection that resolves the owning
    name via two `LEFT JOIN`s (a batch owns exactly one of
    medicationId/inventoryItemId).
  - `lowStock` - **duplicates `InventoryItemController.reorderAlerts()`'s
    own ~15-line aggregation loop** rather than depending on that
    controller as a collaborator - a deliberate choice matching this
    codebase's "plain composition across repositories, no
    cross-controller coupling" convention (reuses the existing
    `ReorderAlert` record type; identical data to
    `GET /api/inventory/reorder-alerts`, just bundled into the unified
    summary too).
  - `assetStatusCounts` - new `AssetRepository.countByStatus`, identical
    shape to `AppointmentRepository.countByStatus`, reusing the
    existing `StatusCount` projection.
- **Frontend (pharmacy only)** - `pages/pharmacist/Dashboard.jsx` gains
  a day-range toggle (7/30/90, same `WINDOW_OPTIONS` pattern as
  `clinic-admin/Dashboard.jsx`) and two new charts in a
  `grid lg:grid-cols-2` of `ChartCard`s: `DispenseVolumeChart.jsx` (same
  area-chart-over-time shape as `AppointmentVolumeChart.jsx`) and
  `MedicationDispenseCountChart.jsx` (same categorical bar-top-N shape
  as `ProviderUtilizationChart.jsx`, but - since the backend already
  embeds `medicationName` - needs no separate name-resolution map prop
  the way that chart does for providers). New `usePharmacyAnalytics`
  hook in `api/queries.js`, mirroring `useClinicAnalytics`'s exact
  shape.
- **A real, significant pre-existing bug found live, not introduced by
  this phase but first tripped by it** - `AppointmentVolumeChart.jsx`
  and `RevenueChart.jsx` (both phase 19) pass `formatDayLabel` directly
  as a Recharts `tickFormatter`. Recharts always calls a tick formatter
  as `(value, index)`; `formatDayLabel`'s own second parameter is an
  optional **timezone override** (`formatDayLabel(iso, zone)`, see
  `lib/format.js`), not an index. Every tick past the first
  (`index >= 1`, truthy) silently passed the tick's own numeric index as
  `zone` into `resolveTimezone`, which - only under "clinic" timezone
  -display mode (frontend phase N) - returned that raw number straight
  through to `Intl.DateTimeFormat({ timeZone: 1 })`, throwing
  `RangeError: Invalid time zone specified: 1` **during React's render
  pass**, blanking the *entire* page (not just the chart - no error
  boundary catches a render-phase throw here). Live-reproduced on the
  pharmacist Dashboard (this phase's own new `DispenseVolumeChart.jsx`
  copied the same broken pattern) with a real browser profile already
  in "clinic" timezone mode from earlier phase-N verification - the
  clinic-admin dashboard has almost certainly been silently carrying
  this same crash for any real user in that mode with a multi-tick
  analytics window, since phase 19 shipped. **Fixed in all three call
  sites** - `tickFormatter={(day) => formatDayLabel(day)}` instead of
  `tickFormatter={formatDayLabel}` - confirmed live post-fix across all
  three day-range windows (7/30/90) with the same "clinic" timezone
  -mode browser profile that originally reproduced it. `ChartTooltip`'s
  own `formatLabel={formatDayLabel}` usage was unaffected (that call
  site already passes exactly one argument).
- **Tests**: `PharmacyAnalyticsControllerIntegrationTest` (new, 4 cases)
  - dispensing volume correctly excludes a dispense outside the window;
  medication dispense counts aggregate correctly and embed the right
  name; role gate; cross-tenant scoping.
  `InventoryAnalyticsControllerIntegrationTest` (new, 6 cases) -
  valuation sums both owner types and excludes a non-active batch;
  expiring-soon includes an in-window batch and excludes an
  out-of-window one and a no-expiry one; low-stock matches
  `GET /api/inventory/reorder-alerts`'s own result for identical
  fixture data; asset status counts aggregate correctly; role gate;
  cross-tenant scoping. No new pure-unit test class or `TenantIsolationIntegrationTest`
  case - same reasoning phase 23's cancel-series case already used (a
  read-only aggregation endpoint doesn't fit that file's per-resource
  "seed in A, deny to B" shape as cleanly as a CRUD endpoint). New
  `AbstractIntegrationTest.createDispenseRecord` fixture helper (a
  direct repository insert, not through `DispenseService`, so a test
  can control `createdAt` for day-bucketed assertions - the field must
  be set *before* the first save, since the column is
  `updatable = false`). Confirmed via a clean `mvn clean test-compile`
  and a full `mvn test` run showing `Tests run: 430, Errors: 342` (up
  from 420/332 - exactly the 10 new test methods), 88 pure-unit tests
  unaffected; confirmed via grepping every surefire report that all 342
  failures hit the identical pre-existing `Could not find a valid
  Docker environment` wall, none any other way. Frontend: `npm run
  build` clean, `npm test` (36/36) unaffected (page-level changes, not
  shared-component changes - consistent with frontend phase S's own
  scope boundary).
- **Live-verified against the real running stack, through `node-bff`
  directly (`:3000`)** - `docker compose up -d --build --force-recreate
  spring-boot-api node-bff` (both healthy). As a real `demo-pharmacist`
  login: `GET /api/pharmacy/analytics?days=90` surfaced real dispense
  data from this session's own earlier phases (27/28/31/32)
  verification, cross-checked exactly against a direct Postgres query
  (`SUM(quantity_dispensed) GROUP BY CAST(created_at AS date)`); loaded
  the real pharmacist Dashboard and confirmed both new charts render
  correctly (after finding and fixing the timezone crash above),
  exercised all three day-range toggles (7/30/90) with zero console
  errors post-fix, and confirmed the whole section renders correctly in
  Dark theme + Amharic (both already active from an earlier session's
  own preference, confirmed live not just read from code). As a real
  `demo-front-desk` login: `GET /api/inventory/analytics` returned
  `totalValuation: 961.50`, cross-checked exactly against a direct
  Postgres query summing both owner types' active batches; `assetStatusCounts`
  reflected the one real asset from phase 30's own verification. As a
  real `demo-provider` login: confirmed a genuine `403` on both new
  endpoints, the pinned role boundaries holding live.
- **A real, separate Docker Desktop environment issue, not a code
  problem, left the nginx (`:80`) route unverified this phase** - after
  rebuilding `node-bff`, `nginx` repeatedly failed to start
  (`host not found in upstream "node-bff:3000"` at config-load, despite
  `node-bff` being healthy and its hostname resolving correctly from a
  fresh throwaway container on the same network - confirmed directly),
  then once that cleared, hit `Bind for 0.0.0.0:80 failed: port is
  already allocated` on every subsequent recreate attempt even with the
  container fully stopped and removed - `com.docker.backend.exe` and
  `wslrelay.exe` (both real Docker Desktop/WSL2 infrastructure
  processes, running since the stack's own last boot) kept holding the
  port at the Docker Desktop internal-state level, out of sync with the
  actual (absent) container. A full Docker Desktop restart would very
  likely clear it, but was deliberately not done unilaterally - it
  would take down the entire stack (postgres/keycloak/redis/mailpit
  included) for what is otherwise a pure "verify through the alternate
  route" nicety, not a correctness concern (nginx is a pure pass
  -through reverse proxy for these routes with zero logic of its own -
  every new endpoint and the frontend fix were already fully verified
  through `node-bff` directly, the identical backend code path). Owed:
  a real `curl`/browser check through `http://localhost/` once nginx is
  confirmed healthy again.
- **Backend + one small, targeted frontend change (pharmacy only)** -
  matches phase 32's own precedent. No new Keycloak role, no
  `node-bff`/`PUBLIC_ROUTES` change.

This closes the entire "Pharmacy expansion + stock management module"
- all ten sequential phases (27-34) sketched 2026-09-29 are now built.

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

- **`Items.jsx`** - closely mirrors `pharmacist/Medications.jsx`'s own
  create-form-above-`DataTable`+`renderExpanded` shape (edit/deactivate,
  a nested plain-`<ul>` stock-batches panel with receive/write-off), one
  level deeper: each active batch gets an "Adjustments" toggle showing
  its `StockAdjustment` history plus a one-line add-adjustment form -
  new nested-within-nested pattern, still a plain list, not a further
  `DataTable`, matching `Medications.jsx`'s own shallow-nesting
  precedent. New parallel hooks (`useInventoryStockBatches`/
  `useReceiveInventoryStockBatch`/`useWriteOffInventoryStockBatch`)
  pointed at the `/api/inventory/items/...` paths - deliberately not a
  generalization of the existing pharmacy `useStockBatches` hook, since
  the path differs and touching the working pharmacist code for this
  was unnecessary risk for zero benefit.
- **`Suppliers.jsx`** - plain CRUD, same inline-edit-in-place shape as
  `clinic-admin/Rooms.jsx` (simpler than Items - no nested panel).
- **`PurchaseOrders.jsx`** - a dynamic line-item create form (each line
  toggles medication vs. inventory-item, `CreatePurchaseOrderRequest`'s
  own nested-lines-in-one-request shape), `renderExpanded` showing the
  response's own already-embedded `lines` (`PurchaseOrderWithLines`) -
  same "no second fetch needed" pattern `accountant/Journal.jsx`'s own
  journal-entry lines already established - plus Receive/Cancel buttons
  gated on `order.status === 'ordered'`.
- **`Assets.jsx`** - status transitions, a dedicated room-assign
  `<select>` wired to the null-clearing `AssignRoomRequest` action, and
  a nested append-only maintenance-record log (same shallow-list shape
  as Items' stock batches).
- **`Dashboard.jsx`** - mirrors `accountant/Dashboard.jsx`'s own stat
  -cards-plus-link-tiles shape, finally giving the phase-34
  `GET /api/inventory/analytics` endpoint a real UI home (it had zero
  consumers since being built). One inline asset-status bar chart
  (`ChartCard`/`useChartPalette`/`ChartTooltip`, the exact phase-19
  -validated palette) - not a new shared chart component for one page,
  matching this session's own "no premature abstraction" bar.
- **Two real, structural bugs found live, not introduced by this phase
  but first tripped by it - the same "no shortcut past real gaps"
  standard this session has held throughout:**
  1. **`front_desk` had no way to read the medication catalog at all**
     (`MedicationController`'s `GET` endpoints were `pharmacist`+
     `clinic_admin` only) - even though `PurchaseOrderController`
     already lets `front_desk` create a medication-owned
     `PurchaseOrderLine` on the backend. The new page's own medication
     picker silently had zero options for `front_desk`, and an existing
     order's medication line rendered as a raw UUID instead of a name.
     **Fixed by widening only the two read endpoints**
     (`GET /api/clinic/medications`, `GET /api/clinic/medications/{id}`)
     to include `front_desk` - deliberately asymmetric from the write
     endpoints below them (still `pharmacist`+`clinic_admin` only),
     same "broader read gate than write gate" precedent
     `RoomController`'s own provider-readable `GET` already sets.
     Confirmed live: `demo-front-desk` now sees real medication options
     and can place a real medication-line purchase order end to end;
     `demo-provider` still gets a clean 403.
  2. **`PurchaseOrders.jsx`/`Assets.jsx` resolved names only against
     `active`-status lists** (correctly scoped to the create-form's own
     pickers), so a historical order/asset referencing a since
     -deactivated supplier, medication, item, or room would show a raw
     id instead of its name. Fixed by fetching a second, unfiltered list
     purely for the name-resolution maps, leaving the active-only lists
     untouched for the pickers.
- **Tests**: `MedicationControllerIntegrationTest` gained
  `frontDeskCanReadTheCatalogButNotWriteIt` (read succeeds, write still
  403s) - the one backend test this phase needed, since everything else
  driving these pages was already covered by phases 29/30/34's own
  suites. Confirmed via a clean `mvn clean test-compile` and a full
  `mvn test` run showing `Tests run: 431, Errors: 343` (up from 430/342
  - exactly the one new test method), 88 pure-unit tests unaffected;
  confirmed via grepping every surefire report that all 343 failures
  hit the identical pre-existing `Could not find a valid Docker
  environment` wall, none any other way. Frontend: `npm run build`
  clean, `npm test` (36/36) unaffected - this phase adds no new
  shared-component test surface per frontend phase S's own scope
  boundary (page-level changes).
- **Live-verified against the real running stack, through `node-bff`
  directly (`:3000`)** - `docker compose up -d --build --force-recreate
  spring-boot-api node-bff` (both healthy). As a real `demo-front-desk`
  login: the Dashboard's stat cards/chart matched a direct Postgres
  cross-check of total valuation exactly ($961.50); created a real
  `InventoryItem` ("Surgical Masks (Box)"), received 15 units, confirmed
  the low-stock badge and the Dashboard's own low-stock count both
  reflected it; opened the nested Adjustments panel (untested further
  live due to the tool-flakiness noted below, but confirmed correct via
  the same component pattern `Medications.jsx` already proved). Created
  a real `Supplier` ("Northside Medical Supplies"). Placed a real
  medication-owned purchase order against it (Penicillin V × 25, the
  bug-1 fix's own live proof), received it, and cross-checked a genuine
  new 25-unit `StockBatch` directly in Postgres. Confirmed the
  pre-existing PO's own medication line now resolves to "Amoxicillin"
  instead of a raw UUID (bug-2's own live proof). On the Autoclave
  asset: assigned it to "Room 1" then cleared the assignment (both
  confirmed via a direct `GET /api/inventory/assets` fetch, not just
  the UI), cycled status to `under_maintenance` and back, and added a
  real third maintenance record ("Filter replaced") confirmed via a
  direct fetch of the endpoint. Confirmed `demo-pharmacist` and
  `demo-provider` both correctly 403 on `/api/inventory/*` and (for
  `demo-provider`) on the now-front-desk-readable `/api/clinic/
  medications` too. Exercised the whole Inventory section once in Dark
  theme + Amharic together (nav group, stat cards, and the chart all
  confirmed correctly translated/themed via real screenshots).
- **A real browser-automation tool-flakiness pattern hit repeatedly
  this phase, not a product bug** - `computer` tool screenshots
  intermittently timed out ("renderer frozen") and, several times
  independent of click position (including once on a plain local-state
  toggle with zero network calls), the SPA landed back on `/` after an
  action - `/auth/me` always confirmed the session stayed genuinely
  authenticated throughout. Worked around by re-verifying state via
  direct authenticated `fetch` calls after each such incident rather
  than trusting the visual result alone, and by using native-setter +
  `dispatchEvent` JS to drive `<select>`/`<input>` elements reliably
  for the create-purchase-order and assign-room flows once repeated
  coordinate-based clicks proved unreliable in this session.
- **Standing note, unresolved since phase 34**: `nginx` (`:80`) is
  still stuck in the same Docker Desktop internal port-allocation
  desync - every `docker compose up -d nginx` this phase started the
  container, then it exited again within seconds, still holding no
  network attachment. Not a code issue (confirmed again this phase -
  every new endpoint and page work identically through `node-bff`
  directly, the identical backend/BFF code path nginx would just proxy
  through). Owed: a real check through `http://localhost/` once this
  clears or a deliberate Docker Desktop restart is authorized.

## Phase 36: drug-interaction pairs UI

Second of the six frontend gap-closing phases above - the smallest one.
Closes the last gap `com.clinicops.pharmacy.DrugInteractionPairController`
(phase 27) left open: a fully-built, fully-tested hard-delete CRUD
backend with zero frontend, even though `DispenseService`'s own
clinical-safety check has been reading from it since phase 27. Zero
backend changes.

- **`pages/pharmacist/DrugInteractions.jsx`** (new) - mirrors
  `clinic-admin/LabRates.jsx`'s own true-hard-delete CRUD shape
  (create-form-above-`DataTable`, `renderExpanded` opens a per-row edit
  panel, delete is a plain unconfirmed button click - no confirm dialog
  anywhere in this app's existing hard-delete flows, not a new pattern
  here either). Two medication `<select>`s on the create form
  (`useMedications(true, 'active')` - only active medications are
  sensible to newly pair); the edit panel only ever touches
  severity/description, since `medicationAId`/`medicationBId` are
  immutable once created on the backend (correcting which two
  medications a pair covers means delete+recreate). `severity` has no
  server-side allow-list but gets a client-side
  unset/mild/moderate/severe `<select>` (matching the entity's own
  javadoc convention) as a UI-only convenience, not a new constraint.
- **Applied the phase-35 "never resolve a display name against an
  active-only list" lesson proactively, not reactively** - the table's
  own `medicationById` map is built from `useMedications(true)` (all
  statuses), kept deliberately separate from the create-form's
  active-only picker list, so a since-deactivated medication already
  paired stays displayable by name instead of falling back to a raw
  UUID. Designed in from the start this time, since phase 35 had
  already surfaced the exact failure mode live for Purchase
  Orders/Assets - it never had to be re-triggered as a live bug here.
- **`api/queries.js`** - a small new
  `// ---- drug interaction pairs (phase 27 backend, phase 36 frontend) ----`
  section: `useDrugInteractionPairs`/`useCreateDrugInteractionPair`/
  `useUpdateDrugInteractionPair`/`useDeleteDrugInteractionPair`, the
  exact same shape `useLabRates`/`useCreateLabRate`/etc. already
  establish (a shared `invalidateDrugInteractionPairs` helper, list
  keyed `['pharmacy', 'drug-interaction-pairs']`).
- **Routing/nav**: one new route, `/pharmacist/drug-interactions`,
  `RequireRole roles={['pharmacist', 'clinic_admin']}` - the same gate
  every other pharmacist route already uses. `layout/Sidebar.jsx`
  gained one link in the `pharmacist` group and a second curated link
  in `clinic_admin`'s own group (right after its existing
  `/pharmacist/medications` link - `clinic_admin` already gets exactly
  one curated link into that module, this is the second, matching the
  established "clinic_admin gets a UI path into a module it overrides,
  not the module's own full nav" precedent).
- **i18n**: new `drugInteractionsPage.*` namespace (form labels/table
  headers/severity options/empty state/errors) plus one
  `nav.pharmacist.drugInteractions` key, added to `en.json`/`am.json`
  together - confirmed exact key-parity via the same flatten-and-diff
  Node script every prior phase this session used (916 keys each side,
  the same two pre-existing intentional English-only pluralization keys
  as always, no new gap).
- **No new backend tests** - the endpoint set was already fully covered
  by phase 27's own `DrugInteractionPairControllerIntegrationTest` (CRUD
  round trip, duplicate-pair-either-order 409, self-pair 400,
  unknown-medication 404, cross-tenant 404, role gate); this phase adds
  no new backend surface at all. Frontend: `npm run build` clean,
  `npm test` (36/36) unaffected - a page-level change only, same bar
  every prior frontend-only phase this session used (no new
  shared-component test surface per frontend phase S's own scope
  boundary).
- **Live-verified against the real running stack, through `node-bff`
  directly (`:3000`)** - `docker compose up -d --build --force-recreate
  spring-boot-api node-bff` (both healthy). As a real `demo-pharmacist`
  login: confirmed the real pre-existing Penicillin V/Amoxicillin pair
  (from phase 27's own verification) displays correctly with resolved
  names; created a real new pair (Amoxicillin 500mg / Fentanyl, severe,
  "Phase 36 verification - sedation risk") and confirmed it via a direct
  fetch (2 pairs total); submitted a self-pair (same medication for both
  fields) and got the real backend 400 - "medicationAId and
  medicationBId must be different medications" - rendered through the
  UI's own `ErrorBanner`; submitted the existing pair's two medications
  in reverse order and got the real backend 409 - "An interaction pair
  for these two medications already exists" - rendered the same way;
  expanded the new pair's row, edited its severity from severe to
  moderate, saved, and confirmed via a direct fetch the update persisted
  with the description left untouched; deleted it and confirmed via a
  direct fetch only the original phase-27 pair remained (a true hard
  delete, not a soft-deactivate) - also confirmed visually in the
  re-rendered table. Confirmed `demo-front-desk` and `demo-provider` are
  both genuinely locked out - a direct `fetch('/api/pharmacy/
  drug-interaction-pairs')` 403s for both, and navigating either login
  to `/pharmacist/drug-interactions` client-side-redirects back to `/`
  via `RequireRole`. `demo-clinic-admin` reaching the page via the new
  curated sidebar link was confirmed earlier in this same session (the
  page loads correctly inside clinic_admin's own full sidebar, with the
  nav item correctly highlighted active). Exercised the whole page once
  in Dark theme + Amharic together, using the exact same create/self-pair/
  duplicate-pair/edit/delete sequence above, not just a visual check -
  every step (the form, both real backend error messages, the edit
  panel, and the final delete) worked identically and rendered correctly
  translated/themed, confirmed via real screenshots.
- **Same JS-driven form-interaction techniques from phase 35 reused,
  not rediscovered** - native-setter + `dispatchEvent` for the
  `<select>`/`<input>` elements, and `document.querySelectorAll('tbody
  tr')`/`querySelectorAll('button')` plus a direct `.click()` call to
  reliably toggle the row-expand and hit the Save/Delete buttons,
  instead of trusting coordinate-based `computer` tool clicks - the same
  DataTable-row-is-the-click-target and Save-button-position issues
  phase 35's own write-up already flagged reproduced identically here
  and were worked around the same way.
- **Standing note, unchanged from phases 34/35**: `nginx` (`:80`) - not
  re-attempted this phase, verification stayed on `node-bff` directly
  throughout, consistent with the prior two phases' own documented
  Docker Desktop port-allocation desync.

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

- **`components/PaymentsPanel.jsx`/`InvoicePanel.jsx` gained one new
  optional `extraRoles` prop each** (default `[]`, purely additive - no
  behavior change for either existing caller,
  `front-desk/AppointmentDetail.jsx`/`lab-orders/LabOrderDetail.jsx`) -
  OR'd into each component's own internal write-gate, since phase 31's
  backend deliberately widens dispense billing to `pharmacist` (full
  read+write, the real counter-staff role for a pharmacy sale) where
  appointments/lab-orders only ever gave `provider` read-only access.
- **A real gap caught live while verifying this phase, fixed before
  calling it done**: the first version fed one combined
  `canRecordOrRefund` flag, so `extraRoles={['pharmacist']}` also
  exposed a working-looking "Refund" link to `demo-pharmacist` - but
  phase 31's own write-up explicitly kept `PaymentController.refund`
  (owner-agnostic by design) `front_desk`/`clinic_admin`-only, since
  widening it would also let a pharmacist refund an appointment or
  lab-order payment. The same class of bug frontend phase P already
  fixed once for `provider` on this exact component. Fixed by splitting
  the flag into `canRecord` (extended by `extraRoles`) and `canRefund`
  (unchanged, still exactly `front_desk`/`clinic_admin`) - confirmed
  live post-fix: `demo-pharmacist` can record a payment and generate/
  download an invoice but no longer sees a "Refund" link on the same
  payment row, while `demo-clinic-admin` viewing the identical panel
  still does.
- **`api/queries.js`** - new `useDispenseRecords(prescriptionId)`/
  `useDispensePayments`/`useCreateDispensePayment`/`useDispenseInvoice`/
  `useGenerateDispenseInvoice`, mirroring `useLabOrderPayments`/etc.'s
  exact shape, just repointed at `/api/dispense-records/{id}/...`.
  `useDispensePrescription`'s own `onSuccess` gained one more
  invalidate (`['dispense-records', prescriptionId]`) so a fresh
  dispense shows up in the new history list without a manual refresh.
- **`pages/pharmacist/Dashboard.jsx`'s `DispensePanel`** gained a new
  `DispenseHistory` sub-component below the existing dispense form - a
  plain shallow list (same nesting precedent `Items.jsx`'s stock
  -batches/`Assets.jsx`'s maintenance log already established, not a
  further `DataTable`, for what's typically 1-3 records per
  prescription), each record expandable into a `DispenseBillingPanel`
  wiring the new hooks into `<PaymentsPanel extraRoles={['pharmacist']}
  />`/`<InvoicePanel extraRoles={['pharmacist']} pdfUrl={...} />` - the
  identical wiring `LabOrderDetail.jsx` already uses.
- **Applied the phase-35/36 "never resolve a display name against an
  active-only list" lesson proactively** - `DispenseHistory` fetches a
  second, unfiltered `useMedications(true)` purely for its own name
  -resolution map, kept separate from the dispense form's own
  active-only picker list, since a historical `DispenseRecord` could
  reference a since-deactivated medication.
- **A real, documented reachability limitation, not fixed here**: a
  prescription drops off `GET /api/pharmacy/queue` once fully
  dispensed (`findPendingDispense`'s own phase-20 behavior), so its
  dispense-history/billing panel becomes unreachable through this UI
  at that point - no existing "all dispense records for this tenant"
  endpoint exists to build a standalone page against instead, and
  adding one is out of scope for a gap-closing pass over an
  already-built backend.
- **No new backend tests** - zero backend changes; every endpoint this
  drives was already covered by phase 31's own integration tests.
  Frontend: `npm run build` clean, `npm test` (36/36) unaffected - the
  `extraRoles` prop addition is backward-compatible and neither
  `PaymentsPanel`/`InvoicePanel` has existing Vitest coverage to
  regress (confirmed against frontend phase S's own tested-file list).
- **Live-verified against the real running stack, through `node-bff`
  directly (`:3000`)** - `docker compose up -d --build --force-recreate
  node-bff` (no `spring-boot-api` rebuild needed - frontend-only
  change), twice (once for the initial build, once again after the
  refund-gate fix above), both confirmed healthy; session state
  survived both recreates unaffected since Redis itself was never
  recreated. As a real `demo-pharmacist` login: expanded a real, pre
  -existing queue row's dispense history and confirmed two real
  records rendered with correctly resolved medication names
  ("Amoxicillin 500mg", "Amoxicillin"); recorded a genuine $15.00 cash
  payment against one, confirmed gateway-routed
  (`gatewayStatus: "succeeded"`) via a direct fetch; generated a real
  invoice and downloaded the real PDF, confirming the `%PDF` header
  (subtotal/tax/total all correctly $0.00, matching this particular
  test medication's own unset `unitPrice` - real data, not a UI bug).
  Confirmed `demo-clinic-admin` reaches the identical flow via its own
  pharmacist-dashboard override access, including the "Refund" link
  the pharmacist correctly doesn't see. Exercised the whole flow once
  in Dark theme + Amharic together (the queue row, dispense-history
  toggle, and both billing panels - "ክፍያዎች"/"ደረሰኝ"/"ገ.ዲ.ኤፍ
  አውርድ"/"ንኡስ ድምር"/"ግብር"/"ጠቅላላ ድምር" - all confirmed correctly
  translated and themed via a real screenshot).
- **`nginx` (`:80`) started cleanly on the first retry this phase** -
  unlike phases 34-36's own repeated Docker Desktop port-allocation
  desync, `docker compose up -d nginx` this time reported the
  container genuinely `Up` with no immediate exit. Not chased further
  (verification still ran through `node-bff` directly, per this
  phase's own plan) - noted here since it may mean the standing issue
  has cleared on its own; worth a real `http://localhost/` check next
  time this comes up rather than assuming it's still broken.

## Phase 38: controlled substance tracking UI

Fifth of the six frontend gap-closing phases above. Closes
`com.clinicops.pharmacy.ControlledSubstanceDispenseController` (phase
28) - the dual-sign-off workflow for a controlled-substance dispense -
which had a fully-built, fully-tested backend but zero frontend, plus
`MedicationController.updateControlledSubstanceSchedule` (also phase
28, the prerequisite step that had no UI path at all - no medication
could be marked controlled without a direct `curl`). Zero backend
changes.

- **`pages/pharmacist/Medications.jsx`** gained a `clinic_admin`-only
  schedule control (`hasRole('clinic_admin')`, a new `useAuth` import
  this file didn't previously need) - a plain `<select>` next to the
  existing Edit/Deactivate links, calling the new schedule-update hook
  directly on change (no separate save button, matching this app's
  "dedicated action endpoint, not a form field" precedent
  `Asset.assignedRoomId`/phase 30 already set). The table's own `name`
  column gained a small warning-colored badge ("Schedule II" etc.)
  next to any medication with a schedule set, visible to every role
  that can already see the catalog - a pharmacist can see at a glance
  which rows require the dual-sign-off flow even without the
  clinic_admin-only control to change it.
- **`pages/pharmacist/ControlledSubstanceQueue.jsx`** (new) - mirrors
  `Dashboard.jsx`'s own `DispensePanel` request-form shape (medication
  → stock-batch cascading selects, quantity, notes) for the request
  side, plus status-filter tabs (pending/co-signed/rejected/all) and a
  `DataTable` with `renderExpanded` showing co-sign/reject actions -
  the same create-form-above-table shape phase 36's `DrugInteractions.jsx`
  already established.
  - **The request form needs a prescription picker no existing
    endpoint gives directly** - reuses `usePharmacyQueue(true)` (the
    same active-prescriptions list `Dashboard.jsx`'s own queue already
    fetches) purely for display context (patient + the prescription's
    own free-text `medicationName`); the actual `medicationId`
    submitted comes from a separate, controlled-substances-only
    medication `<select>` (`useMedications(true, 'active')` filtered
    client-side to `m.controlledSubstanceSchedule != null` - no
    server-side filter param exists for this and adding one was out of
    scope), identical to how `DispensePanel`'s own form already splits
    free-text prescription vs. picked catalog medication (phase 20's
    own pinned "no drug-name matching" design).
  - **An `acknowledgeConflict` checkbox was added to this new form,
    something the older `DispensePanel` never got** - a deliberate,
    narrow addition: the field already exists on this exact request's
    own DTO and the form was being built from scratch anyway, so
    wiring it through was low-cost; retrofitting the plain dispense
    form to add the same affordance was out of scope for this pass.
  - **The same-person 409 is not pre-empted client-side** - no
    reliable "is this me" comparison exists without an extra lookup,
    and the backend's own message already explains it clearly - the
    UI just surfaces it verbatim through `ErrorBanner`, same
    "don't paraphrase a backend conflict message" precedent every
    prior phase's error handling already uses.
- **`api/queries.js`** - `useUpdateControlledSubstanceSchedule`,
  `useControlledSubstanceRequests(enabled, status)`,
  `useRequestControlledSubstanceDispense`,
  `useCosignControlledSubstanceDispense`,
  `useRejectControlledSubstanceDispense` - a cosign additionally
  invalidates `['stock-batches']` (prefix match, same reasoning
  `useDispensePrescription`'s own invalidate already documents - a
  cosign decrements real stock) and `['dispense-records']` (a cosign
  produces a real `DispenseRecord`, so the phase-37 dispense-history/
  billing panel for that prescription should pick it up too -
  confirmed live below).
- **Routing/nav**: `/pharmacist/controlled-substances`,
  `RequireRole roles={['pharmacist', 'clinic_admin']}` - identical
  gate to every other pharmacist route. `layout/Sidebar.jsx` gained a
  link in both the `pharmacist` group and the `clinic_admin` group's
  own curated pharmacist links (alongside medications/drug
  -interactions), matching the existing "clinic_admin gets a UI path
  into every pharmacist-owned workflow" precedent.
- **A real, self-caught bug in this phase's own live-verification
  script, not the app** - an early reject-reason test used an
  unscoped `input` selector that matched the create-form's own Notes
  field instead of the reject mini-form's reason field, so the first
  reject attempt silently persisted `rejectionReason: null`. Caught by
  checking the actual persisted value rather than trusting the click
  succeeded; fixed by scoping the selector to `confirmBtn.closest('form')`
  and re-verified correctly with a fresh request.
- **A real, separate bug caught and fixed in this file (`CLAUDE.md`)
  itself while writing this phase's own write-up**: phase 37's prior
  edit had accidentally deleted the `## Phase 19: clinic-admin
  analytics dashboard` heading line (confirmed via `git diff` against
  the phase-36/phase-37 commits) - the section's own body text
  survived, just orphaned under phase 37's own heading. Restored here.
- **No new backend tests** - the endpoint set was already fully
  covered by phase 28's own `DispenseServiceTest`/
  `ControlledSubstanceDispenseControllerIntegrationTest`; this phase
  adds no new backend surface at all. Frontend: `npm run build` clean,
  `npm test` (36/36) unaffected.
- **Live-verified against the real running stack, through `node-bff`
  directly (`:3000`)** - `docker compose up -d --build --force-recreate
  node-bff` (no `spring-boot-api` rebuild needed). As a real
  `demo-clinic-admin` login: confirmed the pre-existing "Fentanyl
  (Phase 28 verification)" medication's own `schedule_ii` (set in an
  earlier session) renders as a "Schedule II" badge in the catalog
  table; used the new UI-driven `<select>` to mark a second medication
  ("Penicillin V") `schedule_iv` and confirmed the change persisted via
  a direct fetch. Confirmed `demo-pharmacist` sees the read-only badge
  but genuinely has no schedule-editing control at all (no matching
  `<select>` in the DOM). As `demo-pharmacist`: submitted a real
  controlled-substance dispense request (Fentanyl × 1 against a real
  "Demo Patient - Penicillin V" prescription, cascading medication
  →batch selects both confirmed working); attempted to co-sign it as
  the same login and got the real 409 - "A different pharmacist or
  clinic_admin must co-sign this request" - rendered through
  `ErrorBanner`. Logged out via the app's own `/auth/logout` form (a
  fresh Keycloak login form on the next attempt confirmed the SSO
  session genuinely ended) and back in as a genuinely different
  `demo-clinic-admin` login: successfully co-signed the same request -
  the response carried a real `dispenseRecordId`, cross-checked
  directly that `requestedBy` and `coSignedBy` were two different
  AppUser ids, and confirmed the stock batch dropped from 25 to
  exactly 24 (a single decrement). Confirmed the resulting
  `DispenseRecord` immediately appears in phase 37's own
  dispense-history panel on `pharmacist/Dashboard.jsx` for that same
  prescription, with `coSignedBy` correctly set. Created and rejected
  a second request with a real reason string ("Duplicate request -
  wrong prescription") - confirmed via a direct fetch the rejection
  reason persisted correctly and the stock batch stayed at exactly 24
  (a rejected request never touches stock). Exercised the whole page
  once in Dark theme + Amharic together (the request form, status
  tabs, and empty state all confirmed correctly translated/themed via
  a real screenshot).
- **Standing note, unchanged from phase 37**: `nginx` (`:80`) - not
  re-attempted this phase, verification stayed on `node-bff` directly
  throughout.

## Phase 39: patient prescriptions/refills UI

Sixth and last of the six frontend gap-closing phases above - closes
this whole set. Closes `com.clinicops.pharmacy.PatientPrescriptionController`
(phase 33), both sides: the patient-facing `GET /api/my-prescriptions`/
refill-request flow, and the staff review queue
(`GET/POST /api/pharmacy/refill-requests`).

- **A real, structural display gap found while researching this
  phase, the only one of the six that genuinely needed a backend
  change** - `PrescriptionRefillRequest` carries only raw
  `prescriptionId`/`patientId` UUIDs, and unlike every other gap in
  this set, no existing endpoint let `pharmacist` resolve either to a
  name (`PatientController` excludes `pharmacist` from its read gate,
  no `GET /api/prescriptions/{id}` exists anywhere, and
  `GET /api/pharmacy/queue` only covers prescriptions not yet fully
  dispensed - typically not the case for a refill request, since
  requesting one usually means the original supply just ran out).
  Closed with a new `RefillRequestQueueEntry` record (`com.clinicops.pharmacy`)
  embedding a resolved `patientName`/`medicationName` directly in
  `PatientPrescriptionController.refillRequests()`'s own response -
  same "embed what the caller needs, don't force a second lookup"
  convention this codebase already applies repeatedly, resolved inline
  in the controller (`patientRepository.findById`/
  `prescriptionRepository.findById`), same "controller composes
  repositories directly for simple read-only aggregation" precedent
  `DispenseController.queue()` already sets - no new service bean, no
  migration. `approve`/`deny` keep returning the plain
  `PrescriptionRefillRequest` unchanged.
- **`pages/patient/MyPrescriptions.jsx`** (new) - no separate detail
  page/route, mirroring the `DataTable`+`renderExpanded` shape this
  session already used twice for a single simple action
  (`DrugInteractions.jsx`, `ControlledSubstanceQueue.jsx`'s reject
  form) rather than `MyLabOrders.jsx`'s heavier list+dedicated
  -detail-page shape - a prescription has exactly one patient-facing
  action (request a refill), no staff-driven status machine to walk
  through on its own page. `renderExpanded` shows instructions, a
  refill-status badge sourced from the most recent
  `useMyRefillRequests()` entry for that prescription (the backend's
  own duplicate-pending guard means at most one can ever be
  `"requested"` at a time), and the request form itself - hidden while
  a request is genuinely pending, surfacing the real backend 400/409
  messages verbatim through `ErrorBanner` when they occur.
- **`pages/pharmacist/RefillRequests.jsx`** (new) - the same status
  -filter-tabs + `DataTable` + `renderExpanded` shape
  `ControlledSubstanceQueue.jsx` just established, directly reused.
  Approve is a single click (no confirm, matching this app's existing
  convention); Deny expands a reason `<input>` + confirm button, same
  shape `ControlledSubstanceQueue.jsx`'s own reject form and
  `Medications.jsx`'s write-off form already use. Deliberately doesn't
  try to resolve `reviewedBy` to a name, matching phase 38's own
  precedent for the same class of field.
- **`api/queries.js`** - `useMyPrescriptions`, `useMyRefillRequests`,
  `useCreateRefillRequest` (invalidates both patient-side lists),
  `useRefillRequests(enabled, status)` (mirrors
  `useControlledSubstanceRequests`'s exact shape),
  `useApproveRefillRequest`/`useDenyRefillRequest`.
- **Routing/nav**: `/my-prescriptions`,
  `<RequireRole role="patient">` (the same singular-`role` wrapper
  shape `/my-lab-orders` already uses, not the staff routes' `roles`
  array); `/pharmacist/refill-requests`,
  `<RequireRole roles={['pharmacist', 'clinic_admin']}>`. New links in
  the `patient` sidebar group and both the `pharmacist` group and its
  `clinic_admin` curated-links counterpart, matching every prior
  phase's own precedent.
- **Scope boundary, deliberate**: `patient/Dashboard.jsx` is **not**
  touched - no new stat card or "top 5" preview panel there, unlike
  `MyLabOrders`' own dashboard integration. A later call if the user
  wants full parity with the lab-orders dashboard treatment, not
  assumed here.
- **Tests**: one new `PatientPrescriptionControllerIntegrationTest`
  case (`staffQueueEmbedsTheResolvedPatientAndMedicationNames`)
  confirming the embedded fields resolve correctly for a real seeded
  request. Confirmed via a clean `mvn -q clean compile` and
  `mvn clean test-compile`, and a full `mvn test` run showing
  `Tests run: 432, Errors: 344` (up from 431/343 - exactly the one new
  test method), 88 pure-unit tests unaffected; confirmed via grepping
  every surefire report that all 344 failures hit the identical
  pre-existing `Could not find a valid Docker environment` wall (51 of
  67 report files), the other 16 all clean-passed. Frontend:
  `npm run build` clean, `npm test` (36/36) unaffected.
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate spring-boot-api
  node-bff` (this phase touches the backend, unlike 35-38), both
  confirmed healthy, no migration needed (no schema change), Hibernate
  `ddl-auto: validate` and Flyway both confirmed unaffected
  (`flyway_schema_history` still at version 28, "up to date, no
  migration necessary"). As a real `demo-patient` login: confirmed
  `/my-prescriptions` lists a real "Penicillin V" prescription with
  correct dispensed-so-far progress ("1 of 20 dispensed"); submitted a
  real refill request, confirmed the real 409 on an immediate second
  attempt ("A refill request for this prescription is already
  pending") rendering through the UI, and confirmed the pending badge
  correctly hides the request form while pending. As a real
  `demo-pharmacist` login: confirmed the request appears in
  `/pharmacist/refill-requests` with the correctly resolved real
  "Demo Patient"/"Penicillin V" names - the exact gap this phase's own
  backend change closes, not a raw UUID; approved it, confirmed a real
  email landed in Mailpit's own web UI (`:8025`) - "Your prescription
  refill is ready - Penicillin V" - matching phase 33's own original
  verification approach. Created and denied a second request with a
  real reason ("Provider wants to see patient first - phase 39"),
  confirmed via Mailpit that no new notification was written for the
  deny (still "1-2 of 2" messages), and confirmed the real denial
  reason rendered back correctly on the patient side
  ("Refill request: Denied - Provider wants to see patient first -
  phase 39"). Confirmed `demo-front-desk` and `demo-provider` both
  correctly 403 on both new endpoints (`/api/pharmacy/refill-requests`,
  `/api/my-prescriptions`) and are client-side redirected away from
  both new routes. Exercised both pages once in Dark theme + Amharic
  together, including the interpolated real denial-reason text
  rendering correctly translated (confirmed via real screenshots, not
  just code review).
- **A real browser-automation session-stability issue hit repeatedly
  this phase, not a product bug** - beyond the already-documented
  screenshot/`javascript_tool` timeout flakiness, several `POST`
  mutations this phase appeared to fail from the tool's own
  perspective (a reported timeout, or a subsequent read returning a
  `{"error":"Session expired"}`/401 body) while the mutation had
  actually already succeeded server-side by the time it was
  independently re-checked via a fresh authenticated `fetch` - worked
  around throughout by never trusting a reported tool failure at face
  value and instead re-querying the real backend state before
  concluding an action needed to be retried, the same "verify via a
  direct authenticated fetch, not the visual/tool result" discipline
  phases 35 onward already established for the unrelated screenshot
  -timeout flakiness.
- **Standing note, unchanged from phase 38**: `nginx` (`:80`) - not
  attempted this phase, verification stayed on `node-bff` directly
  throughout.

This closes the entire "Frontend gap-closing" set sketched
2026-09-29 - all six gaps (general inventory, drug-interaction pairs,
dispense billing, controlled substance tracking, and now patient
prescriptions/refills) are built and live-verified. Every role and
every backend API built across this whole project now has a real
frontend.

## Sidebar nav arrangement review (2026-10-01)

The user asked for an evaluation of the menu arrangement for every
role - a real review, not a new feature, prompted by the sidebar
having organically grown to 19 flat items for `clinic_admin` across
the six gap-closing phases above with no pass ever taken on the
*arrangement* itself (every one of those phases added a correct link
in a reasonable place, but none stepped back to look at the resulting
whole). Three real, concrete problems were found and fixed, all in
`layout/Sidebar.jsx`:

- **A real access gap, not just a cosmetic one**: `clinic_admin`'s own
  curated accountant links only ever exposed Accounts + Payroll, but
  every `/accountant/*` route already grants `clinic_admin` full
  backend access (confirmed in `App.jsx` - same `roles={['accountant',
  'clinic_admin']}` on all six routes, per frontend phase R's own
  design). Journal, Employees, and Budgets were reachable only by
  typing the URL directly - no link anywhere. Fixed by adding all
  three, matching the access the backend already grants. Live
  -confirmed: `demo-clinic-admin` clicking the new Journal link now
  loads real trial-balance/journal-entry data that was previously
  unreachable through the UI.
- **No grouping at all for what's functionally several departments
  stacked in one list** - `clinic_admin` (19 items) and `front_desk`
  (8 items, inventory outnumbering the role's own core 3 scheduling
  items with nothing to separate them) both read as one undifferentiated
  list. `navGroups()` already returned an array of group objects (one
  per role) but every role only ever pushed exactly one; the fix
  splits `clinic_admin` into five groups (unlabeled core/scheduling,
  "Pharmacy", "Finance", "Inventory", then a final unlabeled Settings
  group) and `front_desk` into two (unlabeled core, "Inventory") -
  `SidebarContent` renders an optional uppercase `group.heading` above
  a group's items when present and not collapsed (collapsed mode skips
  headings entirely, matching how item labels are already hidden
  there). New `sidebar.groupPharmacy`/`groupFinance`/`groupInventory`
  i18n keys - `"Finance"`, not "Accounting", to match the term this
  app already uses for this exact domain (`accountantPage.dashboardTitle`),
  not a new parallel name for the same thing.
- **Three adjacent, visually-identical `ClipboardIcon` uses** - Drug
  Interactions/Controlled Substances/Refill Requests rendered as three
  indistinguishable icons back to back in the collapsed icon-only
  rail, in both `clinic_admin`'s and `pharmacist`'s own nav (the same
  three items appear in both). Three new hand-authored stroke icons
  added to `components/icons.jsx` (`AlertTriangleIcon`, `ShieldIcon`,
  `RefreshIcon` - same `viewBox="0 0 24 24"`/`strokeWidth="1.75"`
  convention every existing icon in that file already uses, no icon
  library), applied to exactly those three items in both roles. Scoped
  deliberately to just this one proven collision, not a general icon
  -uniqueness pass across the whole sidebar (`ClipboardIcon`/`BoxIcon`/
  `FlaskIcon` are still reused elsewhere, just never adjacently).
- **Zero backend changes** - this was a pure frontend nav/IA fix, no
  new routes, no new Keycloak role, no `node-bff`/`PUBLIC_ROUTES`
  change.
- **Live-verified against the real running stack** -
  `docker compose up -d --build --force-recreate node-bff` (frontend
  -only change). As a real `demo-clinic-admin` login: confirmed the
  "PHARMACY"/"FINANCE"/"INVENTORY" headers render correctly between
  the right groups, confirmed the three new Finance links (Journal/
  Employees/Budgets) are present and that clicking Journal loads real
  data; collapsed the sidebar and visually confirmed (via a zoomed
  screenshot) the three previously-identical pharmacy icons now read
  as a warning triangle, a shield, and a refresh/cycle glyph, clearly
  distinct from each other and from every other icon in the rail. As a
  real `demo-front-desk` login: confirmed the "INVENTORY" header now
  visually separates its 5 inventory links from the role's own 3 core
  items. Exercised both roles' updated sidebars once in Dark theme +
  Amharic together (group headers, icons, and translated labels all
  confirmed correctly rendered via real screenshots) before resetting
  back to light/English.
- Frontend: `npm run build` clean, `npm test` (36/36) unaffected (no
  shared-component test surface touched - `Sidebar.jsx` itself has no
  existing Vitest coverage, matching frontend phase S's own "infrastructure
  + shared components" scope boundary, which never included page
  -chrome components like this one).

## Archived full frontend phase write-ups (moved from CLAUDE.md, 2026-10-03 size-reduction pass)

### Frontend phase P

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

### Frontend phase Q

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

### Frontend phase R

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

### Frontend phase S

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

### Frontend phase T

**Frontend phase T: UI for phases 24-26** (built 2026-09-29) - closes the
"backend only" gap the full-EHR-breadth backlog left open. Unlike most
frontend phases, this one needed almost no clarifying questions - nearly
every UI decision followed directly from an existing pattern (the plan
was written straight from reading the real files, not a round of
questions), so this write-up is mostly "which existing pattern got
reused where," not new design.

- **Immunizations** - a new sub-section in `components/PatientChart.jsx`,
  mirroring the file's own Allergies sub-section almost exactly (same
  accumulating-list shape, not the full-replace shape Vitals/Medical
  History use) - list + add-form, using the file's shared `inputClass`/
  `Field` helpers. Write gate is `provider`+`front_desk`+`clinic_admin`
  (Immunization's real backend gate, broader than Allergy's own
  front_desk+clinic_admin). `appointmentId` is never a form field - when
  `PatientChart` is mounted inside a specific appointment's own context
  (front-desk detail page, provider encounter page), the create payload
  sets it silently from the existing prop, so an immunization added from
  within a visit is automatically "given at this visit" - the whole point
  of the phase-26 schema fix, now actually reachable from the UI, not
  just curl. `ImmunizationRow` gets a small local edit-toggle (three
  inputs + Save/Cancel) since its editable fields (dose/lot/site) don't
  reduce to Allergy's own single-button "Mark resolved" pattern. New
  `useImmunizations`/`useCreateImmunization`/`useUpdateImmunization`
  hooks in `api/queries.js`, directly mirroring the Allergy hooks' shape.
- **Physical-exam findings** - extends `pages/provider/Encounter.jsx`
  inside the *same* note form as chief complaint/assessment/plan/ICD-10,
  not a separate section with its own save button - one upsert call
  already accepts arbitrary `Encounter` fields, so the 18 new fields just
  join the existing form-state object and payload, and lock for free
  (every input already gets `disabled={signed}` the same way the
  existing fields do - no new frontend locking logic needed, confirmed
  live: all 9 tri-state selects correctly disabled on an already-signed
  encounter). A `BODY_SYSTEMS` array (same 9-system order as the
  backend/PDF) is `.map()`'d to render one row per system - a 3-option
  `<select>` (unset/Normal/Abnormal, converting `''` ⇄ `null` the same
  way `PatientChart.jsx`'s own numeric Vitals fields already convert
  `''` ⇄ `Number(...)`) plus a `<textarea>` note.
- **Visit summary PDF** - new `components/VisitSummaryLink.jsx`, much
  simpler than `InvoicePanel` since there's no JSON resource to check
  first (the PDF is always generated fresh) - just an unconditionally
  -rendered same-origin `<a href target="_blank">`, the identical
  cookie-auth mechanism `InvoicePanel`'s own `pdfUrl` link already
  relies on. Mounted three places: `front-desk/AppointmentDetail.jsx`
  (next to the existing `InvoicePanel`), `provider/Encounter.jsx` (covers
  the `provider` role, which never reaches the front-desk page), and -
  **this app's first patient-facing document-download link** - the
  patient-facing `AppointmentDetail.jsx`, gated `authenticated &&
  hasRole('patient')` with deliberately no status gate, matching the
  backend's own "generatable anytime" design rather than inventing a
  client-side restriction it doesn't have.
- New locale keys added to both `en.json`/`am.json` in lockstep - most
  nested inside the existing `patientChart`/`encounterPage` namespaces
  (these are extensions of existing pages, not new ones), plus one new
  top-level `visitSummary` namespace (one key) following the
  `invoicePanel`/`paymentsPanel` precedent of "one small namespace per
  shared component." Confirmed exact key parity between both files after
  the edit (805 keys each side, the same two pre-existing intentional
  English-only pluralization keys as before - not a new gap).
- **Tests**: `VisitSummaryLink.test.jsx` (new, 2 cases) - consistent with
  Frontend phase S's own explicit scope decision ("infrastructure +
  shared components," not broad page-level coverage); the `PatientChart.jsx`/
  `Encounter.jsx` edits themselves stay outside that scope, same as every
  other extension to those already-large, already-excluded files. `npm
  test` showed `36/36` passing (up from 34), `npm run build` clean.
- **Live-verified against the real running stack** (`docker compose up
  -d --build --force-recreate node-bff` + `docker compose restart
  nginx`) - as `demo-provider`: opened a genuine guest-booking encounter
  (no `patientId`) and confirmed Allergies/Immunizations both gracefully
  absent while Vitals still rendered (the `{patientId && ...}` gate
  holding against a real guest, not just read from the code); opened a
  different, real-patient encounter and confirmed the existing Allergy/
  Immunization/Vitals/Consent data from earlier sessions' own live
  verification rendered correctly, including the exact `dosePrefix`/
  `lotPrefix`-formatted "Dose 1 · Lot LOT-VS · right arm" line; live
  -edited a Physical Exam field (Respiratory: Normal → Abnormal, with a
  new note) on an *unsigned* encounter and confirmed it persisted in
  Postgres, while the pre-existing Cardiovascular abnormal finding
  (recorded during phase 26's own backend verification) stayed
  untouched; edited an immunization's lot number inline and confirmed
  the update endpoint round-tripped; added a brand-new immunization from
  within an appointment's own chart and confirmed it auto-linked via
  `appointmentId` with no picker involved; confirmed the "Download visit
  summary" link renders with the correct `href` on all three host pages
  (front-desk detail, provider encounter, patient's own detail) and is
  absent for an unauthenticated/guest view of the patient page. Exercised
  the entire Physical Exam section and the new Immunizations list in
  Dark theme + Amharic together - all 9 body-system labels, the
  tri-state select options, and the interpolated dose/lot copy all
  rendered correctly, and the 9 exam-finding selects were confirmed
  genuinely `disabled` (not just styled to look disabled) on a real
  signed encounter.

This closes the "no frontend yet" gap the full-EHR-breadth backlog
(phases 24-26) left open - see "Known gaps" below, updated accordingly.

## Archived full phase write-ups (moved from CLAUDE.md, 2026-10-03, phase 40)

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

- **Self-contained tracker, not a real clearinghouse/EDI integration** - no
  real payer credentials exist in this dev environment, the identical
  "mock/defer the real vendor" call phases 16 (payment gateway) and 17
  (email) already made. A `Claim` bills one already-issued `Invoice`
  against one `InsurancePolicy` - a plain reference to the Invoice, **not**
  a new Invoice/Payment owner type, so neither table's own exactly-one
  -owner CHECK needed touching.
- **`com.clinicops.insurance.InsurancePolicy`** - patient-level, primary
  **and** secondary policies per patient (`rank`), for real coordination
  -of-benefits (a claim can bill a secondary payer for whatever the
  primary didn't cover). No delete endpoint - same "correct via a new row
  + a status flip" precedent `Allergy` already set, not a replacement for
  the pre-existing flat `patients.insurance_member_id` column (phase 1),
  which is left untouched.
- **`com.clinicops.insurance.Claim`** lifecycle, self-contained and
  manually staff-driven: `draft -> submitted -> (paid | partially_paid |
  denied) ->` optionally `appealed` (from `denied`, back into an
  adjudicatable state) `-> closed` (callable from any status, including
  abandoning a draft filed by mistake). Mirrors `LabOrderStatusService`'s
  own conventions - re-calling a transition already reached is idempotent,
  calling one out of order throws a new `InvalidClaimStatusException`
  (409). `submit` is the one deliberate exception: like `Specimen
  .sendToReferenceLab` (phase L5), a re-call while already submitted
  updates the claim number in place rather than no-op'ing, since a payer's
  own claim number often isn't known until after the first submission.
  `recordAdjudication` requires `denialReason` for a `denied` outcome, or
  all three amount fields (`allowedAmount`/`paidAmount`/
  `patientResponsibilityAmount`) otherwise - validated in the service, not
  via annotations, since which fields are required depends on the outcome.
- **Patient resolution reuses `DispenseService`'s own multi-hop chain** -
  `ClaimService.resolvePatientId` walks `Invoice`'s own three nullable
  owner columns (appointment/lab-order/dispense-record) back to a
  `patientId`, the dispense-record branch going through the identical
  `Prescription -> Encounter -> Appointment` hop `DispenseService` already
  established for the same reason (a `DispenseRecord` carries no
  `patientId` of its own). An invoice with no resolvable patient (a guest/
  walk-in booking) is rejected at claim-creation time (400) - insurance
  billing is structurally impossible without a known patient, a real,
  documented limitation, not an oversight. A policy that doesn't belong to
  the invoice's own resolved patient is also rejected (400).
- **Role gate: `front_desk` + `clinic_admin` only, no provider access at
  all** - the user's own pinned answer; insurance billing is a billing
  -team concern, not a clinical one, deliberately narrower than
  `AppointmentPaymentController`'s own provider-readable carve-out.
- **A genuine small gap found and closed while building**:
  `InvoiceRepository` had no `findByIdAndTenantId` at all before this
  phase (every existing lookup was by owner - `findByAppointmentIdAndTenantId`
  etc.) - a claim is the first thing in this codebase that ever needs to
  fetch an invoice by its own id, so the method was added.
- **Eligibility verification deliberately deferred** - the user's own
  pinned answer; a real check needs a real payer API this environment
  doesn't have, the identical reasoning SMS was deferred in phase 17.
- **Tests**: `ClaimServiceTest` (new, pure Mockito, genuinely runs locally
  - **15/15 passing**) - billed-amount snapshotting and patient resolution
  through all three invoice-owner branches (appointment/lab-order/
  dispense-record chain); rejected when the invoice has no resolvable
  patient or the policy belongs to a different patient; `submit`'s
  draft->submitted transition and its deliberate non-idempotent re-call
  (updates the claim number in place) and its rejection from an already
  -adjudicated status; `recordAdjudication`'s required-field validation
  for both `paid` and `denied` outcomes, its rejection from `draft`, and
  idempotent re-call once already adjudicated (`verify(claimRepository,
  never()).save(any())`); `appeal`'s denied->appealed transition and its
  own re-adjudication afterward; `close`'s callable-from-any-status/
  idempotent-once-closed behavior. `InsurancePolicyControllerIntegrationTest`
  (new, 5 cases: CRUD round-trip, invalid rank/relationship/status 400,
  role gate - `provider` forbidden on both read and write, cross-tenant
  404, update-for-the-wrong-patientId 404) and
  `ClaimControllerIntegrationTest` (new, 7 cases: the full draft-\>
  submitted-\>paid-\>closed lifecycle through the real endpoints, the
  denied-\>appealed-\>partially_paid re-adjudication path, invalid
  -outcome/missing-required-field 400s, the guest-invoice-with-no-patient
  400, the mismatched-policy 400, the role gate, cross-tenant 404 on both
  the invoice and the claim). One new `TenantIsolationIntegrationTest`
  case (`insurancePoliciesAndClaimsAreNotReadableOrWritableFromAnotherTenant`).
  Confirmed via a clean `mvn clean test-compile` and a full `mvn test` run
  showing `Tests run: 514, Errors: 381, Failures: 0` (up from 486/368 -
  exactly the 28 new test methods: 15 pure-unit + 13 Testcontainers
  -blocked), 133 pure-unit tests now passing project-wide (up from 118);
  confirmed via computing pass/fail per surefire report that every one of
  the 13 new Testcontainers-blocked failures hits the identical
  pre-existing `Could not find a valid Docker environment` wall every
  other integration test on this machine already hits - not a new failure
  mode.
- **Live-verified against the real running stack, through `node-bff`'s
  real browser-facing proxy on `:3000`** - Docker Desktop was found not
  running at the start of this phase (`docker compose ps` failed to reach
  the daemon); started it, confirmed the whole stack came up healthy, and
  rebuilt `spring-boot-api` - `V35` confirmed applied via
  `flyway_schema_history` (version 35, description "insurance claims",
  `success = t`). Testcontainers itself still hits the identical
  pre-existing Windows npipe wall even with the daemon demonstrably
  reachable via plain `docker compose` commands (confirmed by re-running
  `InsurancePolicyControllerIntegrationTest` locally - same
  `Could not find a valid Docker environment` failure) - not re-chased
  further, a permanent, already-documented limitation of this dev
  machine, not something to debug mid-phase.
  - **No browser extension was connected this session either**, so full
    login was driven by scripting the real Authorization Code + PKCE flow
    directly against Keycloak with `curl` (a two-step "identifier first"
    login form - username, then a separate password step) - genuinely
    exercising the same flow a real browser does, not a shortcut. As a
    real `demo-front-desk` login (`GET /auth/me` confirmed the correct
    identity/role/org): found a real "Demo Patient" and one of their real
    appointments, generated a real $50.00 invoice for it
    (`POST /api/appointments/{id}/invoice`), created a real
    `InsurancePolicy` ("Acme Health Insurance", primary), and filed a
    real `Claim` against the invoice - `billedAmount: 50.00`, patient
    correctly resolved through the appointment chain. Walked it through
    the full lifecycle for real: `submit` (`CLM-LIVE-1`) -\> **re-submit
    while already submitted, confirming the deliberate non-idempotent
    correction precedent live** - `claimNumber` updated to
    `CLM-LIVE-1-CORRECTED` with `submittedAt` genuinely unchanged -\>
    `record-adjudication` (`paid`, allowed/paid $40, patient
    responsibility $10) -\> `close`. Confirmed the final state via a
    fresh `GET /api/claims/{id}` and via both list endpoints
    (`GET /api/invoices/{id}/claims`, `GET /api/patients/{id}/claims`) -
    all three returned the identical, correctly persisted row.
  - As a real, separately-logged-in `demo-provider` session (confirmed via
    `GET /auth/me`): a malformed-body `POST` to create a claim 400'd
    first (request validation runs before `@PreAuthorize`, confirmed by
    re-testing with a well-formed body) - with a valid body, got a
    genuine `403`, and both `GET` endpoints also 403'd - the pinned
    `front_desk`+`clinic_admin`-only, no-provider-access gate holding
    live, not just documented. Cross-tenant isolation itself was not
    re-exercised with a second live login this session (no second
    tenant's staff credentials were on hand) - that invariant is covered
    by the new `TenantIsolationIntegrationTest` case instead, the same
    bar several smaller phases this project has already used when a
    second live tenant login wasn't readily available.
  - **A real lesson learned mid-verification, not a product bug**: the
    first cross-tenant-login attempt (as `demo-provider`) failed with
    "Invalid or expired login attempt" - caused by the verification
    script itself dropping the `-b`/`-c` cookie-jar flags on the final
    OIDC callback request, so node-bff's own pre-login session (which
    holds the PKCE `code_verifier`/`state` server-side) never reached it.
    Fixed by keeping the jar on every request in the chain, including the
    callback - the identical "every hop in a multi-redirect flow needs
    the same cookie jar" discipline a real browser handles invisibly.
- **No frontend yet** - backend only, same "backend first" scope boundary
  every new module in this project starts with (phase 7, phase 20, L1).
  A `pages/front-desk/` or `pages/clinic-admin/` insurance/claims UI is a
  natural next frontend phase, not built here.

## Frontend phase U: insurance & claims UI

Built 2026-10-03, same session as phase 40's backend - closes its own
"no frontend yet" gap, picked as the user's own explicit next-step choice
over three other candidate modules (telemedicine, imaging/radiology
orders, patient engagement) offered directly. Zero backend changes -
every endpoint this drives already existed and was already
live-verified server-side in phase 40 itself.

- **`InsuranceSection` (new, inside `PatientChart.jsx`)** - patient-level
  coverage records, list + create form + per-row edit/status-toggle,
  mirroring `AllergiesSection`'s own shape (no delete, correct a mistaken
  entry with a new row + a status flip). **The one genuinely new pattern
  this phase introduces to `PatientChart.jsx`**: every other section in
  that file is visible (read-only at minimum) to all three staff roles
  that can reach either host page, but `InsurancePolicyController` grants
  `provider` zero access at all - not even read. Gated at the **mount
  point** in the parent `PatientChart` component itself
  (`{patientId && canSeeInsurance && <InsuranceSection ... />}`), so a
  provider session never even fires the query, rather than the section
  rendering and hiding only its write form the way Allergies/Consent do.
- **`ClaimsPanel.jsx` (new, standalone component)** - mounted next to
  `InvoicePanel`/`PaymentsPanel` on both `front-desk/AppointmentDetail.jsx`
  and `lab-orders/LabOrderDetail.jsx`, only once an invoice actually
  exists and the owner has a known patient (`invoiceQuery.data &&
  appointment.patientId` / `order.patientId`) - a guest/walk-in invoice
  has no patient to bill insurance for at all
  (`ClaimService.createClaim`'s own 400), so the host page simply skips
  mounting this rather than the component rendering a permanent error.
  Each `ClaimRow` drives its own lifecycle actions (submit/record
  -adjudication/appeal/close) through one `action` state - at most one
  inline form open at a time, the same "click a link, a small form
  appears below" shape `PaymentRow`'s own refund affordance already
  established. `submit` is available from both `draft` and `submitted`
  statuses (re-submitting while already submitted corrects the claim
  number in place, matching `ClaimService.submit`'s own deliberate
  non-idempotent re-call - the same precedent `Specimen
  .sendToReferenceLab`'s UI, phase L8, already carried through to its own
  "re-opens pre-filled for correcting the details" form).
- **Deliberately not wired into the dispense-billing panel**
  (`pharmacist/Dashboard.jsx`'s `DispenseBillingPanel`, phase 37) - a
  pharmacy dispense isn't a typical insurance-claim scenario in practice,
  and the user's own scoping answer for phase 40 pinned
  `front_desk`+`clinic_admin` as the only roles managing claims, not
  `pharmacist` - extending there would need the exact same component at
  one more mount point for a use case that doesn't obviously need it.
  Revisit if a real need turns up, same as every other "explicitly
  skipped this pass" note in this file.
- **`StatusPill.jsx` extended** with the full claim-status vocabulary
  (`draft`/`submitted`/`paid`/`partially_paid`/`denied`/`appealed`/
  `closed`) - already functional via the component's own raw-string
  fallback before this, just unstyled, same "extend the shared style map"
  precedent lab module L8 already used for the specimen vocabulary.
- **i18n**: ~20 new `patientChart.*` keys (insurance section labels/
  fields/errors) and a new `claimsPanel.*` namespace (~32 keys) plus 7
  new `status.*` keys, added to `en.json`/`am.json` together - confirmed
  exact key parity via a flatten-and-diff Python script afterward (1128
  keys each side, the same two pre-existing intentional English-only
  pluralization keys as every prior phase - not a new gap). A real
  mid-edit mistake was caught before it shipped: a manually-typed Unicode
  escape for the Amharic "appealed" status accidentally included stray
  Cyrillic characters from a copy-paste slip - caught by writing the
  literal Amharic text directly instead of hand-rolled escapes, then
  re-verifying the saved file's actual bytes rather than trusting the
  script had run correctly.
- **Tests**: `StatusPill.test.jsx` gained one new case (a known
  insurance-claim status renders its real translated label) - consistent
  with frontend phase S's own scope boundary (shared components only,
  not page-level `ClaimsPanel.jsx`/`InsuranceSection` itself). `npm run
  build` clean; `npm test` showed `37/37` passing (up from 36 - exactly
  the one new `StatusPill` case).
- **Live-verified against the real running stack, through `node-bff`
  directly (`:3000`)** - `docker compose up -d --build --force-recreate
  node-bff` (healthy). No browser extension was connected this session
  (same standing gap several lab-module phases already flagged), so
  verification stayed at this project's own established "build-only"
  bar, strengthened one step further: confirmed the served JS bundle
  itself genuinely contains the new code (`grep`'d the built bundle for
  `insuranceSection`/`File claim`/`recordAdjudication` - all present,
  not just assumed from a clean build), and confirmed `GET
  /api/invoices/{id}/claims`/`GET /api/patients/{id}/insurance-policies`
  - called through the real node-bff proxy, the identical path the new
  React hooks use - return the exact real claim/policy rows phase 40's
  own backend verification created, in the exact field shapes
  (`payerName`/`memberId`/`rank`/`status`/`billedAmount`/`allowedAmount`/
  etc.) these new components expect. The original session's login cookie
  had expired (tokens, not the Express session itself, per
  `refreshIfExpired`'s own invalid_grant handling) - re-ran the same
  scripted Authorization Code + PKCE login from the phase-40 session (see
  the `scripted-curl-oidc-login` memory) to get a fresh one. **A real
  click-through of the new UI itself is still owed** - the exact same gap
  phase 40's own backend had, not newly introduced here.

