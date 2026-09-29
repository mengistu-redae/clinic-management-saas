-- Phase 30 (pharmacy expansion): equipment & asset tracking. Adds Asset
-- (one row per physical item, not quantity-based) and a simple
-- append-only AssetMaintenanceRecord log to com.clinicops.inventory -
-- deliberately no due-date/reminder field, per the user's own pinned
-- decision when this was first sketched: a history to look back on, not
-- a second scheduling system alongside appointments.

CREATE TABLE assets (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES clinics(id),
    name                VARCHAR(255) NOT NULL,
    serial_number       VARCHAR(255),
    purchase_date       DATE,
    purchase_price      NUMERIC(12, 2),
    warranty_expiry     DATE,
    -- in_service, under_maintenance, retired, disposed
    status              VARCHAR(20) NOT NULL DEFAULT 'in_service',
    assigned_room_id    UUID REFERENCES rooms(id),
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_assets_tenant ON assets(tenant_id);

-- Genuinely append-only, same shape as dispense_records/stock_adjustments
-- - no update/delete anywhere. No next_due_at column - the pinned scope
-- boundary, not just left off the API.
CREATE TABLE asset_maintenance_records (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES clinics(id),
    asset_id      UUID NOT NULL REFERENCES assets(id),
    performed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    description   TEXT NOT NULL,
    performed_by  UUID REFERENCES app_users(id),
    notes         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_asset_maintenance_records_tenant ON asset_maintenance_records(tenant_id);
CREATE INDEX idx_asset_maintenance_records_asset ON asset_maintenance_records(asset_id);
