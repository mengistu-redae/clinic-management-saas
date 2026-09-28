# Standard Clinical Forms & Templates — Reference for Clinic System Development

This document lays out the core forms most outpatient clinic systems need, with suggested
fields and data types. Use it as a starting point for your database schema and form UIs.
Field types are suggested (adapt to your stack): `str`, `text` (long text), `int`, `date`,
`datetime`, `bool`, `enum`, `float`, `file`.

---

## 1. Patient Registration / Intake Form

**Purpose:** Create the master patient record (used once, updated as needed).

| Field | Type | Notes |
|---|---|---|
| patient_id | str (auto) | Unique MRN / medical record number |
| full_name | str | |
| date_of_birth | date | |
| sex / gender | enum | |
| national_id / passport_no | str | Optional, per jurisdiction |
| phone_primary | str | |
| phone_secondary | str | Optional |
| email | str | |
| address | text | |
| emergency_contact_name | str | |
| emergency_contact_relationship | str | |
| emergency_contact_phone | str | |
| marital_status | enum | |
| occupation | str | |
| insurance_provider | str | Optional |
| insurance_policy_no | str | Optional |
| referral_source | str | How they found the clinic |
| registration_date | datetime | Auto |
| photo | file | Optional, for identity |

---

## 2. Medical History Form

**Purpose:** Baseline health background, usually filled at first visit and updated periodically.

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| chief_complaint | text | Reason for visit (per-encounter, not master record) |
| past_medical_conditions | multi-select/text | Diabetes, hypertension, asthma, etc. |
| past_surgeries | text | With dates |
| current_medications | text/table | Name, dose, frequency |
| known_allergies | text/table | Substance, reaction type, severity |
| family_history | text | Relevant hereditary conditions |
| social_history | text | Smoking, alcohol, exercise, occupExposure |
| immunization_history | text/table | See Form 14 for structured version |
| obstetric_history | text | If applicable |
| last_updated | datetime | |

---

## 3. Vital Signs Record

**Purpose:** Captured at every visit, typically by nursing staff.

| Field | Type | Notes |
|---|---|---|
| encounter_id | str (FK) | Links to the visit |
| recorded_at | datetime | |
| height_cm | float | |
| weight_kg | float | |
| bmi | float | Auto-calculated |
| temperature_c | float | |
| pulse_bpm | int | |
| respiratory_rate | int | |
| blood_pressure_systolic | int | |
| blood_pressure_diastolic | int | |
| oxygen_saturation_pct | float | |
| pain_score | int (0–10) | |
| recorded_by | str (FK to staff) | |

---

## 4. Informed Consent Form (General Treatment)

**Purpose:** Legal record that the patient agreed to general care.

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| consent_text_version | str | Track which version of legal text was shown |
| patient_signature | file/image | Or e-signature blob |
| witness_name | str | Optional |
| date_signed | date | |
| language_presented | str | For informed-consent validity |

---

## 5. Procedure-Specific Consent Form

**Purpose:** Required before any procedure/surgery with distinct risks.

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| procedure_name | str | |
| risks_explained | text | Checklist or free text |
| alternatives_discussed | text | |
| physician_name | str (FK to staff) | |
| patient_signature | file | |
| date_signed | datetime | |

---

## 6. Privacy / Data-Protection Consent

**Purpose:** Acknowledgment that patient data will be stored/used per your privacy policy
(e.g., HIPAA in the US, GDPR in the EU, or local equivalents — check what applies to your
jurisdiction).

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| policy_version | str | |
| consent_given | bool | |
| date_signed | date | |
| data_sharing_preferences | enum/bool set | e.g., allow SMS reminders, research use |

---

## 7. Physical Examination Form

**Purpose:** Structured exam findings per body system.

| Field | Type | Notes |
|---|---|---|
| encounter_id | str (FK) | |
| general_appearance | text | |
| heent | text | Head, eyes, ears, nose, throat |
| cardiovascular | text | |
| respiratory | text | |
| abdominal | text | |
| musculoskeletal | text | |
| neurological | text | |
| skin | text | |
| psychiatric | text | |
| examined_by | str (FK to staff) | |
| exam_date | datetime | |

---

## 8. Progress / SOAP Note

**Purpose:** The standard clinical note format for each encounter (Subjective, Objective,
Assessment, Plan).

| Field | Type | Notes |
|---|---|---|
| encounter_id | str (FK) | |
| subjective | text | Patient-reported symptoms |
| objective | text | Exam findings, vitals, test results |
| assessment | text | Diagnosis / clinical impression (often ICD-10 coded) |
| plan | text | Treatment plan, follow-up |
| icd10_codes | multi-select | Diagnosis coding |
| author | str (FK to staff) | |
| note_datetime | datetime | |
| signed_locked | bool | Notes are typically locked after signing |

