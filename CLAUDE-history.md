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
