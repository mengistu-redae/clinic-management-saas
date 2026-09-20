-- PHI access audit log (closes the "PHI-access audit: deferred" gap
-- pinned 2026-09-12). Append-only - no update/delete path exists anywhere
-- in the app, matching an audit trail's own "never rewritten" nature.
-- patient_id is nullable because a guest-channel appointment/encounter has
-- no Patient row at all (contactName only) - the audited resource still
-- gets logged via resource_type/resource_id, just with no patient to link.

CREATE TABLE phi_access_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES clinics(id),
    actor_user_id   UUID NOT NULL REFERENCES app_users(id),
    actor_email     VARCHAR(255) NOT NULL,
    actor_role      VARCHAR(30) NOT NULL,
    patient_id      UUID REFERENCES patients(id),
    resource_type   VARCHAR(30) NOT NULL,
    resource_id     UUID,
    action          VARCHAR(10) NOT NULL, -- read, write
    endpoint        VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_phi_access_log_tenant ON phi_access_log(tenant_id);
CREATE INDEX idx_phi_access_log_patient ON phi_access_log(patient_id);
CREATE INDEX idx_phi_access_log_created_at ON phi_access_log(created_at);
