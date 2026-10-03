-- Real bug found live while first starting the rebuilt container (2026-10-04):
-- V37's own imaging_orders table was missing created_at entirely, a column
-- every BaseTenantEntity subclass requires - Hibernate's ddl-auto: validate
-- correctly refused to start rather than silently tolerating the mismatch.
-- V37 already applied (its checksum is fixed), so this is a new migration
-- rather than an edit to it - same "migrations are immutable once applied"
-- convention every other phase in this project already holds to.
ALTER TABLE imaging_orders ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
