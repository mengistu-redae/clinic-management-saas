-- Phase 3 additions - fee_policies and appointment_reschedules already
-- exist (V1__init.sql) with everything cancellation/reschedule needs; the
-- check-in state machine reuses appointments.status (already free-text),
-- so the only new table here is the cancellation audit row (mirrors the
-- reference bus-ticketing-saas project's `cancellations` table).

CREATE TABLE appointment_cancellations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES clinics(id),
    appointment_id     UUID NOT NULL REFERENCES appointments(id),
    -- Null for a patient self-cancel - there's no staff actor to record.
    cancelled_by       UUID REFERENCES app_users(id),
    reason             VARCHAR(255),
    fee_amount         NUMERIC(10,2) NOT NULL DEFAULT 0,
    cancelled_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_appointment_cancellations_tenant ON appointment_cancellations(tenant_id);
