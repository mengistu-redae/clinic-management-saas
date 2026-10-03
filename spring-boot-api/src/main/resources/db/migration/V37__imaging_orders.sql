-- Imaging/radiology orders (sketched 2026-10-04) - a full structured
-- module per the user's own answer, mirroring the lab module's own
-- role-split/critical-alerting/billing-integration depth rather than a
-- minimal first pass. A new `imaging_technologist` realm role performs
-- the study (schedule/start/complete - purely mechanical, no clinical
-- content); `provider`+`clinic_admin` order it and write the actual
-- report (findings/impression + a manually-flagged critical finding,
-- since imaging findings are narrative, not numeric like lab's own
-- AnalyteResult - there's no range to auto-compute a flag from).
--
-- Deliberately simpler than LabOrder in two real ways, not oversights:
-- one study per order (no LabOrderTest-style line items - a radiology
-- order is typically one study, unlike a lab panel's many individual
-- tests), and no patient-initiated "requested" flow (lab's own
-- request->confirm-and-order path isn't being mirrored here - every
-- imaging order starts staff/provider-created).

CREATE TABLE imaging_study_rates (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES clinics(id),
    study_code   VARCHAR(50) NOT NULL,
    study_name   VARCHAR(200) NOT NULL,
    modality     VARCHAR(20) NOT NULL,
    base_charge  NUMERIC(12,2) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, study_code)
);

CREATE TABLE imaging_orders (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                  UUID NOT NULL REFERENCES clinics(id),
    patient_id                 UUID NOT NULL REFERENCES patients(id),
    ordering_provider_id       UUID NOT NULL REFERENCES providers(id),
    order_ref                  VARCHAR(20) NOT NULL UNIQUE,
    clinic_ref                 VARCHAR(50),
    -- xray, ultrasound, ct, mri, other
    modality                   VARCHAR(20) NOT NULL,
    study_type                 VARCHAR(200) NOT NULL,
    study_code                 VARCHAR(50),
    -- routine, urgent, stat
    priority                   VARCHAR(20) NOT NULL DEFAULT 'routine',
    -- ordered -> scheduled -> in_progress -> completed -> reviewed, or cancelled (pre-study only)
    status                     VARCHAR(20) NOT NULL DEFAULT 'ordered',
    notes                      TEXT,
    total_cost                 NUMERIC(12,2),
    findings                   TEXT,
    impression                 TEXT,
    critical_finding            BOOLEAN NOT NULL DEFAULT false,
    critical_acknowledged_at    TIMESTAMPTZ,
    critical_acknowledged_by    UUID REFERENCES app_users(id),
    ordered_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    scheduled_at               TIMESTAMPTZ,
    started_at                 TIMESTAMPTZ,
    completed_at               TIMESTAMPTZ,
    reviewed_at                TIMESTAMPTZ,
    reviewed_by                UUID REFERENCES app_users(id),
    cancelled_at               TIMESTAMPTZ,
    cancellation_reason        TEXT
);
CREATE INDEX idx_imaging_orders_tenant ON imaging_orders(tenant_id);
CREATE INDEX idx_imaging_orders_patient ON imaging_orders(tenant_id, patient_id);

-- Imaging orders as a 4th billable owner type - Payment/Invoice already
-- grew their own exactly-one-owner CHECK from 2 to 3 columns in V27
-- (pharmacy billing integration); this widens the identical shape to 4.
ALTER TABLE payments ADD COLUMN imaging_order_id UUID REFERENCES imaging_orders(id);
ALTER TABLE payments DROP CONSTRAINT chk_payments_exactly_one_owner;
ALTER TABLE payments ADD CONSTRAINT chk_payments_exactly_one_owner
    CHECK (
        (CASE WHEN appointment_id IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN lab_order_id IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN dispense_record_id IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN imaging_order_id IS NOT NULL THEN 1 ELSE 0 END) = 1
    );

ALTER TABLE invoices ADD COLUMN imaging_order_id UUID UNIQUE REFERENCES imaging_orders(id);
ALTER TABLE invoices DROP CONSTRAINT chk_invoices_exactly_one_owner;
ALTER TABLE invoices ADD CONSTRAINT chk_invoices_exactly_one_owner
    CHECK (
        (CASE WHEN appointment_id IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN lab_order_id IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN dispense_record_id IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN imaging_order_id IS NOT NULL THEN 1 ELSE 0 END) = 1
    );