---

## 9. Prescription / Medication Order

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| encounter_id | str (FK) | |
| medication_name | str | Ideally linked to a drug database |
| dosage | str | e.g., "500mg" |
| route | enum | Oral, IV, IM, topical, etc. |
| frequency | str | e.g., "twice daily" |
| duration | str | e.g., "7 days" |
| quantity_dispensed | int | |
| refills_allowed | int | |
| prescribing_physician | str (FK to staff) | |
| date_prescribed | datetime | |
| notes | text | Special instructions |
| status | enum | Active, completed, discontinued |

---

## 10. Laboratory Test Request Form

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| encounter_id | str (FK) | |
| tests_requested | multi-select | CBC, glucose, lipid panel, etc. |
| clinical_notes | text | Reason for test / suspected diagnosis |
| priority | enum | Routine, urgent, stat |
| ordering_physician | str (FK to staff) | |
| order_datetime | datetime | |
| result_status | enum | Pending, completed, cancelled |
| result_file | file | Attached report |
| result_date | datetime | |

---

## 11. Radiology / Imaging Request Form

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| imaging_type | enum | X-ray, ultrasound, CT, MRI |
| body_area | str | |
| clinical_indication | text | |
| ordering_physician | str (FK) | |
| order_datetime | datetime | |
| report_file | file | |
| radiologist_notes | text | |

---

## 12. Referral Form

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| referring_physician | str (FK) | |
| referred_to_specialty | str | |
| referred_to_provider | str | Name/clinic, if external |
| reason_for_referral | text | |
| clinical_summary | text | |
| urgency | enum | Routine, urgent |
| date_referred | date | |
| status | enum | Pending, scheduled, completed |

---

## 13. Discharge Summary

**Purpose:** Used for inpatient stays or day-procedures; some outpatient clinics use a
lightweight version as a "visit summary."

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| admission_date | date | If applicable |
| discharge_date | date | |
| diagnosis_final | text | |
| procedures_performed | text | |
| hospital_course | text | Summary of what happened |
| discharge_medications | text/table | |
| follow_up_instructions | text | |
| discharging_physician | str (FK) | |

---

## 14. Immunization Record

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| vaccine_name | str | |
| dose_number | int | |
| date_administered | date | |
| lot_number | str | |
| administered_by | str (FK to staff) | |
| site_of_administration | str | e.g., left deltoid |
| next_dose_due | date | Optional |

---

## 15. Appointment / Scheduling Record

| Field | Type | Notes |
|---|---|---|
| appointment_id | str (auto) | |
| patient_id | str (FK) | |
| provider_id | str (FK to staff) | |
| appointment_datetime | datetime | |
| duration_minutes | int | |
| visit_type | enum | New patient, follow-up, procedure, telehealth |
| status | enum | Scheduled, checked-in, completed, no-show, cancelled |
| notes | text | |
| reminder_sent | bool | |

---

## 16. Billing / Invoice Form

| Field | Type | Notes |
|---|---|---|
| invoice_id | str (auto) | |
| patient_id | str (FK) | |
| encounter_id | str (FK) | |
| line_items | table | Service code, description, quantity, unit price |
| total_amount | float | |
| insurance_claim_no | str | Optional |
| payment_status | enum | Unpaid, partial, paid, insurance-pending |
| payment_method | enum | Cash, card, insurance, mobile money |
| date_issued | date | |

---

## 17. Allergy Record (standalone/structured)

**Purpose:** Many systems break this out from medical history so it can trigger
prescribing alerts.

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| allergen | str | Drug, food, environmental |
| reaction_type | str | Rash, anaphylaxis, etc. |
| severity | enum | Mild, moderate, severe |
| date_identified | date | |
| status | enum | Active, resolved, unconfirmed |

---

## 18. Provider / Staff Profile Form

**Purpose:** Master record for each clinician/staff member — needed once you have more
than one provider.

| Field | Type | Notes |
|---|---|---|
| staff_id | str (auto) | |
| full_name | str | |
| role | enum | Physician, nurse, receptionist, lab tech, admin, etc. |
| specialty | str | e.g., general practice, pediatrics, dermatology |
| license_number | str | Medical license / registration number |
| license_expiry | date | For compliance tracking |
| phone | str | |
| email | str | |
| department | str (FK) | See Form 20 if you model departments separately |
| employment_status | enum | Full-time, part-time, visiting/locum |
| digital_signature | file | For signing notes/prescriptions |
| active | bool | For deactivating without deleting history |

---

## 19. Provider Availability / Schedule Template

**Purpose:** Drives the booking calendar; each provider has their own working hours and
exceptions.

