-- Phase 16: pluggable (mock-for-now) payment gateway, full/partial refunds,
-- and a payment-to-invoice link. See com.clinicops.paymentgateway and
-- CLAUDE.md's phase-16 write-up for the full reasoning.

-- Only meaningful for a genuine staff-entered payment recorded against an
-- already-issued invoice - the fee_auto_charged shortcut (Cancellation/
-- Reschedule/LabOrderCancellationService) never sets this, since there's
-- no invoice to link at the point that row is created.
ALTER TABLE payments ADD COLUMN invoice_id UUID REFERENCES invoices(id);

-- gateway_transaction_id is set only when a payment is actually routed
-- through PaymentGatewayClient.charge() (every AppointmentPaymentController/
-- LabOrderPaymentController-recorded payment); the fee_auto_charged
-- shortcut bypasses the gateway entirely and leaves this null, same as
-- invoice_id above. gateway_status (allow-listed in code: pending/
-- succeeded/failed/refunded/partially_refunded) is the one general
-- lifecycle field covering every payment this phase touches, including a
-- refund recorded against a payment that was never gateway-charged.
ALTER TABLE payments ADD COLUMN gateway_transaction_id VARCHAR(120);
ALTER TABLE payments ADD COLUMN gateway_status VARCHAR(20);

-- Append-only audit rows, same "no update/delete anywhere" shape as
-- appointment_cancellations/appointment_reschedules/consent_records - a
-- refund is a fact that happened, never edited after the fact. Cumulative
-- refunded-so-far per payment is enforced in RefundService (a running-sum
-- check, not expressible as a plain CHECK constraint).
CREATE TABLE refunds (
    id                             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                      UUID NOT NULL REFERENCES clinics(id),
    payment_id                     UUID NOT NULL REFERENCES payments(id),
    amount                         NUMERIC(10,2) NOT NULL,
    reason                         VARCHAR(255),
    gateway_refund_transaction_id  VARCHAR(120),
    refunded_by                    UUID REFERENCES app_users(id),
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refunds_tenant ON refunds(tenant_id);
CREATE INDEX idx_refunds_payment ON refunds(payment_id);
