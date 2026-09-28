-- Phase 22 (finance): budgets, minimal payroll (salary + a monthly
-- pay-run action), and P&L/budget-vs-actual reporting - the third and
-- last phase of the pharmacy/accounting/finance module set, built on top
-- of phase 21's ledger. Shares the accountant realm role phase 20 already
-- added to Keycloak - no new role needed.

-- One row per staff AppUser a clinic has opted into payroll - not every
-- staff login has one. Resolved by email at creation (same
-- ProviderController.linkLogin precedent - "no account has ever logged in
-- with that email" 404 if unresolvable), with fullName/email snapshotted
-- at creation for display since there's no general staff-directory
-- endpoint to re-resolve a name from later.
CREATE TABLE employees (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES clinics(id),
    app_user_id  UUID NOT NULL REFERENCES app_users(id),
    full_name    VARCHAR(255),
    email        VARCHAR(255) NOT NULL,
    salary_amount NUMERIC(12, 2) NOT NULL CHECK (salary_amount > 0),
    -- active, inactive - soft-deactivate only, same reasoning as every
    -- other staff-adjacent resource (rooms/providers/medications):
    -- referenced by FK from payroll_payments with no cascade.
    status       VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, app_user_id)
);
CREATE INDEX idx_employees_tenant ON employees(tenant_id);

-- One row per "run payroll for this clinic, this month" action -
-- UNIQUE(tenant_id, year, month) is what makes a re-run of the same month
-- idempotent-by-rejection (409), not idempotent-by-no-op, matching
-- Invoice's own "immutable once issued, a repeat 409s" convention rather
-- than Encounter's upsert-in-place.
CREATE TABLE payroll_runs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES clinics(id),
    year        INTEGER NOT NULL,
    month       INTEGER NOT NULL CHECK (month BETWEEN 1 AND 12),
    total_amount NUMERIC(12, 2) NOT NULL,
    run_by      UUID REFERENCES app_users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, year, month)
);
CREATE INDEX idx_payroll_runs_tenant ON payroll_runs(tenant_id);

-- Line items, own table, no JPA relation - same "plain UUID FK + explicit
-- repository queries" convention as journal_lines/lab_order_tests, and
-- the same "carries its own tenant_id" precedent. journal_entry_id links
-- each payslip back to the specific balanced entry JournalService posted
-- for it (Salary Expense debit / Cash credit) - one entry per employee
-- per run, not one entry for the whole run, so a single employee's pay
-- can be traced (or reversed) on its own.
CREATE TABLE payroll_payments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES clinics(id),
    payroll_run_id   UUID NOT NULL REFERENCES payroll_runs(id),
    employee_id      UUID NOT NULL REFERENCES employees(id),
    amount           NUMERIC(12, 2) NOT NULL,
    journal_entry_id UUID NOT NULL REFERENCES journal_entries(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payroll_payments_tenant ON payroll_payments(tenant_id);
CREATE INDEX idx_payroll_payments_run ON payroll_payments(payroll_run_id);

-- Per-account, per-month target - UNIQUE(tenant_id, account_id, year,
-- month) so setting a budget for the same account/period twice is a real
-- conflict (update instead), not a silent second row. References
-- accounting.Account directly - finance is explicitly "built on top of
-- phase 21's ledger," not a parallel account concept.
CREATE TABLE budgets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES clinics(id),
    account_id  UUID NOT NULL REFERENCES accounts(id),
    year        INTEGER NOT NULL,
    month       INTEGER NOT NULL CHECK (month BETWEEN 1 AND 12),
    amount      NUMERIC(12, 2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, account_id, year, month)
);
CREATE INDEX idx_budgets_tenant ON budgets(tenant_id);
