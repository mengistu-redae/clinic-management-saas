import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useControlledSubstanceRequests,
  useRequestControlledSubstanceDispense,
  useCosignControlledSubstanceDispense,
  useRejectControlledSubstanceDispense,
  usePharmacyQueue,
  useMedications,
  useStockBatches,
} from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const STATUS_TABS = ['pending', 'cosigned', 'rejected', ''];

/**
 * Phase 28's own dual-sign-off workflow (ControlledSubstanceDispenseController)
 * had zero frontend until this phase - a pharmacist requests a controlled
 * -substance dispense, a *different* pharmacist or clinic_admin co-signs
 * before the real stock decrement/DispenseRecord happens (enforced in
 * DispenseService, not the role gate here - the UI doesn't try to
 * pre-empt the same-person 409 client-side, it just surfaces the real
 * backend message).
 *
 * The request form needs a prescription picker no existing endpoint
 * gives directly - reuses usePharmacyQueue(true), the same
 * active-prescriptions list pharmacist/Dashboard.jsx's own queue
 * already fetches, purely for display context (patient + the
 * prescription's own free-text medicationName); the actual
 * medicationId submitted comes from a separate, controlled-substances
 * -only medication <select>, matching DispensePanel's own established
 * "free-text prescription vs. picked catalog medication" split (no
 * drug-name matching anywhere in this app, phase 20's own pinned
 * design).
 */
export default function ControlledSubstanceQueue() {
  const { t } = useTranslation();
  const [statusFilter, setStatusFilter] = useState('pending');
  const requests = useControlledSubstanceRequests(true, statusFilter);
  const queue = usePharmacyQueue(true);
  const { data: allMedications } = useMedications(true);

  const medicationById = useMemo(
    () => Object.fromEntries((allMedications || []).map((m) => [m.id, m])),
    [allMedications],
  );

  function medicationName(id) {
    return medicationById[id]?.name || id;
  }

  function prescriptionLabel(prescriptionId) {
    const row = (queue.data || []).find((r) => r.id === prescriptionId);
    if (!row) return prescriptionId;
    return `${row.patientName || row.contactName || t('frontDeskAppointments.guest')} - ${row.medicationName}`;
  }

  const columns = [
    { key: 'prescription', header: t('controlledSubstancesPage.prescription'), accessor: (r) => prescriptionLabel(r.prescriptionId) },
    { key: 'medication', header: t('pharmacistPage.medication'), accessor: (r) => medicationName(r.medicationId), sortable: true, className: 'font-semibold' },
    { key: 'quantity', header: t('pharmacistPage.quantity'), accessor: (r) => r.quantity, sortable: true },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (r) => r.status,
      sortable: true,
      render: (r) => t(`controlledSubstancesPage.status_${r.status}`, { defaultValue: r.status }),
    },
  ];

  return (
    <PageContainer width="lg">
      <h1 className="mb-2 text-2xl font-bold text-ink">{t('nav.pharmacist.controlledSubstances')}</h1>
      <p className="mb-6 text-sm text-ink-muted">{t('controlledSubstancesPage.intro')}</p>

      <RequestForm />

      <div className="mb-4 mt-6 inline-flex rounded-lg border border-slate-200 p-0.5 text-xs font-medium" role="group">
        {STATUS_TABS.map((s) => (
          <button
            key={s || 'all'}
            type="button"
            onClick={() => setStatusFilter(s)}
            aria-pressed={statusFilter === s}
            className={`rounded-md px-3 py-1.5 transition-colors ${
              statusFilter === s ? 'bg-brand-light text-brand-text' : 'text-ink-muted hover:bg-slate-100 hover:text-ink'
            }`}
          >
            {t(s ? `controlledSubstancesPage.status_${s}` : 'controlledSubstancesPage.statusAll')}
          </button>
        ))}
      </div>

      <DataTable
        columns={columns}
        rows={requests.data || []}
        rowKey="id"
        searchAccessors={[(r) => medicationName(r.medicationId), (r) => prescriptionLabel(r.prescriptionId)]}
        renderExpanded={(request) => <RequestDetail request={request} medicationName={medicationName(request.medicationId)} prescriptionLabel={prescriptionLabel(request.prescriptionId)} />}
        isLoading={requests.isLoading}
        error={requests.isError ? requests.error : null}
        onRetry={requests.refetch}
        emptyTitle={t('controlledSubstancesPage.emptyTitle')}
        emptyDescription={t('controlledSubstancesPage.emptyDescription')}
      />
    </PageContainer>
  );
}

