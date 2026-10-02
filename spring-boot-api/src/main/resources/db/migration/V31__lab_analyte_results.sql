-- Lab module L2 (CLAUDE.md sketch, 2026-10-02): structured per-analyte
-- results, additive alongside lab_order_tests' own existing flat
-- result_value/result_unit/reference_range/abnormal_flag columns - those
-- stay exactly as they are (the old POST .../result endpoint keeps working
-- unchanged for any test, structured or not), this is a parallel, richer
-- path a test gains once a real analyte catalog exists for its test_code.
--
-- One range per test+analyte (no age/sex banding in v1 - a direct,
-- recommended-option answer to the fork this module's own sketch left
-- open). normal_range_low/high are numeric for auto-flagging; a
-- non-numeric analyte (e.g. a qualitative urinalysis field expected to
-- read "Negative") instead sets normal_range_text, with no auto-flag
-- computed for it - the entry is still structured and catalog-driven,
-- just not flaggable without a numeric comparison.

CREATE TABLE analyte_definitions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES clinics(id),
    test_code           VARCHAR(50) NOT NULL,
    analyte_name        VARCHAR(255) NOT NULL,
    display_order       INTEGER NOT NULL DEFAULT 0,
    unit                VARCHAR(50),
    normal_range_low    NUMERIC(12, 4),
    normal_range_high   NUMERIC(12, 4),
    normal_range_text   VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, test_code, analyte_name)
);
CREATE INDEX idx_analyte_definitions_tenant_test ON analyte_definitions(tenant_id, test_code);

-- name/unit/referenceRangeDisplay are snapshotted from the AnalyteDefinition
-- at result-entry time (analyte_definition_id can be null - an ad-hoc
-- analyte with no catalog entry is still enterable, just never flagged) -
-- same "snapshot at the time, never silently drift if the catalog later
-- changes" convention lab_order_tests.price already uses against
-- lab_test_rates.
CREATE TABLE analyte_results (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES clinics(id),
    lab_order_test_id       UUID NOT NULL REFERENCES lab_order_tests(id),
    analyte_definition_id   UUID REFERENCES analyte_definitions(id),
    analyte_name            VARCHAR(255) NOT NULL,
    value                   VARCHAR(255) NOT NULL,
    unit                    VARCHAR(50),
    reference_range_display VARCHAR(255),
    -- normal, abnormal, or unflagged (no numeric range to compare against -
    -- true 3-way normal/abnormal/critical flagging is L3's own job, once a
    -- critical range exists alongside the normal one defined here)
    flag                    VARCHAR(20) NOT NULL DEFAULT 'unflagged',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_analyte_results_tenant ON analyte_results(tenant_id);
CREATE INDEX idx_analyte_results_test ON analyte_results(lab_order_test_id);
