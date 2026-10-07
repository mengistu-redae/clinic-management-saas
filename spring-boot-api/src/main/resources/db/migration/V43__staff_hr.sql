-- Staff/HR: roster + attendance. A new role-neutral "any staff member"
-- concept this app didn't have before (clinic_admin/provider/front_desk/
-- pharmacist/accountant/lab_technician/imaging_technologist all lacked a
-- shared domain row) - deliberately separate from Finance's Employee
-- (phase 22, payroll-only scope). Shifts are ad-hoc per-date assignments,
-- not recurring templates. Attendance is a correctable present/absent/
-- leave outcome against one shift (one row per shift, upsert-in-place),
-- not a real clock-in/out punch. clinic_admin manages all of it.

CREATE TABLE staff (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES clinics(id),
    app_user_id   UUID REFERENCES app_users(id),
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    role          VARCHAR(30) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_staff_tenant_id ON staff(tenant_id);

CREATE TABLE shifts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES clinics(id),
    staff_id    UUID NOT NULL REFERENCES staff(id),
    shift_date  DATE NOT NULL,
    start_time  TIME NOT NULL,
    end_time    TIME NOT NULL,
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_shifts_tenant_id ON shifts(tenant_id);
CREATE INDEX idx_shifts_staff_id_date ON shifts(staff_id, shift_date);

CREATE TABLE attendance_records (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES clinics(id),
    shift_id     UUID NOT NULL UNIQUE REFERENCES shifts(id),
    status       VARCHAR(20) NOT NULL,
    notes        TEXT,
    recorded_by  UUID REFERENCES app_users(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attendance_records_tenant_id ON attendance_records(tenant_id);
