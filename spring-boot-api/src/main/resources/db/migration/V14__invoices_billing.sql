-- Phase 15: real billing. invoices has sat unused since V1 - extend it to
-- the same dual-owner shape payments already uses (V5), so an invoice can
-- belong to either an appointment or a lab order.
ALTER TABLE invoices ALTER COLUMN appointment_id DROP NOT NULL;
ALTER TABLE invoices ADD COLUMN lab_order_id UUID REFERENCES lab_orders(id);
ALTER TABLE invoices ADD CONSTRAINT invoices_lab_order_id_key UNIQUE (lab_order_id);
ALTER TABLE invoices ADD CONSTRAINT chk_invoices_exactly_one_owner
    CHECK ((appointment_id IS NOT NULL) <> (lab_order_id IS NOT NULL));
