-- Phase 2 verification seed: a demo room/provider/appointment-type/working
-- hours for the demo clinic created by infra/keycloak/create-demo-clinic.sh.
-- Provider/room/appointment-type admin CRUD is phase 5 scope - until then,
-- this is how test data gets in. Run:
--   docker compose exec -T postgres psql -U clinicops -d clinic_management -f /dev/stdin < infra/postgres/seed-demo-scheduling-data.sql
-- (or paste the DO block via `docker compose exec -T postgres psql -U clinicops -d clinic_management`)

DO $$
DECLARE
    v_clinic_id   UUID;
    v_room_id     UUID;
    v_provider_id UUID;
    v_type_id     UUID;
BEGIN
    SELECT id INTO v_clinic_id FROM clinics WHERE keycloak_org_id = 'demo-clinic';
    IF v_clinic_id IS NULL THEN
        RAISE EXCEPTION 'No clinic with keycloak_org_id = demo-clinic - run infra/keycloak/create-demo-clinic.sh first';
    END IF;

    INSERT INTO rooms (tenant_id, name) VALUES (v_clinic_id, 'Room 1') RETURNING id INTO v_room_id;

    INSERT INTO providers (tenant_id, full_name, specialty, room_id)
    VALUES (v_clinic_id, 'Dr. Demo Provider', 'General Practice', v_room_id)
    RETURNING id INTO v_provider_id;

    INSERT INTO appointment_types (tenant_id, name, duration_minutes, price_amount)
    VALUES (v_clinic_id, 'New Patient / 30 min', 30, 50.00)
    RETURNING id INTO v_type_id;

    -- 9am-5pm every day of the week (0=Sunday..6=Saturday).
    INSERT INTO provider_working_hours (tenant_id, provider_id, day_of_week, start_time, end_time)
    SELECT v_clinic_id, v_provider_id, dow, '09:00', '17:00' FROM generate_series(0, 6) AS dow;

    -- Clinic-wide no-show/late-cancel fee tiers (phase 3 verification) -
    -- 24h+ notice: no fee; 2-24h: 50%; under 2h: 100%.
    INSERT INTO fee_policies (tenant_id, provider_id, cutoff_hours, fee_percent) VALUES
        (v_clinic_id, NULL, 24, 0),
        (v_clinic_id, NULL, 2, 50),
        (v_clinic_id, NULL, 0, 100);

    RAISE NOTICE 'clinic_id=%, room_id=%, provider_id=%, appointment_type_id=%',
        v_clinic_id, v_room_id, v_provider_id, v_type_id;
END $$;
