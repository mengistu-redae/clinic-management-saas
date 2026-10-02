-- Lab module L3 (CLAUDE.md sketch, 2026-10-02): critical-value alerting.
-- A critical range alongside each analyte's existing normal range -
-- the outer danger-zone bounds beyond which AnalyteResultService's own
-- flag computation now reports "critical" instead of just "abnormal"
-- (true 3-way flagging, closing the gap L2 deliberately left open).

ALTER TABLE analyte_definitions ADD COLUMN critical_range_low NUMERIC(12, 4);
ALTER TABLE analyte_definitions ADD COLUMN critical_range_high NUMERIC(12, 4);

-- A critical result needs a real acknowledgment trail - who saw the alert
-- and confirmed it, not just that the flag was computed. Null until
-- acknowledged; meaningless (always null) for a non-critical result.
ALTER TABLE analyte_results ADD COLUMN critical_acknowledged_at TIMESTAMPTZ;
ALTER TABLE analyte_results ADD COLUMN critical_acknowledged_by UUID REFERENCES app_users(id);
