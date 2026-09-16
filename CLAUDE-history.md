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

