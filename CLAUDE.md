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
- **postgres / redis** - primary datastore, and (once phase 2 lands)
  appointment-slot locking + session store.
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
- **Marketplace-style exception** (planned, phase 2): the
  appointment-availability search (a patient browsing open slots across
  clinics) will be intentionally cross-tenant and have no tenant filter;
  staff-facing calendar management will use the tenant-scoped finders. Keep
  the two clearly separated once built.
- **`TenantIsolationIntegrationTest`** (planned, grows with each phase): for
  every staff-scoped resource, clinic A seeds it and clinic B's staff is
  refused (404/403) on every read/write/action path, plus the deactivation
  lockout. `ClinicControllerIntegrationTest` is the phase-1 seed of this
  idea, scoped to the one endpoint that exists so far.

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

## Phase plan

1. **Infra + auth skeleton** (this session, 2026-09-12) - repo scaffolding,
   Keycloak realm, nginx, docker-compose, the Spring Boot tenancy/security
   skeleton, the BFF's OIDC/session/proxy skeleton, a minimal frontend, CI.
   See "Verified this session" below.
2. **Patient booking flow** - appointment types, provider working hours ->
   slot generation, `SlotLockService` + a split-bean `AppointmentWriter`,
   both booking channels (`patient_portal`/`front_desk`), idempotency, the
   public appointment-tracking endpoint, optional guest booking.
3. **Front-desk/counter + check-in** - front-desk search/book/
   reschedule/cancel, the check-in state machine
   (`booked -> checked_in -> roomed -> with_provider -> checked_out`,
   `no_show`/`cancelled`), fee-tier calculation on cancel, cross-tenant 403
   coverage.
4. **Provider clinical flow** - today's schedule, encounter documentation,
   prescriptions.
5. **Clinic-admin config** - providers/rooms/appointment-types/fee-policy
   CRUD, `ClinicSettingsService.resolve`, the branding endpoint, the
   settings/branding frontend hub.
6. **Platform-admin onboarding** - Keycloak Organization creation via
   `RestClient` (not the `keycloak-admin-client` library - see the kickoff
   spec's reasoning) + local `clinics` insert, deactivate/reactivate.
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

## Known gaps (don't pretend these are done)

- No patient booking flow yet (appointment types, slots, the booking
  channels, idempotency, public tracking, guest booking) - phase 2.
- No front-desk/check-in, provider clinical, clinic-admin config, or
  platform-admin onboarding flows yet - phases 3-6.
- No PHI-access audit log in v1 (deferred by decision, 2026-09-12).
- No lab-orders module yet - scoped as its own later session.
- `payments`/`invoices` tables exist in `V1__init.sql` but there's no code
  reading/writing them yet.
- Email/notifications: the `notifications` outbox table exists in
  `V1__init.sql`; no `NotificationWorker`/`LoggingEmailSender` yet.
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
