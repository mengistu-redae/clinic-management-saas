# Documentation

This directory is the project's own documentation set, written as a 2026-10
pass over the existing `clinic-management-saas` codebase (not the running
narrative decision-log that `CLAUDE.md` at the repo root is — these two
serve different purposes and are meant to coexist, see below).

## Reading order

There's no single required order, but if you're new to this project, this
sequence builds context sensibly:

1. **[system-design.md](system-design.md)** — what this system is, the
   high-level architecture, the multi-tenancy model, the 7 roles, and how
   the product evolved across its build phases. Start here.
2. **[component-architecture.md](component-architecture.md)** — one level
   more concrete: the actual deployable pieces, the backend's package
   structure, the frontend's shared component layer, and a request-flow
   walkthrough.
3. **[integration-architecture.md](integration-architecture.md)** — how
   this system wires itself to Keycloak, Postgres, Redis, nginx, Mailpit,
   the payment gateway, and file storage.
4. **[data-model.md](data-model.md)** — the database schema, table by
   table, as it stands after all 29 Flyway migrations.
5. **[api-reference.md](api-reference.md)** — every REST endpoint
   `spring-boot-api` exposes, organized by domain, with its role gate.
6. **[coding-conventions.md](coding-conventions.md)** — how code here is
   actually written (backend and frontend), so new code matches.
7. **[setup-manual.md](setup-manual.md)** — get the whole stack running on
   your own machine.
8. **[deployment-guide.md](deployment-guide.md)** — how the running stack
   is actually composed (services, ports, env vars, migrations), as it
   exists today — a local/dev docker-compose deployment, not a production
   one (none exists yet).
9. **[user-guide.md](user-guide.md)** — the only document here written for
   an end user rather than a developer: what each of the 7 roles can
   actually do in the app, day to day.

## How this relates to `CLAUDE.md`

The repo root's `CLAUDE.md` (and its overflow file, `CLAUDE-history.md`) is
a different kind of document: a dated, append-only log of *why* each
decision was made, session by session, across every phase of this
project's build-out — the project's own working memory. It's exhaustive on
history and reasoning, but it isn't organized as a reference.

The documents in this directory are the reference shape of the same
underlying system: organized by topic, not by when it was built, meant to
answer "how does X work" or "what does Y look like today" without reading
through 39 phases of history to reconstruct it. When the two disagree on a
point of current fact, trust whichever one was actually verified against
the running code most recently — and if you find a real contradiction,
it's worth fixing in both places, not just one.

## Keeping this set current

These documents describe the system as of commit `d9dc528` (migration
`V29`). They're static snapshots, not generated from the code — nothing
here re-runs automatically when the code changes. When a future change
touches something one of these documents describes (a new endpoint, a new
table, a new shared frontend component, a new integration), update the
relevant document in the same change, the same way `CLAUDE.md` itself is
already kept current session by session.
