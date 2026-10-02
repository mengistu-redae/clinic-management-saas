-- Lab module L4 (CLAUDE.md sketch, 2026-10-02): basic QC logging.
-- A lightweight, genuinely append-only log of control-material runs per
-- instrument/day - flag-only (the pinned fork's recommended option): a
-- failed run is visible here but never blocks new result entry anywhere
-- else in this app, same bias every other workflow in this codebase
-- already holds (no confirm dialogs, trust staff judgment).

CREATE TABLE lab_qc_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES clinics(id),
    instrument_identifier TEXT NOT NULL,
    analyte_name TEXT NOT NULL,
    control_material_lot TEXT NOT NULL,
    expected_range_low NUMERIC(12, 4) NOT NULL,
    expected_range_high NUMERIC(12, 4) NOT NULL,
    observed_value TEXT NOT NULL,
    pass BOOLEAN NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_lab_qc_runs_tenant_instrument ON lab_qc_runs (tenant_id, instrument_identifier);