| Field | Type | Notes |
|---|---|---|
| schedule_id | str (auto) | |
| provider_id | str (FK) | |
| day_of_week | enum | Mon–Sun, for recurring weekly templates |
| start_time | time | |
| end_time | time | |
| slot_duration_minutes | int | e.g., 15/20/30-min slots |
| location_room | str | Useful if clinic has multiple rooms/branches |
| effective_from | date | |
| effective_to | date | Optional, for temporary schedules |
| exceptions | table | date, reason (leave, holiday, conference) — overrides the recurring template |

---

## 20. User Roles & Permissions Matrix

**Purpose:** Not a patient-facing form, but essential to design before you build anything
else — determines who can see/edit what.

| Role | Patient Records | Vitals/Notes | Prescriptions | Lab Orders | Billing | Admin/Users |
|---|---|---|---|---|---|---|
| Physician | Read/Write (own patients, or all per policy) | Read/Write | Write | Write | Read | — |
| Nurse | Read/Write (limited) | Write (vitals), Read (notes) | Read | Read | — | — |
| Receptionist | Read/Write (demographics only) | — | — | — | Read/Write | — |
| Lab Tech | Read (relevant orders only) | — | — | Write (results) | — | — |
| Billing Staff | Read (demographics/insurance) | — | — | — | Read/Write | — |
| Admin | Configurable | Configurable | Configurable | Configurable | Read/Write | Full |

Adjust rows/columns to your clinic's actual policy — this table is meant as a starting
scaffold for your access-control design, not a fixed standard. Most systems implement
this as role-based access control (RBAC), sometimes combined with a rule like
"providers can only fully edit records for patients under their own care."

---

## 21. Internal Referral / Consult Request (Provider-to-Provider)

**Purpose:** Distinct from the external referral form (Form 12) — this is for routing a
patient between providers *within* the same clinic, e.g., GP to in-house specialist.

| Field | Type | Notes |
|---|---|---|
| patient_id | str (FK) | |
| encounter_id | str (FK) | The visit that triggered the referral |
| referring_provider | str (FK to staff) | |
| receiving_provider | str (FK to staff) | Or receiving_department if not assigned yet |
| reason | text | |
| priority | enum | Routine, urgent |
| status | enum | Pending, accepted, scheduled, completed, declined |
| notes | text | |
| date_requested | datetime | |
| date_completed | datetime | Optional |

---

## Data Model Notes for Your Web App

- **Core entities:** `Patient`, `Staff/Provider`, `Encounter/Visit`, then most forms above
  attach either to `Patient` (persistent, e.g., allergies, history) or to `Encounter`
  (per-visit, e.g., vitals, SOAP note, prescriptions).
- **Encounter** is usually the central join table: one row per visit, linking patient,
  provider, date, and all associated forms for that visit.
- **Audit trail:** clinical records typically need `created_by`, `created_at`,
  `updated_by`, `updated_at`, and often an immutable log (notes get "signed" and locked,
  amendments are addenda rather than edits).
- **Consent/signature forms** should store either a signed file/image or a cryptographic
  e-signature record, plus the exact version of the text presented — this matters for
  legal defensibility.
- **Coding standards** worth integrating if you want interoperability: ICD-10 (diagnoses),
  LOINC (lab tests), SNOMED CT (clinical terms), RxNorm/local drug codes (medications).
- **Compliance:** which privacy/security framework applies (HIPAA, GDPR, or a local
  health-data law) depends on where your clinic operates — worth confirming before you
  finalize the data-retention and consent fields.

---

## Suggested Build Order (Multi-Provider Clinic)

1. **Staff/provider profiles + roles & permissions (Forms 18, 20)** — build this first;
   retrofitting RBAC after patient data exists is painful.
2. Patient registration + core patient table
3. Provider availability/schedule (Form 19) + appointment scheduling (Form 15)
4. Encounter (visit) record — the entity that ties patient + provider + date together
5. Vitals + SOAP note (the daily-use core, always tagged to `provider_id`)
6. Prescriptions, lab/imaging orders (each tagged to ordering provider)
7. Consent forms (needed before any real patient data is entered in production)
8. Internal referrals (Form 21) — matters as soon as you have more than one provider
9. Billing
10. External referrals, discharge summaries, immunization records (as needed by your
    clinic type)

### Multi-provider specific considerations
- Every clinical form above should carry a `provider_id` (who wrote/ordered it), not
  just a generic "created_by" — this is what makes multi-provider auditing work.
- Decide early whether patients are "owned" by one primary provider or can be seen by
  any provider in the clinic — this affects both your access rules and your UI (e.g.,
  a shared queue vs. per-provider patient lists).
- If providers work across multiple rooms/branches, add a `location_id` to Encounter
  and Schedule (Form 19) rather than bolting it on later.
