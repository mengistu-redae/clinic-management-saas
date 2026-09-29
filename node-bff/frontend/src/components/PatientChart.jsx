import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useAllergies,
  useCreateAllergy,
  useUpdateAllergy,
  useImmunizations,
  useCreateImmunization,
  useUpdateImmunization,
  useVitals,
  useUpsertVitals,
  useMedicalHistory,
  useUpsertMedicalHistory,
  useConsentRecords,
  useCreateConsentRecord,
} from '../api/queries.js';
import { useAuth } from '../auth/AuthContext.jsx';
import Skeleton from './Skeleton.jsx';
import ErrorBanner from './ErrorBanner.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const SEVERITY_STYLE = { mild: 'bg-slate-100 text-ink-muted', moderate: 'bg-warning-light text-warning', severe: 'bg-danger-light text-danger' };
const ALLERGY_STATUS_STYLE = { active: 'bg-danger-light text-danger', resolved: 'bg-success-light text-success', unconfirmed: 'bg-slate-100 text-ink-muted' };

function Badge({ style, children }) {
  return <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold capitalize ${style}`}>{children}</span>;
}

function Field({ label, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
      {children}
    </label>
  );
}

/**
 * Shared clinical-chart panel - allergies/vitals/medical history (frontend
 * phase H) plus consent records (phase I), the phase 8/9/10 backend
 * modules. Used from both front-desk/AppointmentDetail.jsx and
 * provider/Encounter.jsx, same "share the existing page" pattern the
 * clinic_admin encounter-access fix already established, rather than
 * separate standalone pages. `appointmentId` is optional - when omitted
 * (there's no natural appointment context, e.g. a future patient-detail
 * page) the vitals section is skipped, since Vitals hangs off an
 * appointment, not a patient.
 *
 * Every section here is visible to all three staff roles that can reach
 * either host page (front_desk/provider/clinic_admin) - vitals and medical
 * history are writable by all three (matches their own controllers'
 * gates); allergies and consent both restrict the write form to
 * front_desk/clinic_admin (provider reads but doesn't write either,
 * same as this app's PatientController gate).
 */
export default function PatientChart({ patientId, appointmentId }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(true);

  return (
    <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
      <button type="button" onClick={() => setOpen((o) => !o)} className="flex w-full items-center justify-between text-left">
        <p className="text-sm font-semibold text-ink">{t('patientChart.title')}</p>
        <span className="text-sm text-ink-muted">{open ? t('patientChart.hide') : t('patientChart.show')}</span>
      </button>

      {open && (
        <div className="mt-4 flex flex-col gap-5">
          {patientId && <AllergiesSection patientId={patientId} />}
          {patientId && <ImmunizationsSection patientId={patientId} appointmentId={appointmentId} />}
          {appointmentId && <VitalsSection appointmentId={appointmentId} />}
          {patientId && <MedicalHistorySection patientId={patientId} />}
          {patientId && <ConsentSection patientId={patientId} />}
          {!patientId && !appointmentId && <p className="text-sm text-ink-muted">{t('patientChart.nothingForGuest')}</p>}
        </div>
      )}
    </div>
  );
}

function AllergiesSection({ patientId }) {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canWrite = hasRole('front_desk') || hasRole('clinic_admin');
  const allergiesQuery = useAllergies(patientId);
  const createAllergy = useCreateAllergy(patientId);

  const [form, setForm] = useState({ allergen: '', reactionType: '', severity: 'moderate', identifiedAt: '' });
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.allergen.trim()) {
      setFormError(t('patientChart.errorAllergenRequired'));
      return;
    }
    try {
      await createAllergy.mutateAsync({
        allergen: form.allergen.trim(),
        reactionType: form.reactionType.trim() || undefined,
        severity: form.severity,
        identifiedAt: form.identifiedAt || undefined,
      });
      setForm({ allergen: '', reactionType: '', severity: 'moderate', identifiedAt: '' });
    } catch (err) {
      setFormError(err.message || t('patientChart.errorAddAllergy'));
    }
  }

  const allergies = allergiesQuery.data || [];

  return (
    <div className="border-t border-slate-100 pt-4">
      <p className="mb-3 text-sm font-semibold text-ink">{t('patientChart.allergiesSection')}</p>
      {allergiesQuery.isLoading && <Skeleton className="h-12 w-full" />}
      {allergiesQuery.isError && <ErrorBanner message={allergiesQuery.error?.message} onRetry={allergiesQuery.refetch} />}
      {!allergiesQuery.isLoading && !allergiesQuery.isError && allergies.length === 0 && (
        <p className="text-sm text-ink-muted">{t('patientChart.noAllergies')}</p>
      )}
      {allergies.length > 0 && (
        <ul className="mb-3 flex flex-col gap-2">
          {allergies.map((a) => (
            <AllergyRow key={a.id} allergy={a} patientId={patientId} canWrite={canWrite} />
          ))}
        </ul>
      )}
      {canWrite && (
        <form onSubmit={handleCreate} className="flex flex-wrap items-end gap-3">
          <Field label={t('patientChart.allergen')}>
            <input value={form.allergen} onChange={(e) => setForm({ ...form, allergen: e.target.value })} placeholder="Penicillin" className={`${inputClass} w-40`} />
          </Field>
          <Field label={t('patientChart.reactionOptional')}>
            <input value={form.reactionType} onChange={(e) => setForm({ ...form, reactionType: e.target.value })} placeholder="Rash" className={`${inputClass} w-40`} />
          </Field>
          <Field label={t('patientChart.severity')}>
            <select value={form.severity} onChange={(e) => setForm({ ...form, severity: e.target.value })} className={inputClass}>
              <option value="mild">{t('patientChart.mild')}</option>
              <option value="moderate">{t('patientChart.moderate')}</option>
              <option value="severe">{t('patientChart.severe')}</option>
            </select>
          </Field>
          <Field label={t('patientChart.identifiedOptional')}>
            <input type="date" value={form.identifiedAt} onChange={(e) => setForm({ ...form, identifiedAt: e.target.value })} className={inputClass} />
          </Field>
          <button type="submit" disabled={createAllergy.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
            {createAllergy.isPending ? t('common.adding') : t('patientChart.addAllergy')}
          </button>
        </form>
      )}
      {formError && <div className="mt-3"><ErrorBanner message={formError} /></div>}
    </div>
  );
}

function AllergyRow({ allergy, patientId, canWrite }) {
  const { t } = useTranslation();
  const updateAllergy = useUpdateAllergy(patientId, allergy.id);
  const [rowError, setRowError] = useState(null);

  async function changeStatus(status) {
    setRowError(null);
    try {
      await updateAllergy.mutateAsync({ status });
    } catch (err) {
      setRowError(err.message || t('patientChart.errorUpdateAllergy'));
    }
  }

  return (
    <li className="rounded-lg border border-slate-100 px-3 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <span className="text-sm font-semibold text-ink">{allergy.allergen}</span>
          {allergy.reactionType && <span className="ml-2 text-sm text-ink-muted">{allergy.reactionType}</span>}
        </div>
        <div className="flex items-center gap-2">
          <Badge style={SEVERITY_STYLE[allergy.severity] || SEVERITY_STYLE.moderate}>{t(`patientChart.${allergy.severity}`, { defaultValue: allergy.severity })}</Badge>
          <Badge style={ALLERGY_STATUS_STYLE[allergy.status] || ALLERGY_STATUS_STYLE.active}>{t(`status.${allergy.status}`, { defaultValue: allergy.status })}</Badge>
          {canWrite && allergy.status !== 'resolved' && (
            <button type="button" disabled={updateAllergy.isPending} onClick={() => changeStatus('resolved')} className="text-xs font-medium text-brand-text hover:underline disabled:opacity-50">
              {t('patientChart.markResolved')}
            </button>
          )}
          {canWrite && allergy.status === 'resolved' && (
            <button type="button" disabled={updateAllergy.isPending} onClick={() => changeStatus('active')} className="text-xs font-medium text-brand-text hover:underline disabled:opacity-50">
              {t('common.reactivate')}
            </button>
          )}
        </div>
      </div>
      {allergy.identifiedAt && <p className="mt-1 text-xs text-ink-muted">{t('patientChart.identifiedPrefix', { date: allergy.identifiedAt })}</p>}
      {rowError && <p className="mt-1 text-xs text-danger">{rowError}</p>}
    </li>
  );
}

/**
 * Same accumulating-list shape as Allergies (never full-replace), but
 * writable by all three staff roles - matches ImmunizationController's
 * own gate (provider/front_desk/clinic_admin, an immunization is
 * physically administered the same way vitals are, not intake-recorded
 * data the way allergies are). `appointmentId` is never a form field -
 * when this panel is mounted inside a specific appointment's own context
 * (front-desk detail page, provider encounter page), every immunization
 * added here is naturally "given at this visit," so the create payload
 * links it silently.
 */
function ImmunizationsSection({ patientId, appointmentId }) {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canWrite = hasRole('provider') || hasRole('front_desk') || hasRole('clinic_admin');
  const immunizationsQuery = useImmunizations(patientId);
  const createImmunization = useCreateImmunization(patientId);

  const [form, setForm] = useState({ vaccineName: '', administeredAt: '', doseNumber: '', lotNumber: '', site: '' });
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.vaccineName.trim() || !form.administeredAt) {
      setFormError(t('patientChart.errorImmunizationRequired'));
      return;
    }
    try {
      await createImmunization.mutateAsync({
        vaccineName: form.vaccineName.trim(),
        administeredAt: form.administeredAt,
        doseNumber: form.doseNumber === '' ? undefined : Number(form.doseNumber),
        lotNumber: form.lotNumber.trim() || undefined,
        site: form.site.trim() || undefined,
        appointmentId: appointmentId ?? undefined,
      });
      setForm({ vaccineName: '', administeredAt: '', doseNumber: '', lotNumber: '', site: '' });
    } catch (err) {
      setFormError(err.message || t('patientChart.errorAddImmunization'));
    }
  }

  const immunizations = immunizationsQuery.data || [];

  return (
    <div className="border-t border-slate-100 pt-4">
      <p className="mb-3 text-sm font-semibold text-ink">{t('patientChart.immunizationsSection')}</p>
      {immunizationsQuery.isLoading && <Skeleton className="h-12 w-full" />}
      {immunizationsQuery.isError && <ErrorBanner message={immunizationsQuery.error?.message} onRetry={immunizationsQuery.refetch} />}
      {!immunizationsQuery.isLoading && !immunizationsQuery.isError && immunizations.length === 0 && (
        <p className="text-sm text-ink-muted">{t('patientChart.noImmunizations')}</p>
      )}
      {immunizations.length > 0 && (
        <ul className="mb-3 flex flex-col gap-2">
          {immunizations.map((i) => (
            <ImmunizationRow key={i.id} immunization={i} patientId={patientId} canWrite={canWrite} />
          ))}
        </ul>
      )}
      {canWrite && (
        <form onSubmit={handleCreate} className="flex flex-wrap items-end gap-3">
          <Field label={t('patientChart.vaccineName')}>
            <input value={form.vaccineName} onChange={(e) => setForm({ ...form, vaccineName: e.target.value })} placeholder="Influenza" className={`${inputClass} w-36`} />
          </Field>
          <Field label={t('patientChart.administeredAt')}>
            <input type="date" value={form.administeredAt} onChange={(e) => setForm({ ...form, administeredAt: e.target.value })} className={inputClass} />
          </Field>
          <Field label={t('patientChart.doseNumberOptional')}>
            <input type="number" min="1" value={form.doseNumber} onChange={(e) => setForm({ ...form, doseNumber: e.target.value })} className={`${inputClass} w-20`} />
          </Field>
          <Field label={t('patientChart.lotNumberOptional')}>
            <input value={form.lotNumber} onChange={(e) => setForm({ ...form, lotNumber: e.target.value })} className={`${inputClass} w-28`} />
          </Field>
          <Field label={t('patientChart.siteOptional')}>
            <input value={form.site} onChange={(e) => setForm({ ...form, site: e.target.value })} placeholder="left deltoid" className={`${inputClass} w-32`} />
          </Field>
          <button type="submit" disabled={createImmunization.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
            {createImmunization.isPending ? t('common.adding') : t('patientChart.addImmunization')}
          </button>
        </form>
      )}
      {formError && <div className="mt-3"><ErrorBanner message={formError} /></div>}
    </div>
  );
}

function ImmunizationRow({ immunization, patientId, canWrite }) {
  const { t } = useTranslation();
  const updateImmunization = useUpdateImmunization(patientId, immunization.id);
  const [editing, setEditing] = useState(false);
  const [rowError, setRowError] = useState(null);
  const [editForm, setEditForm] = useState({
    doseNumber: immunization.doseNumber ?? '',
    lotNumber: immunization.lotNumber || '',
    site: immunization.site || '',
  });

  async function handleSave(event) {
    event.preventDefault();
    setRowError(null);
    try {
      await updateImmunization.mutateAsync({
        doseNumber: editForm.doseNumber === '' ? undefined : Number(editForm.doseNumber),
        lotNumber: editForm.lotNumber.trim() || undefined,
        site: editForm.site.trim() || undefined,
      });
      setEditing(false);
    } catch (err) {
      setRowError(err.message || t('patientChart.errorUpdateImmunization'));
    }
  }

  return (
    <li className="rounded-lg border border-slate-100 px-3 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <span className="text-sm font-semibold text-ink">{immunization.vaccineName}</span>
          <span className="ml-2 text-sm text-ink-muted">{immunization.administeredAt}</span>
        </div>
        {canWrite && !editing && (
          <button type="button" onClick={() => setEditing(true)} className="text-xs font-medium text-brand-text hover:underline">
            {t('common.edit')}
          </button>
        )}
      </div>
      {!editing && (
        <p className="mt-1 text-xs text-ink-muted">
          {[
            immunization.doseNumber != null && t('patientChart.dosePrefix', { dose: immunization.doseNumber }),
            immunization.lotNumber && t('patientChart.lotPrefix', { lot: immunization.lotNumber }),
            immunization.site,
          ].filter(Boolean).join(' · ') || '—'}
        </p>
      )}
      {editing && (
        <form onSubmit={handleSave} className="mt-2 flex flex-wrap items-end gap-3">
          <Field label={t('patientChart.doseNumberOptional')}>
            <input type="number" min="1" value={editForm.doseNumber} onChange={(e) => setEditForm({ ...editForm, doseNumber: e.target.value })} className={`${inputClass} w-20`} />
          </Field>
          <Field label={t('patientChart.lotNumberOptional')}>
            <input value={editForm.lotNumber} onChange={(e) => setEditForm({ ...editForm, lotNumber: e.target.value })} className={`${inputClass} w-28`} />
          </Field>
          <Field label={t('patientChart.siteOptional')}>
            <input value={editForm.site} onChange={(e) => setEditForm({ ...editForm, site: e.target.value })} className={`${inputClass} w-32`} />
          </Field>
          <button type="submit" disabled={updateImmunization.isPending} className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
            {t('common.save')}
          </button>
          <button type="button" onClick={() => setEditing(false)} className="text-xs font-medium text-ink-muted hover:underline">
            {t('common.cancel')}
          </button>
        </form>
      )}
      {rowError && <p className="mt-1 text-xs text-danger">{rowError}</p>}
    </li>
  );
}

const VITALS_FIELDS = [
  { key: 'heightCm', labelKey: 'heightCm' },
  { key: 'weightKg', labelKey: 'weightKg' },
  { key: 'temperatureC', labelKey: 'tempC' },
  { key: 'pulseBpm', labelKey: 'pulseBpm' },
  { key: 'respiratoryRate', labelKey: 'respRate' },
  { key: 'bloodPressureSystolic', labelKey: 'bpSystolic' },
  { key: 'bloodPressureDiastolic', labelKey: 'bpDiastolic' },
  { key: 'oxygenSaturationPct', labelKey: 'o2Sat' },
  { key: 'painScore', labelKey: 'painScore' },
];

function emptyVitalsForm() {
  return Object.fromEntries(VITALS_FIELDS.map((f) => [f.key, '']));
}

/** Full-replace form, same "lazy-init from server once loaded" pattern as provider/Encounter.jsx's own note form. */
function VitalsSection({ appointmentId }) {
  const { t } = useTranslation();
  const vitalsQuery = useVitals(appointmentId);
  const upsertVitals = useUpsertVitals(appointmentId);
  const notFound = vitalsQuery.isError && vitalsQuery.error?.status === 404;
  const loadError = vitalsQuery.isError && !notFound;

  const [form, setForm] = useState(emptyVitalsForm());
  const [saved, setSaved] = useState(false);
  const [formError, setFormError] = useState(null);

  useEffect(() => {
    if (vitalsQuery.data) {
      const v = vitalsQuery.data;
      setForm(Object.fromEntries(VITALS_FIELDS.map((f) => [f.key, v[f.key] ?? ''])));
    }
  }, [vitalsQuery.data]);

  async function handleSave(event) {
    event.preventDefault();
    setFormError(null);
    setSaved(false);
    const body = Object.fromEntries(VITALS_FIELDS.map((f) => [f.key, form[f.key] === '' ? null : Number(form[f.key])]));
    try {
      await upsertVitals.mutateAsync(body);
      setSaved(true);
    } catch (err) {
      setFormError(err.message || t('patientChart.errorSaveVitals'));
    }
  }

  const bmi = vitalsQuery.data?.bmi;

  return (
    <div className="border-t border-slate-100 pt-4">
      <p className="mb-3 text-sm font-semibold text-ink">{t('patientChart.vitalsSection')}</p>
      {vitalsQuery.isLoading && <Skeleton className="h-24 w-full" />}
      {loadError && <ErrorBanner message={vitalsQuery.error?.message} onRetry={vitalsQuery.refetch} />}
      {!vitalsQuery.isLoading && (
        <form onSubmit={handleSave} className="flex flex-col gap-3">
          <div className="flex flex-wrap gap-3">
            {VITALS_FIELDS.map((f) => (
              <Field key={f.key} label={t(`patientChart.${f.labelKey}`)}>
                <input
                  type="number"
                  step="any"
                  value={form[f.key]}
                  onChange={(e) => { setForm({ ...form, [f.key]: e.target.value }); setSaved(false); }}
                  className={`${inputClass} w-28`}
                />
              </Field>
            ))}
            <Field label={t('patientChart.bmi')}>
              <div className={`${inputClass} w-24 border-dashed bg-slate-50 text-ink-muted`}>{bmi ?? '—'}</div>
            </Field>
          </div>
          {formError && <ErrorBanner message={formError} />}
          <div className="flex items-center gap-3">
            <button type="submit" disabled={upsertVitals.isPending} className="self-start rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
              {upsertVitals.isPending ? t('settingsPage.saving') : vitalsQuery.data ? t('patientChart.updateVitals') : t('patientChart.saveVitals')}
            </button>
            {saved && <span className="text-sm text-success">{t('settingsPage.saved')}</span>}
          </div>
        </form>
      )}
    </div>
  );
}

const HISTORY_FIELDS = [
  { key: 'pastConditions', labelKey: 'pastConditions' },
  { key: 'pastSurgeries', labelKey: 'pastSurgeries' },
  { key: 'currentMedications', labelKey: 'currentMedications' },
  { key: 'familyHistory', labelKey: 'familyHistory' },
  { key: 'socialHistory', labelKey: 'socialHistory' },
];

function emptyHistoryForm() {
  return Object.fromEntries(HISTORY_FIELDS.map((f) => [f.key, '']));
}

/** Single evolving row per patient, full-replace on every save - same shape as VitalsSection above. */
function MedicalHistorySection({ patientId }) {
  const { t } = useTranslation();
  const historyQuery = useMedicalHistory(patientId);
  const upsertHistory = useUpsertMedicalHistory(patientId);
  const notFound = historyQuery.isError && historyQuery.error?.status === 404;
  const loadError = historyQuery.isError && !notFound;

  const [form, setForm] = useState(emptyHistoryForm());
  const [saved, setSaved] = useState(false);
  const [formError, setFormError] = useState(null);

  useEffect(() => {
    if (historyQuery.data) {
      const h = historyQuery.data;
      setForm(Object.fromEntries(HISTORY_FIELDS.map((f) => [f.key, h[f.key] || ''])));
    }
  }, [historyQuery.data]);

  async function handleSave(event) {
    event.preventDefault();
    setFormError(null);
    setSaved(false);
    const body = Object.fromEntries(HISTORY_FIELDS.map((f) => [f.key, form[f.key].trim() || null]));
    try {
      await upsertHistory.mutateAsync(body);
      setSaved(true);
    } catch (err) {
      setFormError(err.message || t('patientChart.errorSaveHistory'));
    }
  }

  return (
    <div className="border-t border-slate-100 pt-4">
      <p className="mb-3 text-sm font-semibold text-ink">{t('patientChart.historySection')}</p>
      {historyQuery.isLoading && <Skeleton className="h-24 w-full" />}
      {loadError && <ErrorBanner message={historyQuery.error?.message} onRetry={historyQuery.refetch} />}
      {!historyQuery.isLoading && (
        <form onSubmit={handleSave} className="flex flex-col gap-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {HISTORY_FIELDS.map((f) => (
              <Field key={f.key} label={t(`patientChart.${f.labelKey}`)}>
                <textarea
                  rows={2}
                  value={form[f.key]}
                  onChange={(e) => { setForm({ ...form, [f.key]: e.target.value }); setSaved(false); }}
                  className={`${inputClass} w-full`}
                />
              </Field>
            ))}
          </div>
          {formError && <ErrorBanner message={formError} />}
          <div className="flex items-center gap-3">
            <button type="submit" disabled={upsertHistory.isPending} className="self-start rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
              {upsertHistory.isPending ? t('settingsPage.saving') : historyQuery.data ? t('patientChart.updateHistory') : t('patientChart.saveHistory')}
            </button>
            {saved && <span className="text-sm text-success">{t('settingsPage.saved')}</span>}
          </div>
        </form>
      )}
    </div>
  );
}

function emptyConsentForm() {
  return { consentType: 'general_treatment', policyVersion: '', consentGiven: true, witnessName: '', languagePresented: '', dataSharingPreferences: '' };
}

/**
 * Accumulates, never replaces - ConsentController has no update/delete
 * endpoint at all (see ConsentRecord's own javadoc), so this section is a
 * pure list + create form, never an edit form the way Vitals/MedicalHistory
 * are. Write gate matches Allergies exactly (front_desk/clinic_admin only).
 */
function ConsentSection({ patientId }) {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canWrite = hasRole('front_desk') || hasRole('clinic_admin');
  const consentQuery = useConsentRecords(patientId);
  const createConsent = useCreateConsentRecord(patientId);
  const CONSENT_TYPE_LABEL = { general_treatment: t('patientChart.generalTreatment'), privacy_data: t('patientChart.privacyData') };

  const [form, setForm] = useState(emptyConsentForm());
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.policyVersion.trim()) {
      setFormError(t('patientChart.errorPolicyRequired'));
      return;
    }
    try {
      await createConsent.mutateAsync({
        consentType: form.consentType,
        policyVersion: form.policyVersion.trim(),
        consentGiven: form.consentGiven,
        witnessName: form.witnessName.trim() || undefined,
        languagePresented: form.languagePresented.trim() || undefined,
        dataSharingPreferences: form.dataSharingPreferences.trim() || undefined,
      });
      setForm(emptyConsentForm());
    } catch (err) {
      setFormError(err.message || t('patientChart.errorRecordConsent'));
    }
  }

  const records = consentQuery.data || [];

  return (
    <div className="border-t border-slate-100 pt-4">
      <p className="mb-3 text-sm font-semibold text-ink">{t('patientChart.consentSection')}</p>
      {consentQuery.isLoading && <Skeleton className="h-12 w-full" />}
      {consentQuery.isError && <ErrorBanner message={consentQuery.error?.message} onRetry={consentQuery.refetch} />}
      {!consentQuery.isLoading && !consentQuery.isError && records.length === 0 && (
        <p className="text-sm text-ink-muted">{t('patientChart.noConsent')}</p>
      )}
      {records.length > 0 && (
        <ul className="mb-3 flex flex-col gap-2">
          {records.map((r) => (
            <li key={r.id} className="rounded-lg border border-slate-100 px-3 py-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-sm font-semibold text-ink">{CONSENT_TYPE_LABEL[r.consentType] || r.consentType}</span>
                <Badge style={r.consentGiven ? 'bg-success-light text-success' : 'bg-danger-light text-danger'}>
                  {r.consentGiven ? t('patientChart.given') : t('patientChart.declined')}
                </Badge>
              </div>
              <p className="mt-1 text-xs text-ink-muted">
                {t('patientChart.policyPrefix', { version: r.policyVersion })} · {new Date(r.signedAt).toLocaleDateString()}
                {r.witnessName && ` · ${t('patientChart.witnessedByPrefix', { name: r.witnessName })}`}
              </p>
            </li>
          ))}
        </ul>
      )}
      {canWrite && (
        <form onSubmit={handleCreate} className="flex flex-wrap items-end gap-3">
          <Field label={t('patientChart.type')}>
            <select value={form.consentType} onChange={(e) => setForm({ ...form, consentType: e.target.value })} className={inputClass}>
              <option value="general_treatment">{t('patientChart.generalTreatment')}</option>
              <option value="privacy_data">{t('patientChart.privacyData')}</option>
            </select>
          </Field>
          <Field label={t('patientChart.policyVersion')}>
            <input value={form.policyVersion} onChange={(e) => setForm({ ...form, policyVersion: e.target.value })} placeholder="v1" className={`${inputClass} w-24`} />
          </Field>
          <Field label={t('patientChart.witnessOptional')}>
            <input value={form.witnessName} onChange={(e) => setForm({ ...form, witnessName: e.target.value })} className={`${inputClass} w-40`} />
          </Field>
          <label className="flex items-center gap-2 pb-2 text-sm text-ink">
            <input type="checkbox" checked={form.consentGiven} onChange={(e) => setForm({ ...form, consentGiven: e.target.checked })} />
            {t('patientChart.consentGivenLabel')}
          </label>
          <button type="submit" disabled={createConsent.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
            {createConsent.isPending ? t('fdAppointmentDetail.recording') : t('patientChart.recordConsent')}
          </button>
        </form>
      )}
      {formError && <div className="mt-3"><ErrorBanner message={formError} /></div>}
    </div>
  );
}
