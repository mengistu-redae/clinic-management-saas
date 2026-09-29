import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  useAppointment,
  usePatient,
  useEncounter,
  useUpsertEncounter,
  useReplacePrescriptions,
  useSignEncounter,
  useAddAddendum,
} from '../../api/queries.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import PatientChart from '../../components/PatientChart.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import VisitSummaryLink from '../../components/VisitSummaryLink.jsx';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/** Only reachable once a provider has actually started seeing the patient - matches EncounterService.requireDocumentableStatus exactly, checked client-side too so the common case never round-trips a 409. */
const DOCUMENTABLE_STATUSES = new Set(['with_provider', 'checked_out']);

/** Phase 11 - matches EncounterService.VALID_ROUTES exactly; "other" is the escape hatch for a route this list doesn't name. */
const ROUTES = ['oral', 'iv', 'im', 'subcutaneous', 'topical', 'inhaled', 'rectal', 'sublingual', 'other'];
/** Phase 11 - matches EncounterService.VALID_PRESCRIPTION_STATUSES exactly. */
const PRESCRIPTION_STATUSES = ['active', 'completed', 'discontinued'];

/** Phase 25 - the fixed 9-system review-of-systems checklist, same order as the backend columns/PDF. */
const BODY_SYSTEMS = [
  'generalAppearance', 'heent', 'cardiovascular', 'respiratory', 'abdominal',
  'musculoskeletal', 'neurological', 'skin', 'psychiatric',
];

function emptyExamForm() {
  return Object.fromEntries(BODY_SYSTEMS.flatMap((s) => [[`${s}Normal`, ''], [`${s}Note`, '']]));
}

const emptyLine = () => ({
  medicationName: '', dosage: '', instructions: '',
  route: '', frequency: '', duration: '', quantityDispensed: '', refillsAllowed: '', status: 'active',
});

/**
 * Provider clinical documentation - GET/POST /api/appointments/{id}/encounter
 * (chief complaint/assessment/plan, upserted - never a duplicate row, see
 * EncounterService.upsert) plus a full-replace prescription list (POST
 * .../encounter/prescriptions). Reached from the provider dashboard's
 * "Today's Schedule" rows, not a standalone nav link - same relationship as
 * front-desk's AppointmentDetail to its own dashboard, or (for clinic_admin,
 * who hits the same backend endpoints with no ownership check) from a
 * "Document encounter" link on the shared front-desk/AppointmentDetail.jsx
 * page - this route's own RequireRole gate in App.jsx allows both roles.
 */
