-- Phase 29 (pharmacy expansion): stock/inventory foundation. Delivers
-- the "medications are just one category of general stock" architecture
-- - a new com.clinicops.inventory package (InventoryItem, generalized
-- StockBatch, Supplier, PurchaseOrder/PurchaseOrderLine, StockAdjustment).
-- General (non-pharmacy) inventory is managed by clinic_admin+front_desk;
-- pharmacy/medication stock keeps its existing pharmacist+clinic_admin
-- gate, completely unchanged.

CREATE TABLE inventory_items (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES clinics(id),
    name                VARCHAR(255) NOT NULL,
    -- clinical_supply, ppe, office_supply
    category            VARCHAR(30) NOT NULL,
    unit_of_measure     VARCHAR(50),
    unit_price          NUMERIC(10, 2) NOT NULL DEFAULT 0,
    reorder_threshold   INTEGER NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_inventory_items_tenant ON inventory_items(tenant_id);

-- StockBatch generalized: medication_id becomes optional, a new
-- inventory_item_id joins it - exactly one of the two is ever set, same
-- CHECK-constraint shape chk_payments_exactly_one_owner/
-- chk_invoices_exactly_one_owner already use. Every pre-existing row
-- already has medication_id set, so this is a safe, non-destructive
-- widening.
ALTER TABLE stock_batches ALTER COLUMN medication_id DROP NOT NULL;
ALTER TABLE stock_batches ADD COLUMN inventory_item_id UUID REFERENCES inventory_items(id);
ALTER TABLE stock_batches ADD CONSTRAINT chk_stock_batches_exactly_one_owner
    CHECK ((medication_id IS NOT NULL) <> (inventory_item_id IS NOT NULL));
CREATE INDEX idx_stock_batches_inventory_item ON stock_batches(inventory_item_id);

CREATE TABLE suppliers (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES clinics(id),
    name          VARCHAR(255) NOT NULL,
    contact_name  VARCHAR(255),
    phone         VARCHAR(50),
    email         VARCHAR(255),
    status        VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_suppliers_tenant ON suppliers(tenant_id);

CREATE TABLE purchase_orders (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES clinics(id),
    supplier_id   UUID NOT NULL REFERENCES suppliers(id),
    -- ordered, received, cancelled
    status        VARCHAR(20) NOT NULL DEFAULT 'ordered',
    ordered_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    received_at   TIMESTAMPTZ,
    notes         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_purchase_orders_tenant ON purchase_orders(tenant_id);

-- Own table, no JPA relation, same plain-UUID-FK + own-tenant_id
-- convention as journal_lines/lab_order_tests.
CREATE TABLE purchase_order_lines (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES clinics(id),
    purchase_order_id   UUID NOT NULL REFERENCES purchase_orders(id),
    medication_id       UUID REFERENCES medications(id),
    inventory_item_id   UUID REFERENCES inventory_items(id),
    quantity_ordered    INTEGER NOT NULL,
    unit_cost           NUMERIC(10, 2),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_purchase_order_lines_exactly_one_owner
        CHECK ((medication_id IS NOT NULL) <> (inventory_item_id IS NOT NULL))
);
CREATE INDEX idx_purchase_order_lines_order ON purchase_order_lines(purchase_order_id);

-- Genuinely append-only, same audit-row shape as dispense_records - no
-- update/delete anywhere. quantity_delta is signed (negative for
-- usage/waste, positive for a correction).
CREATE TABLE stock_adjustments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES clinics(id),
    stock_batch_id    UUID NOT NULL REFERENCES stock_batches(id),
    quantity_delta    INTEGER NOT NULL,
    -- used, wasted, expired, correction, other
    reason            VARCHAR(20) NOT NULL,
    adjusted_by       UUID REFERENCES app_users(id),
    notes             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_stock_adjustments_tenant ON stock_adjustments(tenant_id);
CREATE INDEX idx_stock_adjustments_batch ON stock_adjustments(stock_batch_id);
