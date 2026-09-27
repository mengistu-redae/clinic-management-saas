-- Phase 21 (accounting): a chart of accounts + a real double-entry
-- journal, auto-posted whenever an existing Payment/Refund happens (see
-- com.clinicops.payment) - the ledger picks up real cash events, it
-- doesn't create new ones. Second phase of the pharmacy/accounting/finance
-- module set; the accountant realm role was already added to Keycloak in
-- phase 20, alongside pharmacist.

CREATE TABLE accounts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES clinics(id),
    code        VARCHAR(20) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    -- asset, liability, equity, revenue, expense
    type        VARCHAR(20) NOT NULL,
    -- active, inactive - soft-deactivate only, same reasoning as rooms/
    -- appointment_types/medications: referenced by FK from journal_lines
    -- with no cascade, so a real delete would fail once anything posts to it.
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);
CREATE INDEX idx_accounts_tenant ON accounts(tenant_id);

-- The journal entry header - one per posted transaction (a Payment or a
-- Refund today; source_type/source_id trace back to whichever domain event
-- caused it). Genuinely immutable once posted - no updated_at, no
-- update/delete endpoint anywhere, same "audit-only table" precedent as
-- appointment_cancellations/consent_records/phi_access_log. A mistake is
-- corrected with a reversing entry, not an edit.
CREATE TABLE journal_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES clinics(id),
    description VARCHAR(255) NOT NULL,
    -- appointment_payment, lab_order_payment, refund
    source_type VARCHAR(30) NOT NULL,
    source_id   UUID NOT NULL,
    posted_by   UUID REFERENCES app_users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_journal_entries_tenant ON journal_entries(tenant_id);

-- Line items, own table, no JPA relation - plain UUID FK + explicit
-- repository queries, same convention as lab_order_tests. Carries its own
-- tenant_id (a denormalized copy of its parent journal_entries row's own),
-- matching lab_order_tests/prescriptions' own precedent of every entity
-- extending BaseTenantEntity even as a line item, so every repository
-- query keeps the same tenant-scoped shape as the rest of this app.
CREATE TABLE journal_lines (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES clinics(id),
    journal_entry_id  UUID NOT NULL REFERENCES journal_entries(id),
    account_id        UUID NOT NULL REFERENCES accounts(id),
    amount            NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    -- debit, credit
    entry_type        VARCHAR(10) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_journal_lines_tenant ON journal_lines(tenant_id);
CREATE INDEX idx_journal_lines_entry ON journal_lines(journal_entry_id);
CREATE INDEX idx_journal_lines_account ON journal_lines(account_id);
