package com.clinicops.encounter;

/**
 * All fields optional, matching the nullable encounters.chief_complaint/
 * assessment/plan/icd10_codes columns. icd10Codes is free text (phase 11)
 * - see Encounter's own javadoc. The 18 exam-finding fields (phase 25) are
 * a fixed 9-system checklist, each a nullable normal/abnormal flag plus a
 * free-text note - same shape/order as the columns on Encounter itself.
 * Stays a flat positional record even at this width, matching
 * UpsertVitalsRequest's own "many nullable scalar fields in one record"
 * precedent rather than introducing a nested request shape.
 */
public record UpsertEncounterRequest(
        String chiefComplaint,
        String assessment,
        String plan,
        String icd10Codes,
        Boolean generalAppearanceNormal,
        String generalAppearanceNote,
        Boolean heentNormal,
        String heentNote,
        Boolean cardiovascularNormal,
        String cardiovascularNote,
        Boolean respiratoryNormal,
        String respiratoryNote,
        Boolean abdominalNormal,
        String abdominalNote,
        Boolean musculoskeletalNormal,
        String musculoskeletalNote,
        Boolean neurologicalNormal,
        String neurologicalNote,
        Boolean skinNormal,
        String skinNote,
        Boolean psychiatricNormal,
        String psychiatricNote) {
}
