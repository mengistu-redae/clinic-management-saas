# Coding Conventions

How code in this codebase is actually written, so new code matches. This is
a reference for an engineer who already knows the stack (Spring Boot 3.3 /
Java 21, React / Vite / Tailwind) and needs to know *this project's* own
real conventions — not a tutorial on either framework. Every convention
below is current, repeatedly-reconfirmed practice (often re-validated
multiple times across the project's ~39 build phases), not an aspiration.

See also: [`system-design.md`](./system-design.md) for the "what/why" at
the architecture level, [`component-architecture.md`](./component-architecture.md)
for the package/component inventory this document assumes.

---

## Backend (`spring-boot-api`, Java 21 / Spring Boot 3.3)

### Package-per-domain

Every feature area is its own top-level package under `com.clinicops.<domain>`
(`appointment`, `patient`, `encounter`, `pharmacy`, `inventory`, `accounting`,
`finance`, `platform`, `phiaudit`, …) — entity, repository, controller,
and any service/writer beans for that domain all live together. There is
no `com.clinicops.controller`/`com.clinicops.service`/`com.clinicops.repository`
horizontal split anywhere in this codebase.

Every tenant-scoped entity extends `com.clinicops.common.BaseTenantEntity`:

```java
@MappedSuperclass
public abstract class BaseTenantEntity {
    @Id @GeneratedValue private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
```

Even a plain line-item table (`JournalLine`, `PurchaseOrderLine`) gets its
own `tenant_id` this way rather than relying only on a join back to its
parent — a deliberate redundancy, not an oversight.

### Tenancy enforcement — explicit, never implicit

`com.clinicops.tenant.TenantContext` holds the current request's tenant id
in a `ThreadLocal`, set once per request by `TenantContextFilter` (which
also doubles as the clinic-deactivation enforcement point — a `403` if the
resolved clinic's `status != "active"`). It is `null` for patient tokens
(no Organization membership) and platform_admin tokens (cross-tenant by
design).

**There is deliberately no blanket Hibernate multi-tenant filter.** The
project's own javadoc on `TenantContext` states the reasoning directly:

> Staff-scoped repository methods should take the tenant id explicitly as
> a parameter (e.g. `findByTenantIdAndId(...)`) rather than reading this
> implicitly deep inside a query, so it's always obvious from the method
> signature whether an endpoint is tenant-scoped or cross-tenant.

So every staff-scoped repository method signature carries `tenantId`
explicitly — `findByTenantIdAndId(tenantId, id)`, `findAllByTenantId(tenantId)`
— never a shared base-repository method that reads `TenantContext` behind
the caller's back. A handler that genuinely needs a tenant calls
`TenantContext.require()`, which throws rather than silently proceeding
with `null` — making a missing-tenant bug a loud `IllegalStateException`,
not a quiet full-table scan.

### `@PreAuthorize` on every endpoint

Method security is explicitly enabled (`@EnableMethodSecurity` on
`SecurityConfig` — Spring Security 6 does **not** turn this on by default)
and every `/api/**` method carries its own `@PreAuthorize("hasRole(...)")`
or `@PreAuthorize("hasAnyRole(...)")`. The only endpoints without one are
the deliberately-public paths listed in `SecurityConfig`'s own
`.permitAll()` block (health check, the patient-portal/guest booking
surface, the two tracking lookups) — every one of those is also mirrored
in `node-bff`'s own `PUBLIC_ROUTES` list, since a route being public at
the Spring Security layer means nothing if the BFF still gates it behind
a session.

### Controller → repository directly, unless there's a real reason not to

The default shape for plain CRUD is the controller calling the repository
directly — **no dedicated service bean for a resource that's just
create/read/update/deactivate.** A service bean only gets introduced for
one of three genuine reasons, and this has been re-confirmed deliberately
at multiple later phases rather than drifting:

1. **Cross-bean `@Transactional` self-invocation** — calling a
   `@Transactional` method on `this` from inside the same class silently
   skips the proxy, so a write that must be its own transaction (and
   sometimes retried/locked) lives in a *separate* bean. See
   `AppointmentWriter` (own file, own class, called by `AppointmentService`):

   ```java
   /**
    * The actual DB write, in a separate @Transactional bean from
    * AppointmentService so the transaction goes through Spring's proxy
    * correctly - calling a @Transactional method on `this` from inside
    * the same class silently skips it.
    */
   @Service
   public class AppointmentWriter { … }
   ```

   The same split-bean shape repeats for `AppUserWriter`, `PatientWriter`.

2. **Lock/race handling** — `SlotLockService` wraps a short-lived,
   per-slot Redis `SETNX`+TTL lock (so a patient in the portal and
   front-desk staff at the counter can never both confirm the same slot),
   with a Lua release script that only deletes a lock the caller's own
   request token actually owns.

3. **A genuine cross-cutting dependency** other code needs — e.g.
   `ClinicSettingsService.resolve(tenantId)` is the one real merge point
   for tax/fee/notice-hour platform defaults vs. tenant overrides, used by
   multiple unrelated callers (`RescheduleService`, invoice generation).

Outside those three cases, a new resource's controller should call its
repository directly — confirmed as the deliberate default by re-reading
the reference project's own precedent at several later phases, not
assumed once and left unchecked.

### Exceptions

- A plain 400/404/409 with no recurring shape: throw `ResponseStatusException`
  directly at the call site.
- A dedicated exception type + a controller-level `@ExceptionHandler` only
  once the *same* "wrong state" shape recurs across multiple call sites —
  e.g. `InvalidAppointmentStatusException` (409, reused across check-in/
  encounter-documentation/vitals gates). A new, structurally *different*
  lock concept gets its *own* type rather than reusing an existing one
  even if the HTTP status matches: `EncounterLockedException` is deliberately
  separate from `InvalidAppointmentStatusException`, because an encounter's
  sign-lock and an appointment's own status are two independent state
  machines that can disagree (a `with_provider` appointment can have an
  already-signed, locked encounter).

### Request/response DTOs

- `CreateXRequest`/`UpdateXRequest` as plain Java records with Jakarta
  Bean Validation annotations:

  ```java
  public record CreateAppointmentRequest(
          @NotNull UUID slotId,
          @NotNull UUID providerId,
          @NotNull UUID appointmentTypeId,
          …
  ) {}
  ```

- Partial-update semantics on every `UpdateXRequest`: a `null` field means
  "leave this unchanged," never "clear it" — a full-replace resource
  (prescriptions on an encounter, medical history) is the deliberate
  exception and is documented as such at its own call site, not left
  ambiguous.
- **Embed what the caller needs directly in the response, don't force a
  second client-side lookup.** This is a recurring, deliberate pattern —
  e.g. `PrescriptionQueueEntry` embeds a resolved `patientName`/
  `medicationName` directly (a pharmacist's queue view has no business
  needing read access to the whole patient-search endpoint just to show a
  name), and the same shape repeats for `RefillRequestQueueEntry`. When
  you're tempted to make the frontend fetch a second resource just to
  resolve an id to a display string, check whether the view-record should
  just carry that field instead.

### CRUD endpoint shape

`GET` (list) / `GET {id}` (one) / `POST` (create) / `POST {id}/update`
(partial update) — **there is no `PUT`, `PATCH`, or bare `DELETE` verb
anywhere in this API.** A resource with a well-defined "missing = safe
default" fallback gets a real hard delete, but still as `POST {id}/delete`
(see `FeePolicyController`):

```java
@PostMapping("/api/fee-policies/{id}/delete")
@PreAuthorize("hasRole('CLINIC_ADMIN')")
public FeePolicy deleteFeePolicy(@PathVariable UUID id) {
    …
    feePolicyRepository.delete(policy);
}
```

`FeePolicy`/`LabRate`/`DrugInteractionPair` are the real hard-deletes in
this codebase — all three are configuration with a well-defined "missing
= no override / zero" meaning. Everything else soft-deactivates via a
`status` column, with dedicated `POST {id}/deactivate`/`POST {id}/reactivate`
action endpoints for a *consequential* state transition (locking out an
entire clinic's staff, say) rather than folding that into the generic
`update` endpoint — a deactivation reads as a deliberate action in the
API surface, not an incidental field edit.

### Schema changes — Flyway only

Every schema change is a new `V<n>__description.sql` file under
`src/main/resources/db/migration/` (29 migrations as of this writing — see
[`data-model.md`](./data-model.md)). `ddl-auto: validate` in
`application.yml` means Hibernate checks the schema matches the entities
at startup and fails loudly if not — the application never auto-migrates
its own schema.

### Testing

- `AbstractIntegrationTest` — a Testcontainers-backed base class (Postgres
  16 + Redis 7, started once per JVM fork via `@Testcontainers`/`@Container`,
  never explicitly stopped — deliberate, not a leak). `MockMvc` runs through
  the *real* filter chain (Spring Security, `TenantContextFilter`,
  `@PreAuthorize` all genuinely execute), with JWTs faked via Spring
  Security Test's `jwt()` post-processor — a real `Jwt` object is built and
  its authorities derived through the app's own real `JwtAuthenticationConverter`
  bean, not a parallel stub. Convenience methods like `asFrontDesk(subject,
  orgAlias)`/`asClinicAdmin(...)` build the right fake JWT shape per role.
  **This suite cannot run on Windows** (`Could not find a valid Docker
  environment`, a documented Testcontainers/npipe incompatibility) —
  confirmed working only in the project's GitHub Actions CI
  (`ubuntu-latest`); never assume a failure on a Windows dev machine means
  a real regression.
- Pure-unit Mockito tests (no Spring context, run anywhere including
  Windows) for calculation-heavy logic that doesn't need a real database —
  `FeeCalculatorTest`, `DispenseServiceTest`, `JournalServiceTest`,
  `PayrollServiceTest`. When a new piece of logic is genuinely isolable
  this way, it gets its own pure-unit test class rather than only
  Testcontainers coverage, specifically so it's actually runnable on every
  contributor's machine.

### PHI access audit

`com.clinicops.phiaudit.PhiAccessAuditService` logs staff access to
patient/encounter/prescription/lab-order data via **explicit call sites**,
not an AOP aspect or annotation — the same reasoning the project applies
elsewhere: visibility at the call site over implicit cross-cutting magic.
Scoped to staff-initiated access only (a patient viewing their own record
is never audited), and it's not the same DB transaction as the domain
read/write it accounts for.

---

## Frontend (`node-bff/frontend`, React + Vite + Tailwind)

### The shared component layer

Reach for these before hand-rolling the equivalent markup — every one of
them exists because the same pattern was found duplicated across many
pages at some point in this project's history:

| Component | Use it for |
|---|---|
| `DataTable` | Any list with search/sort. `searchAccessors` (array of `(row) => string`) feeds the search box; `renderExpanded(row)` turns a row into an inline edit/detail panel instead of navigating away. Handles loading/error/empty states internally (`Skeleton`/`ErrorBanner`/`EmptyState`). |
| `Field` + `inputClass` (`components/Field.jsx`) | The label+input wrapper and input className string — extracted this session from **13+ files** that had each independently redefined an identical local copy. Always import this rather than redeclaring it locally. |
| `Button` | Every button — `variant` (`primary`/`accent`/`secondary`/`ghost`/`danger`) and `size` (`sm`/`md`) props. Never hand-roll a button's Tailwind classes; a hand-rolled button is how a real bug shipped once (a Save button silently missing `disabled:cursor-not-allowed` that every sibling button had, simply because it wasn't using this component). |
| `Card` | The `rounded-xl border border-slate-200 bg-surface p-4` wrapper, with an `as`/`hover` prop for a clickable card (`<Card as={Link} hover>`). |
| `PageContainer` / `PageHeader` | Page width + the title/description/actions row every list/detail page repeats. |
| `StatusPill` | Status → color mapping across every status vocabulary in the app (appointment, lab-order, generic active/inactive, …) — falls back gracefully (raw value, underscore-split) for a status it doesn't know about yet, so a new backend status never silently renders blank. |
| `TabGroup` | Status-tab filtering (pending/cosigned/rejected/all, say) — created this session specifically to de-duplicate 3 near-identical hand-rolled implementations that had drifted in accessibility completeness (2 of 3 were missing an `aria-label`). |
| `ErrorBanner` / `EmptyState` / `Skeleton` | The three states every data-fetching page must actually handle — a page with none of these for some query is a real, flaggable gap, not a style nit. |

### Data fetching — React Query only

`api/queries.js` is the **one** data-fetching layer — no Redux, no other
global state manager anywhere in this app. One `useX`/`useCreateX`/
`useUpdateX` hook per resource, each a thin wrapper over `useQuery`/
`useMutation`.

**Cache-invalidation discipline**: a mutation must invalidate every query
key its write could affect, not just the "obvious" one. A real bug shipped
this way and was fixed live this session: `Budgets.jsx`'s create/update/
delete mutations only invalidated `['clinic', 'budgets']`, never
`['clinic', 'budget-vs-actual']` — so the budget-vs-actual table kept
showing a stale value after a budget was created, in the very same render
pass. When adding a mutation, ask "what else on screen could this number
have changed?", not just "what did I directly write."

### i18n

Every user-facing string goes through `t()` — `en.json`/`am.json` are kept
in exact key-parity, confirmed by a flatten-and-diff check after any
addition (two intentional English-only pluralization keys are the sole
accepted exception: `fdAppointmentDetail.cancelSeriesSuccess_one`,
`myLabOrders.testCount_one` — Amharic needs no singular/plural split for
those). **Reuse an existing key/namespace before adding a new one** — this
project leans hard on cross-namespace reuse (`status.*` covers every
status vocabulary in the app; `common.all`/`common.search`/
`common.filterByStatus` are shared across dozens of pages) rather than
letting near-duplicate strings accumulate. Check for an existing key
first; only add a new one when nothing genuinely fits.

### Theme tokens — never a hardcoded color

Always the semantic tokens, never a raw Tailwind color:

- `bg-surface` (never `bg-white`)
- `text-ink` / `text-ink-muted` (never `text-gray-900`/`text-gray-500`)
- `border-slate-200` (border is the one token family that does use a raw
  Tailwind slate scale directly, by design)
- `bg-brand` / `bg-accent` / `bg-danger` / `bg-warning` / `bg-success`,
  each with a `-light` variant for a subtle background treatment

These are CSS custom properties with separate light/dark values, so using
the token is what makes dark mode work automatically with zero per-page
changes. A hardcoded color is a real, flaggable bug, not a style
preference — one shipped and sat undiscovered on a high-consequence page
(a one-time temp-password banner) until a dedicated audit caught it.

### Role-gating

`RequireRole` (singular `role` or plural `roles` prop) wraps every
role-restricted route. Inside a page shared by multiple roles,
`hasRole()`/`hasAnyRole()` from `useAuth()` gates individual controls —
and that gate must match the real backend `@PreAuthorize` **exactly**,
never more permissive. The recurring bug class this project has hit and
fixed multiple times: a page shared across roles (lab-order detail,
pharmacy dispense billing) renders a working-looking control (record
payment, refund, generate invoice) for a role the backend will actually
403 on submit. When wiring a shared page, check the real controller's own
`@PreAuthorize` for that specific action, don't assume the page's
broadest role covers every control on it.

### Accessibility baseline

Enforced project-wide after a dedicated audit found it was inconsistently
applied:

- Every icon-only control needs a real `aria-label`.
- Every clickable element that isn't a native `<button>`/`<a>` needs
  keyboard support — `tabIndex={0}`, `role="button"`, and an `onKeyDown`
  handling Enter/Space. See `DataTable`'s own row-click/expand handling
  for the reference implementation.
- A form input needs a real associated `<label>` — a `placeholder` alone
  is not a label (it disappears on input and most screen readers don't
  reliably announce it).

### Testing

Vitest + React Testing Library, deliberately scoped to **infrastructure +
shared components** (`DataTable`, `StatusPill`, `RequireRole`,
`lib/tableUtils.js`, a couple of `api/queries.js` hooks) rather than broad
page-level coverage — an explicit scope decision, asked of the user
directly rather than assumed, when test infrastructure was first added.
Most individual pages' own correctness still rests on `npm run build` +
manual verification, not automated page-level tests — don't assume a
passing `npm test` run means a given page's own behavior is covered.
