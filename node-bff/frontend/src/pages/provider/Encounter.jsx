import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  useAppointment,
  usePatient,
  useEncounter,
  useUpsertEncounter,
  useReplacePrescriptions,
} from '../../api/queries.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/** Only reachable once a provider has actually started seeing the patient - matches EncounterService.requireDocumentableStatus exactly, checked client-side too so the common case never round-trips a 409. */
const DOCUMENTABLE_STATUSES = new Set(['with_provider', 'checked_out']);

const emptyLine = () => ({ medicationName: '', dosage: '', instructions: '' });

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

  const [form, setForm] = useState({ chiefComplaint: '', assessment: '', plan: '' });
  const [formError, setFormError] = useState(null);
  const [saved, setSaved] = useState(false);

  const [lines, setLines] = useState([emptyLine()]);
  const [rxError, setRxError] = useState(null);
  const [rxSaved, setRxSaved] = useState(false);

  // Lazy-init from the server once loaded, same one-shot pattern as every
  // other edit form in this app (e.g. clinic-admin/Settings.jsx).
  useEffect(() => {
    if (encounterQuery.data) {
      const e = encounterQuery.data.encounter;
      setForm({ chiefComplaint: e.chiefComplaint || '', assessment: e.assessment || '', plan: e.plan || '' });
      const rx = encounterQuery.data.prescriptions;
      setLines(rx.length > 0 ? rx.map((p) => ({ medicationName: p.medicationName, dosage: p.dosage || '', instructions: p.instructions || '' })) : [emptyLine()]);
    }
  }, [encounterQuery.data]);

  async function handleSaveEncounter(event) {
    event.preventDefault();
    setFormError(null);
    setSaved(false);
    try {
      await upsertEncounter.mutateAsync({
        chiefComplaint: form.chiefComplaint.trim() || null,
        assessment: form.assessment.trim() || null,
        plan: form.plan.trim() || null,
      });
      setSaved(true);
    } catch (err) {
      setFormError(err.message || 'Could not save this encounter.');
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
      .map((l) => ({ medicationName: l.medicationName.trim(), dosage: l.dosage.trim() || undefined, instructions: l.instructions.trim() || undefined }))
      .filter((l) => l.medicationName);
    try {
      await replacePrescriptions.mutateAsync(items);
      setRxSaved(true);
    } catch (err) {
      setRxError(err.message || 'Could not save prescriptions.');
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
    : appointment.contactName || 'Guest';
  const documentable = DOCUMENTABLE_STATUSES.has(appointment.status);
  const encounterExists = Boolean(encounterQuery.data);

  return (
    <div className="mx-auto max-w-2xl">
      <div className="mb-1 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">Encounter</h1>
        <StatusPill status={appointment.status} />
      </div>
      <p className="mb-6 text-sm text-ink-muted">
        {patientName} · <span className="font-mono">{appointment.appointmentRef}</span>
      </p>

      {!documentable ? (
        <EmptyState
          title="Not ready to document yet"
          description={`This appointment is still '${appointment.status.replace(/_/g, ' ')}' - start the visit (check-in → room → start visit) before charting a note.`}
          action={
            <Link
              to={hasRole('clinic_admin') ? `/front-desk/appointments/${id}` : '/provider'}
              className="text-sm font-medium text-brand hover:underline"
            >
              {hasRole('clinic_admin') ? 'Back to appointment' : "Back to Today's Schedule"}
            </Link>
          }
        />
      ) : (
        <>
          <form onSubmit={handleSaveEncounter} className="flex flex-col gap-4 rounded-xl border border-slate-200 bg-surface p-4">
            <p className="text-sm font-semibold text-ink">Note</p>
            {encounterQuery.isLoading && <Skeleton className="h-40 w-full" />}
            {encounterLoadError && <ErrorBanner message={encounterQuery.error?.message} onRetry={encounterQuery.refetch} />}
            {!encounterQuery.isLoading && (
              <>
                <Field label="Chief complaint">
                  <textarea rows={2} value={form.chiefComplaint} onChange={(e) => { setForm({ ...form, chiefComplaint: e.target.value }); setSaved(false); }} className={inputClass} />
                </Field>
                <Field label="Assessment">
                  <textarea rows={3} value={form.assessment} onChange={(e) => { setForm({ ...form, assessment: e.target.value }); setSaved(false); }} className={inputClass} />
                </Field>
                <Field label="Plan">
                  <textarea rows={3} value={form.plan} onChange={(e) => { setForm({ ...form, plan: e.target.value }); setSaved(false); }} className={inputClass} />
                </Field>
                {formError && <ErrorBanner message={formError} />}
                <div className="flex items-center gap-3">
                  <button
                    type="submit"
                    disabled={upsertEncounter.isPending}
                    className="self-start rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {upsertEncounter.isPending ? 'Saving…' : encounterExists ? 'Update note' : 'Save note'}
                  </button>
                  {saved && <span className="text-sm text-success">Saved.</span>}
                </div>
              </>
            )}
          </form>

          <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-4">
            <p className="mb-3 text-sm font-semibold text-ink">Prescriptions</p>
            {encounterQuery.isLoading ? (
              <Skeleton className="h-24 w-full" />
            ) : !encounterExists ? (
              <p className="text-sm text-ink-muted">Save the note above first - prescriptions need an encounter to attach to.</p>
            ) : (
              <form onSubmit={handleSavePrescriptions} className="flex flex-col gap-3">
                {lines.map((line, i) => (
                  <div key={i} className="flex flex-wrap items-end gap-3">
                    <Field label="Medication">
                      <input value={line.medicationName} onChange={(e) => updateLine(i, 'medicationName', e.target.value)} className={`${inputClass} w-48`} />
                    </Field>
                    <Field label="Dosage">
                      <input value={line.dosage} onChange={(e) => updateLine(i, 'dosage', e.target.value)} placeholder="500mg twice daily" className={`${inputClass} w-48`} />
                    </Field>
                    <Field label="Instructions">
                      <input value={line.instructions} onChange={(e) => updateLine(i, 'instructions', e.target.value)} className={`${inputClass} w-56`} />
                    </Field>
                    <button type="button" onClick={() => removeLine(i)} className="text-sm text-danger hover:underline">
                      Remove
                    </button>
                  </div>
                ))}
                <button type="button" onClick={addLine} className="self-start text-sm font-semibold text-brand hover:underline">
                  + Add medication
                </button>
                {rxError && <ErrorBanner message={rxError} />}
                <div className="flex items-center gap-3">
                  <button
                    type="submit"
                    disabled={replacePrescriptions.isPending}
                    className="self-start rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {replacePrescriptions.isPending ? 'Saving…' : 'Save prescriptions'}
                  </button>
                  {rxSaved && <span className="text-sm text-success">Saved.</span>}
                </div>
              </form>
            )}
          </div>
        </>
      )}
    </div>
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
