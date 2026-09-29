-- Phase 31 (pharmacy expansion): pharmacy billing integration. A
-- dispense can now be paid for and invoiced through the exact same
-- Payment/Invoice machinery appointments and lab orders already use.

ALTER TABLE payments ADD COLUMN dispense_record_id UUID REFERENCES dispense_records(id);
ALTER TABLE payments DROP CONSTRAINT chk_payments_exactly_one_owner;
ALTER TABLE payments ADD CONSTRAINT chk_payments_exactly_one_owner
    CHECK (
        (CASE WHEN appointment_id IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN lab_order_id IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN dispense_record_id IS NOT NULL THEN 1 ELSE 0 END) = 1
    );

-- Same "generate once" DB-level enforcement appointment_id/lab_order_id
-- already carry via their own UNIQUE constraints.
ALTER TABLE invoices ADD COLUMN dispense_record_id UUID UNIQUE REFERENCES dispense_records(id);
ALTER TABLE invoices DROP CONSTRAINT chk_invoices_exactly_one_owner;
ALTER TABLE invoices ADD CONSTRAINT chk_invoices_exactly_one_owner
    CHECK (
        (CASE WHEN appointment_id IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN lab_order_id IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN dispense_record_id IS NOT NULL THEN 1 ELSE 0 END) = 1
    );
