-- Phase 46: billing realism - a discount entered at invoice-generation
-- time. Both columns are additive: an existing invoice gets discount_amount
-- = 0 and discount_reason = null, identical to today's totalAmount.
ALTER TABLE invoices ADD COLUMN discount_amount NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN discount_reason VARCHAR(255);