export default function Encounter() {
  const { t } = useTranslation();
  const { id } = useParams();
  const { hasRole } = useAuth();
  const appointmentQuery = useAppointment(id);
  const appointment = appointmentQuery.data;

  const patientQuery = usePatient(appointment?.patientId);
  const encounterQuery = useEncounter(id);
  const encounterNotFound = encounterQuery.isError && encounterQuery.error?.status === 404;
  const encounterLoadError = encounterQuery.isError && !encounterNotFound;

  const upsertEncounter = useUpsertEncounter(id);
  const replacePrescriptions = useReplacePrescriptions(id);
  const signEncounter = useSignEncounter(id);
  const addAddendum = useAddAddendum(id);

  const [form, setForm] = useState({ chiefComplaint: '', assessment: '', plan: '', icd10Codes: '', ...emptyExamForm() });
  const [formError, setFormError] = useState(null);
  const [saved, setSaved] = useState(false);

  const [lines, setLines] = useState([emptyLine()]);
  const [rxError, setRxError] = useState(null);
  const [rxSaved, setRxSaved] = useState(false);

  const [signError, setSignError] = useState(null);
  const [addendumText, setAddendumText] = useState('');
  const [addendumError, setAddendumError] = useState(null);

  // Lazy-init from the server once loaded, same one-shot pattern as every
  // other edit form in this app (e.g. clinic-admin/Settings.jsx).
  useEffect(() => {
    if (encounterQuery.data) {
      const e = encounterQuery.data.encounter;
      const examFields = Object.fromEntries(BODY_SYSTEMS.flatMap((s) => [
        [`${s}Normal`, e[`${s}Normal`] === true ? 'true' : e[`${s}Normal`] === false ? 'false' : ''],
        [`${s}Note`, e[`${s}Note`] || ''],
      ]));
      setForm({ chiefComplaint: e.chiefComplaint || '', assessment: e.assessment || '', plan: e.plan || '', icd10Codes: e.icd10Codes || '', ...examFields });
      const rx = encounterQuery.data.prescriptions;
      setLines(rx.length > 0 ? rx.map((p) => ({
        medicationName: p.medicationName, dosage: p.dosage || '', instructions: p.instructions || '',
        route: p.route || '', frequency: p.frequency || '', duration: p.duration || '',
        quantityDispensed: p.quantityDispensed ?? '', refillsAllowed: p.refillsAllowed ?? '', status: p.status || 'active',
      })) : [emptyLine()]);
    }
  }, [encounterQuery.data]);

  async function handleSaveEncounter(event) {
    event.preventDefault();
    setFormError(null);
    setSaved(false);
    try {
      const examFields = Object.fromEntries(BODY_SYSTEMS.flatMap((s) => [
        [`${s}Normal`, form[`${s}Normal`] === '' ? null : form[`${s}Normal`] === 'true'],
        [`${s}Note`, form[`${s}Note`].trim() || null],
      ]));
      await upsertEncounter.mutateAsync({
        chiefComplaint: form.chiefComplaint.trim() || null,
        assessment: form.assessment.trim() || null,
        plan: form.plan.trim() || null,
        icd10Codes: form.icd10Codes.trim() || null,
        ...examFields,
      });
      setSaved(true);
    } catch (err) {
      setFormError(err.message || t('encounterPage.errorSaveEncounter'));
    }
  }

  function updateLine(index, field, value) {
    setLines((ls) => ls.map((l, i) => (i === index ? { ...l, [field]: value } : l)));
    setRxSaved(false);
  }
  function addLine() {
    setLines((ls) => [...ls, emptyLine()]);
  }
  function removeLine(index) {
    setLines((ls) => ls.filter((_, i) => i !== index));
  }

  async function handleSavePrescriptions(event) {
    event.preventDefault();
    setRxError(null);
    setRxSaved(false);
    const items = lines
      .map((l) => ({
        medicationName: l.medicationName.trim(),
        dosage: l.dosage.trim() || undefined,
        instructions: l.instructions.trim() || undefined,
        route: l.route || undefined,
        frequency: l.frequency.trim() || undefined,
        duration: l.duration.trim() || undefined,
        quantityDispensed: l.quantityDispensed === '' ? undefined : Number(l.quantityDispensed),
        refillsAllowed: l.refillsAllowed === '' ? undefined : Number(l.refillsAllowed),
        status: l.status || undefined,
      }))
      .filter((l) => l.medicationName);
    try {
      await replacePrescriptions.mutateAsync(items);
      setRxSaved(true);
    } catch (err) {
      setRxError(err.message || t('encounterPage.errorSavePrescriptions'));
    }
  }

  async function handleSign() {
    setSignError(null);
    try {
      await signEncounter.mutateAsync();
    } catch (err) {
      setSignError(err.message || t('encounterPage.errorSign'));
    }
  }

  async function handleAddAddendum(event) {
    event.preventDefault();
    setAddendumError(null);
    if (!addendumText.trim()) {
      setAddendumError(t('encounterPage.errorAddendumRequired'));
      return;
    }
    try {
      await addAddendum.mutateAsync({ text: addendumText.trim() });
      setAddendumText('');
    } catch (err) {
      setAddendumError(err.message || t('encounterPage.errorAddAddendum'));
    }
  }

  if (appointmentQuery.isLoading) {
    return <Skeleton className="h-64 w-full max-w-2xl" />;
  }
  if (appointmentQuery.isError) {
    return <ErrorBanner message={appointmentQuery.error?.message} onRetry={appointmentQuery.refetch} />;
  }

  const patientName = appointment.patientId
    ? patientQuery.data
      ? `${patientQuery.data.firstName} ${patientQuery.data.lastName}`
      : '…'
    : appointment.contactName || t('frontDeskAppointments.guest');
  const documentable = DOCUMENTABLE_STATUSES.has(appointment.status);
  const encounterExists = Boolean(encounterQuery.data);
  const signedAt = encounterQuery.data?.encounter?.signedAt;
  const signed = Boolean(signedAt);
  const addenda = encounterQuery.data?.addenda || [];

  return (
    <PageContainer>
      <div className="mb-1 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">{t('encounterPage.title')}</h1>
        <StatusPill status={appointment.status} />
      </div>
      <p className="mb-4 text-sm text-ink-muted">
        {patientName} · <span className="font-mono">{appointment.appointmentRef}</span>
      </p>

      <VisitSummaryLink href={`/api/appointments/${id}/visit-summary/pdf`} />

      <PatientChart patientId={appointment.patientId} appointmentId={id} />

      {!documentable ? (
        <EmptyState
          title={t('encounterPage.notReadyTitle')}
          description={t('encounterPage.notReadyDescription', { status: appointment.status.replace(/_/g, ' ') })}
          action={
            <Link
              to={hasRole('clinic_admin') ? `/front-desk/appointments/${id}` : '/provider'}
              className="text-sm font-medium text-brand-text hover:underline"
            >
              {hasRole('clinic_admin') ? t('encounterPage.backToAppointment') : t('encounterPage.backToSchedule')}
            </Link>
          }
        />
      ) : (
        <>
          <form onSubmit={handleSaveEncounter} className="flex flex-col gap-4 rounded-xl border border-slate-200 bg-surface p-4">
            <div className="flex items-center justify-between">
              <p className="text-sm font-semibold text-ink">{t('encounterPage.note')}</p>
              {signed && (
                <span className="inline-flex items-center rounded-full bg-success-light px-2.5 py-0.5 text-xs font-semibold text-success">
                  {t('encounterPage.signedAt', { date: new Date(signedAt).toLocaleString() })}
                </span>
              )}
            </div>
            {encounterQuery.isLoading && <Skeleton className="h-40 w-full" />}
            {encounterLoadError && <ErrorBanner message={encounterQuery.error?.message} onRetry={encounterQuery.refetch} />}
            {!encounterQuery.isLoading && (
              <>
                <Field label={t('encounterPage.chiefComplaint')}>
                  <textarea rows={2} disabled={signed} value={form.chiefComplaint} onChange={(e) => { setForm({ ...form, chiefComplaint: e.target.value }); setSaved(false); }} className={`${inputClass} disabled:bg-slate-50 disabled:text-ink-muted`} />
                </Field>
                <Field label={t('encounterPage.assessment')}>
                  <textarea rows={3} disabled={signed} value={form.assessment} onChange={(e) => { setForm({ ...form, assessment: e.target.value }); setSaved(false); }} className={`${inputClass} disabled:bg-slate-50 disabled:text-ink-muted`} />
                </Field>
                <Field label={t('encounterPage.plan')}>
                  <textarea rows={3} disabled={signed} value={form.plan} onChange={(e) => { setForm({ ...form, plan: e.target.value }); setSaved(false); }} className={`${inputClass} disabled:bg-slate-50 disabled:text-ink-muted`} />
                </Field>
                <Field label={t('encounterPage.icd10Optional')}>
                  <input disabled={signed} value={form.icd10Codes} onChange={(e) => { setForm({ ...form, icd10Codes: e.target.value }); setSaved(false); }} placeholder="J20.9, R05" className={`${inputClass} disabled:bg-slate-50 disabled:text-ink-muted`} />
                </Field>

                <p className="mt-2 text-sm font-semibold text-ink">{t('encounterPage.physicalExam')}</p>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  {BODY_SYSTEMS.map((s) => (
                    <div key={s} className="flex flex-col gap-2 rounded-lg border border-slate-100 p-3">
                      <div className="flex items-center justify-between gap-3">
                        <span className="text-sm font-medium text-ink">{t(`encounterPage.${s}`)}</span>
                        <select
                          disabled={signed}
                          value={form[`${s}Normal`]}
                          onChange={(e) => { setForm({ ...form, [`${s}Normal`]: e.target.value }); setSaved(false); }}
                          className={`${inputClass} w-32 disabled:bg-slate-50 disabled:text-ink-muted`}
                        >
                          <option value="">{t('encounterPage.notExamined')}</option>
                          <option value="true">{t('encounterPage.normal')}</option>
                          <option value="false">{t('encounterPage.abnormal')}</option>
                        </select>
                      </div>
                      <textarea
                        rows={1}
                        disabled={signed}
                        value={form[`${s}Note`]}
                        onChange={(e) => { setForm({ ...form, [`${s}Note`]: e.target.value }); setSaved(false); }}
                        className={`${inputClass} disabled:bg-slate-50 disabled:text-ink-muted`}
                      />
                    </div>
                  ))}
                </div>

                {formError && <ErrorBanner message={formError} />}
                {!signed && (
                  <div className="flex flex-wrap items-center gap-3">
                    <button
                      type="submit"
                      disabled={upsertEncounter.isPending}
                      className="self-start rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {upsertEncounter.isPending ? t('settingsPage.saving') : encounterExists ? t('encounterPage.updateNote') : t('encounterPage.saveNote')}
                    </button>
                    {saved && <span className="text-sm text-success">{t('settingsPage.saved')}</span>}
                    {encounterExists && (
                      <button
                        type="button"
                        disabled={signEncounter.isPending}
                        onClick={handleSign}
                        className="self-start rounded-lg border border-brand px-4 py-2 text-sm font-semibold text-brand-text hover:bg-brand-light disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        {signEncounter.isPending ? t('encounterPage.signing') : t('encounterPage.signEncounterBtn')}
                      </button>
                    )}
                  </div>
                )}
                {signError && <ErrorBanner message={signError} />}
              </>
            )}
          </form>

          {signed && (
            <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-4">
              <p className="mb-3 text-sm font-semibold text-ink">{t('encounterPage.addenda')}</p>
              {addenda.length === 0 && <p className="mb-3 text-sm text-ink-muted">{t('encounterPage.noAddenda')}</p>}
              {addenda.length > 0 && (
                <ul className="mb-4 flex flex-col gap-3">
                  {addenda.map((a) => (
                    <li key={a.id} className="rounded-lg border border-slate-100 px-3 py-2">
                      <p className="text-sm text-ink">{a.text}</p>
                      <p className="mt-1 text-xs text-ink-muted">{new Date(a.createdAt).toLocaleString()}</p>
                    </li>
                  ))}
                </ul>
              )}
              <form onSubmit={handleAddAddendum} className="flex flex-col gap-3">
                <Field label={t('encounterPage.addAnAddendum')}>
                  <textarea rows={2} value={addendumText} onChange={(e) => setAddendumText(e.target.value)} className={inputClass} />
                </Field>
                {addendumError && <ErrorBanner message={addendumError} />}
                <button
                  type="submit"
                  disabled={addAddendum.isPending}
                  className="self-start rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {addAddendum.isPending ? t('common.adding') : t('encounterPage.addAddendum')}
                </button>
              </form>
            </div>
          )}

          <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-4">
            <p className="mb-3 text-sm font-semibold text-ink">{t('encounterPage.prescriptions')}</p>
            {encounterQuery.isLoading ? (
              <Skeleton className="h-24 w-full" />
            ) : !encounterExists ? (
              <p className="text-sm text-ink-muted">{t('encounterPage.saveNoteFirst')}</p>
            ) : (
              <form onSubmit={handleSavePrescriptions} className="flex flex-col gap-4">
                {lines.map((line, i) => (
                  <div key={i} className="flex flex-col gap-3 border-b border-slate-100 pb-3 last:border-b-0 last:pb-0">
                    <div className="flex flex-wrap items-end gap-3">
                      <Field label={t('encounterPage.medication')}>
                        <input disabled={signed} value={line.medicationName} onChange={(e) => updateLine(i, 'medicationName', e.target.value)} className={`${inputClass} w-48 disabled:bg-slate-50 disabled:text-ink-muted`} />
                      </Field>
                      <Field label={t('encounterPage.dosage')}>
                        <input disabled={signed} value={line.dosage} onChange={(e) => updateLine(i, 'dosage', e.target.value)} placeholder="500mg twice daily" className={`${inputClass} w-48 disabled:bg-slate-50 disabled:text-ink-muted`} />
                      </Field>
                      <Field label={t('encounterPage.instructions')}>
                        <input disabled={signed} value={line.instructions} onChange={(e) => updateLine(i, 'instructions', e.target.value)} className={`${inputClass} w-56 disabled:bg-slate-50 disabled:text-ink-muted`} />
                      </Field>
                      {!signed && (
                        <button type="button" onClick={() => removeLine(i)} className="text-sm text-danger hover:underline">
                          {t('common.delete')}
                        </button>
                      )}
                    </div>
                    <div className="flex flex-wrap items-end gap-3">
                      <Field label={t('encounterPage.route')}>
                        <select disabled={signed} value={line.route} onChange={(e) => updateLine(i, 'route', e.target.value)} className={`${inputClass} disabled:bg-slate-50 disabled:text-ink-muted`}>
                          <option value="">—</option>
                          {ROUTES.map((r) => <option key={r} value={r}>{t(`routeLabel.${r}`)}</option>)}
                        </select>
                      </Field>
                      <Field label={t('encounterPage.frequency')}>
                        <input disabled={signed} value={line.frequency} onChange={(e) => updateLine(i, 'frequency', e.target.value)} placeholder="twice daily" className={`${inputClass} w-36 disabled:bg-slate-50 disabled:text-ink-muted`} />
                      </Field>
                      <Field label={t('encounterPage.duration')}>
                        <input disabled={signed} value={line.duration} onChange={(e) => updateLine(i, 'duration', e.target.value)} placeholder="7 days" className={`${inputClass} w-32 disabled:bg-slate-50 disabled:text-ink-muted`} />
                      </Field>
                      <Field label={t('encounterPage.quantity')}>
                        <input type="number" min="0" disabled={signed} value={line.quantityDispensed} onChange={(e) => updateLine(i, 'quantityDispensed', e.target.value)} className={`${inputClass} w-24 disabled:bg-slate-50 disabled:text-ink-muted`} />
                      </Field>
                      <Field label={t('encounterPage.refills')}>
                        <input type="number" min="0" disabled={signed} value={line.refillsAllowed} onChange={(e) => updateLine(i, 'refillsAllowed', e.target.value)} className={`${inputClass} w-24 disabled:bg-slate-50 disabled:text-ink-muted`} />
                      </Field>
                      <Field label={t('referralsPage.status')}>
                        <select disabled={signed} value={line.status} onChange={(e) => updateLine(i, 'status', e.target.value)} className={`${inputClass} disabled:bg-slate-50 disabled:text-ink-muted`}>
                          {PRESCRIPTION_STATUSES.map((s) => <option key={s} value={s}>{t(`prescriptionStatus.${s}`)}</option>)}
                        </select>
                      </Field>
                    </div>
                  </div>
                ))}
                {!signed && (
                  <button type="button" onClick={addLine} className="self-start text-sm font-semibold text-brand-text hover:underline">
                    {t('encounterPage.addMedication')}
                  </button>
                )}
                {rxError && <ErrorBanner message={rxError} />}
                {!signed && (
                  <div className="flex items-center gap-3">
                    <button
                      type="submit"
                      disabled={replacePrescriptions.isPending}
                      className="self-start rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {replacePrescriptions.isPending ? t('settingsPage.saving') : t('encounterPage.savePrescriptions')}
                    </button>
                    {rxSaved && <span className="text-sm text-success">{t('settingsPage.saved')}</span>}
                  </div>
                )}
              </form>
            )}
          </div>
        </>
      )}
    </PageContainer>
  );
}

function Field({ label, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
      {children}
    </label>
  );
}