function RequestForm() {
  const { t } = useTranslation();
  const queue = usePharmacyQueue(true);
  const { data: activeMedications } = useMedications(true, 'active');
  const controlledMedications = (activeMedications || []).filter((m) => m.controlledSubstanceSchedule);

  const [prescriptionId, setPrescriptionId] = useState('');
  const [medicationId, setMedicationId] = useState('');
  const [batchId, setBatchId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [notes, setNotes] = useState('');
  const [acknowledgeConflict, setAcknowledgeConflict] = useState(false);
  const [formError, setFormError] = useState(null);
  const [submitted, setSubmitted] = useState(false);

  const { data: batches } = useStockBatches(medicationId);
  const availableBatches = (batches || []).filter((b) => b.status === 'active' && b.quantityOnHand > 0);
  const request = useRequestControlledSubstanceDispense(prescriptionId);

  async function handleSubmit(event) {
    event.preventDefault();
    setFormError(null);
    setSubmitted(false);
    if (!prescriptionId || !medicationId || !batchId || !quantity || Number(quantity) <= 0) {
      setFormError(t('pharmacistPage.errorFields'));
      return;
    }
    try {
      await request.mutateAsync({
        medicationId,
        stockBatchId: batchId,
        quantity: Number(quantity),
        notes: notes.trim() || undefined,
        acknowledgeConflict,
      });
      setSubmitted(true);
      setBatchId('');
      setQuantity('1');
      setNotes('');
      setAcknowledgeConflict(false);
    } catch (err) {
      setFormError(err.message || t('controlledSubstancesPage.errorRequest'));
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
      <Field label={t('controlledSubstancesPage.prescription')}>
        <select value={prescriptionId} onChange={(e) => setPrescriptionId(e.target.value)} className={`${inputClass} w-64`}>
          <option value="">{t('booking.select')}</option>
          {(queue.data || []).map((r) => (
            <option key={r.id} value={r.id}>
              {(r.patientName || r.contactName || t('frontDeskAppointments.guest'))} - {r.medicationName}
            </option>
          ))}
        </select>
      </Field>
      <Field label={t('pharmacistPage.medication')}>
        <select value={medicationId} onChange={(e) => { setMedicationId(e.target.value); setBatchId(''); }} className={`${inputClass} w-56`}>
          <option value="">{t('booking.select')}</option>
          {controlledMedications.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
        </select>
      </Field>
      <Field label={t('pharmacistPage.stockBatch')}>
        <select value={batchId} onChange={(e) => setBatchId(e.target.value)} disabled={!medicationId} className={`${inputClass} w-56`}>
          <option value="">{t('booking.select')}</option>
          {availableBatches.map((b) => (
            <option key={b.id} value={b.id}>
              {(b.batchNumber || t('pharmacistPage.unlabeledBatch'))} - {b.quantityOnHand} {t('pharmacistPage.onHand')}
              {b.expiryDate ? ` - ${b.expiryDate}` : ''}
            </option>
          ))}
        </select>
      </Field>
      <Field label={t('pharmacistPage.quantity')}>
        <input type="number" min="1" value={quantity} onChange={(e) => setQuantity(e.target.value)} className={`${inputClass} w-24`} />
      </Field>
      <Field label={t('pharmacistPage.notesOptional')}>
        <input value={notes} onChange={(e) => setNotes(e.target.value)} className={`${inputClass} w-56`} />
      </Field>
      <label className="flex items-center gap-2 pb-2 text-xs text-ink-muted">
        <input type="checkbox" checked={acknowledgeConflict} onChange={(e) => setAcknowledgeConflict(e.target.checked)} />
        {t('controlledSubstancesPage.acknowledgeConflict')}
      </label>
      <Button type="submit" variant="accent" disabled={request.isPending}>
        {request.isPending ? t('controlledSubstancesPage.requesting') : t('controlledSubstancesPage.requestDispense')}
      </Button>
      {submitted && <span className="text-sm text-success">{t('settingsPage.saved')}</span>}
      {formError && <div className="w-full"><ErrorBanner message={formError} /></div>}
    </form>
  );
}

function RequestDetail({ request, medicationName, prescriptionLabel }) {
  const { t } = useTranslation();
  const cosign = useCosignControlledSubstanceDispense(request.id);
  const reject = useRejectControlledSubstanceDispense(request.id);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState(null);

  async function handleCosign() {
    setError(null);
    try {
      await cosign.mutateAsync();
    } catch (err) {
      setError(err.message || t('controlledSubstancesPage.errorCosign'));
    }
  }

  async function handleReject(event) {
    event.preventDefault();
    setError(null);
    try {
      await reject.mutateAsync({ reason: reason.trim() || undefined });
      setRejecting(false);
      setReason('');
    } catch (err) {
      setError(err.message || t('controlledSubstancesPage.errorReject'));
    }
  }

  return (
    <div>
      <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-3">
        <div>
          <dt className="text-ink-muted">{t('controlledSubstancesPage.prescription')}</dt>
          <dd className="text-ink">{prescriptionLabel}</dd>
        </div>
        <div>
          <dt className="text-ink-muted">{t('pharmacistPage.medication')}</dt>
          <dd className="text-ink">{medicationName} &times; {request.quantity}</dd>
        </div>
        {request.notes && (
          <div>
            <dt className="text-ink-muted">{t('pharmacistPage.notesOptional')}</dt>
            <dd className="text-ink">{request.notes}</dd>
          </div>
        )}
        {request.status === 'cosigned' && (
          <div>
            <dt className="text-ink-muted">{t('controlledSubstancesPage.coSignedAt')}</dt>
            <dd className="text-ink">{new Date(request.coSignedAt).toLocaleString()}</dd>
          </div>
        )}
        {request.status === 'rejected' && (
          <>
            <div>
              <dt className="text-ink-muted">{t('controlledSubstancesPage.rejectedAt')}</dt>
              <dd className="text-ink">{new Date(request.rejectedAt).toLocaleString()}</dd>
            </div>
            {request.rejectionReason && (
              <div>
                <dt className="text-ink-muted">{t('controlledSubstancesPage.rejectionReason')}</dt>
                <dd className="text-ink">{request.rejectionReason}</dd>
              </div>
            )}
          </>
        )}
      </dl>

      {request.status === 'pending' && (
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <Button type="button" variant="accent" onClick={handleCosign} disabled={cosign.isPending}>
            {cosign.isPending ? t('controlledSubstancesPage.cosigning') : t('controlledSubstancesPage.cosign')}
          </Button>
          {!rejecting ? (
            <button type="button" onClick={() => setRejecting(true)} className="text-sm text-danger hover:underline">
              {t('controlledSubstancesPage.reject')}
            </button>
          ) : (
            <form onSubmit={handleReject} className="flex flex-wrap items-end gap-3 rounded-lg bg-slate-50 p-3">
              <Field label={t('pharmacistPage.writeOffReasonLabel')}>
                <input autoFocus value={reason} onChange={(e) => setReason(e.target.value)} className={`${inputClass} w-64`} />
              </Field>
              <Button type="submit" variant="danger" size="sm" disabled={reject.isPending}>
                {t('controlledSubstancesPage.confirmReject')}
              </Button>
              <button type="button" onClick={() => setRejecting(false)} className="text-sm text-ink-muted hover:underline">
                {t('common.cancel')}
              </button>
            </form>
          )}
        </div>
      )}
      {error && <div className="mt-3"><ErrorBanner message={error} /></div>}
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
