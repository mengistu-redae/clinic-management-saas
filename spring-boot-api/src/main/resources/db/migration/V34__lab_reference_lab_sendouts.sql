-- Lab module L5 (CLAUDE.md sketch, 2026-10-02): external reference-lab
-- send-outs. A new specimen status branch - "sent_to_reference_lab" -
-- reachable from collected/in_transit/received (a specimen can be sent
-- out at whatever point staff realize it needs to go to an outside lab,
-- same lenient "no enforced single path" bias this app already holds).
-- Results coming back are entered through the existing structured
-- per-analyte shape (L2) - no new result entity, no parallel path.

ALTER TABLE specimens ADD COLUMN reference_lab_name TEXT;
ALTER TABLE specimens ADD COLUMN reference_lab_order_number TEXT;
ALTER TABLE specimens ADD COLUMN expected_turnaround_days INTEGER;
ALTER TABLE specimens ADD COLUMN sent_to_reference_lab_at TIMESTAMPTZ;
