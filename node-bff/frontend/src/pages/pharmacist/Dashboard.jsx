import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  usePharmacyQueue,
  useMedications,
  useStockBatches,
  useDispensePrescription,
  usePharmacyAnalytics,
  useDispenseRecords,
  useDispensePayments,
  useCreateDispensePayment,
  useDispenseInvoice,
  useGenerateDispenseInvoice,
} from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import StatCard from '../../components/StatCard.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ChartCard from '../../components/analytics/ChartCard.jsx';
import DispenseVolumeChart from '../../components/analytics/DispenseVolumeChart.jsx';
import MedicationDispenseCountChart from '../../components/analytics/MedicationDispenseCountChart.jsx';
import PaymentsPanel from '../../components/PaymentsPanel.jsx';
import InvoicePanel from '../../components/InvoicePanel.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const WINDOW_OPTIONS = [7, 30, 90];

/**
 * The pharmacist's own worklist - GET /api/pharmacy/queue, active
 * prescriptions not yet fully dispensed, joined server-side through
 * encounter/appointment/patient for a real patientName/contactName (see
 * PrescriptionRepository.findPendingDispense's own javadoc - pharmacist has
 * no read access to GET /api/patients, unlike front_desk/clinic_admin/
 * provider, so the name is embedded server-side rather than resolved via a
 * second, permission-gated lookup the way other worklists do it). A
 * prescription's medicationName stays free text (a provider writes it, no
 * drug-name matching exists anywhere in this app) - the pharmacist manually
 * maps it to a catalog Medication + a specific StockBatch when dispensing,
 * see DispenseRecord's own javadoc for why there's no automatic FEFO
 * allocation.
 */
export default function PharmacistDashboard() {
  const { t } = useTranslation();
  const queue = usePharmacyQueue(true);
  const [lastDispensed, setLastDispensed] = useState(null);
  const [windowDays, setWindowDays] = useState(30);
  const analytics = usePharmacyAnalytics(true, windowDays);

  function patientName(row) {
    return row.patientName || row.contactName || t('frontDeskAppointments.guest');
  }

  const isLoading = queue.isLoading;
  const isError = queue.isError;

  const columns = [
    { key: 'patient', header: t('common.name'), accessor: patientName, sortable: true },
    { key: 'medication', header: t('pharmacistPage.medication'), accessor: (r) => r.medicationName, sortable: true, className: 'font-semibold' },
    { key: 'dosage', header: t('pharmacistPage.dosage'), accessor: (r) => r.dosage || '', render: (r) => r.dosage || '—' },
    {
      key: 'progress',
      header: t('pharmacistPage.progress'),
      accessor: (r) => r.quantityPrescribed ?? 0,
      sortable: true,
      render: (r) =>
        r.quantityPrescribed
          ? t('pharmacistPage.progressValue', { dispensed: r.quantityAlreadyDispensed ?? 0, prescribed: r.quantityPrescribed })
          : t('pharmacistPage.noTotal', { dispensed: r.quantityAlreadyDispensed ?? 0 }),
    },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('pharmacistPage.dashboardTitle')} />

      <div className="mb-6 max-w-xs">
        <StatCard label={t('pharmacistPage.pendingDispenses')} value={(queue.data || []).length} />
      </div>

      {lastDispensed && (
        <div className="mb-4 flex items-center justify-between rounded-lg border border-success/30 bg-success-light px-4 py-2.5 text-sm text-success">
          <span>{t('pharmacistPage.dispenseConfirmed', { medication: lastDispensed.medicationName, quantity: lastDispensed.quantity })}</span>
          <button type="button" onClick={() => setLastDispensed(null)} className="font-semibold hover:underline">
            {t('clinicsPage.dismiss')}
          </button>
        </div>
      )}

      <DataTable
        columns={columns}
        rows={queue.data || []}
        rowKey="id"
        searchAccessors={[patientName, (r) => r.medicationName]}
        renderExpanded={(row) => <DispensePanel prescription={row} onDispensed={setLastDispensed} />}
        isLoading={isLoading}
        error={isError ? queue.error : null}
        onRetry={() => queue.refetch()}
        emptyTitle={t('pharmacistPage.emptyQueueTitle')}
        emptyDescription={t('pharmacistPage.emptyQueueDescription')}
      />

      <div className="mt-8 flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-bold text-ink">{t('clinicAnalytics.sectionTitle')}</h2>
          <div className="inline-flex rounded-lg border border-slate-200 p-0.5 text-xs font-medium" role="group" aria-label={t('clinicAnalytics.windowLabel', { days: windowDays })}>
            {WINDOW_OPTIONS.map((d) => (
              <button
                key={d}
                type="button"
                onClick={() => setWindowDays(d)}
                aria-pressed={windowDays === d}
                className={`rounded-md px-2.5 py-1 transition-colors ${
                  windowDays === d ? 'bg-brand-light text-brand-text' : 'text-ink-muted hover:bg-slate-100 hover:text-ink'
                }`}
              >
                {t(`clinicAnalytics.days${d}`)}
              </button>
            ))}
          </div>
        </div>

        {analytics.isError && (
          <ErrorBanner message={analytics.error?.message || t('clinicAnalytics.errorLoad')} onRetry={analytics.refetch} />
        )}

        {analytics.isLoading && (
          <div className="grid gap-4 lg:grid-cols-2">
            <Skeleton className="h-64 w-full" />
            <Skeleton className="h-64 w-full" />
          </div>
        )}

        {!analytics.isLoading && !analytics.isError && analytics.data && (
          <>
            <div className="max-w-xs">
              <StatCard
                label={t('pharmacistAnalytics.totalDispensedWindow', { days: windowDays })}
                value={analytics.data.dispensingVolume.reduce((s, d) => s + Number(d.total), 0)}
                mono
              />
            </div>

            <div className="grid gap-4 lg:grid-cols-2">
              <ChartCard title={t('pharmacistAnalytics.dispensingVolumeTitle')} subtitle={t('pharmacistAnalytics.dispensingVolumeSubtitle')}>
                <DispenseVolumeChart data={analytics.data.dispensingVolume} />
              </ChartCard>
              <ChartCard title={t('pharmacistAnalytics.medicationDispenseCountsTitle')} subtitle={t('pharmacistAnalytics.medicationDispenseCountsSubtitle')}>
                <MedicationDispenseCountChart data={analytics.data.medicationDispenseCounts} />
              </ChartCard>
            </div>
          </>
        )}
      </div>
    </PageContainer>
  );
}

function DispensePanel({ prescription, onDispensed }) {
  const { t } = useTranslation();
  const { data: medications } = useMedications(true, 'active');
  const [medicationId, setMedicationId] = useState(prescription.suggestedMedicationId || '');
  const [batchId, setBatchId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [notes, setNotes] = useState('');
  const [formError, setFormError] = useState(null);
  const [dispensed, setDispensed] = useState(false);

  const { data: batches } = useStockBatches(medicationId);
  const availableBatches = (batches || []).filter((b) => b.status === 'active' && b.quantityOnHand > 0);
  const dispense = useDispensePrescription(prescription.id);

  async function handleSubmit(event) {
    event.preventDefault();
    setFormError(null);
    setDispensed(false);
    if (!medicationId || !batchId || !quantity || Number(quantity) <= 0) {
      setFormError(t('pharmacistPage.errorFields'));
      return;
    }
    const dispensedMedicationName = (medications || []).find((m) => m.id === medicationId)?.name || prescription.medicationName;
    const dispensedQuantity = Number(quantity);
    try {
      await dispense.mutateAsync({ medicationId, stockBatchId: batchId, quantity: dispensedQuantity, notes: notes.trim() || undefined });
      setDispensed(true);
      setBatchId('');
      setQuantity('1');
      setNotes('');
      onDispensed?.({ medicationName: dispensedMedicationName, quantity: dispensedQuantity });
    } catch (err) {
      setFormError(err.message || t('pharmacistPage.errorDispense'));
    }
  }

  return (
    <div>
      {prescription.instructions && <p className="mb-3 text-sm text-ink">{prescription.instructions}</p>}
      <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3">
        <Field label={t('pharmacistPage.medication')}>
          <select value={medicationId} onChange={(e) => { setMedicationId(e.target.value); setBatchId(''); }} className={`${inputClass} w-56`}>
            <option value="">{t('booking.select')}</option>
            {(medications || []).map((m) => (
              <option key={m.id} value={m.id}>{m.name}</option>
            ))}
          </select>
          {prescription.suggestedMedicationId && (
            <span className="mt-1 block text-xs text-ink-muted">{t('pharmacistPage.suggestedMedicationHint')}</span>
          )}
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
        <Button type="submit" variant="accent" disabled={dispense.isPending}>
          {dispense.isPending ? t('pharmacistPage.dispensing') : t('pharmacistPage.dispense')}
        </Button>
        {dispensed && <span className="text-sm text-success">{t('settingsPage.saved')}</span>}
      </form>
      {formError && <div className="mt-3"><ErrorBanner message={formError} /></div>}
      <DispenseHistory prescriptionId={prescription.id} />
    </div>
  );
}

/**
 * Dispense billing (phase 31 backend, phase 37 frontend) - GET
 * /api/prescriptions/{id}/dispense-records had no UI consumer at all
 * until now. A plain shallow list, not a further DataTable - same
 * "shallow-list nesting" precedent Items.jsx's stock-batches and
 * Assets.jsx's maintenance log already established for what's typically
 * 1-3 records per prescription. `allMedications` (all statuses, not
 * just active) is fetched separately from the dispense form's own
 * active-only picker purely for name resolution, applying the phase-35/36
 * "never resolve a display name against an active-only list" lesson
 * proactively - a historical record can reference a since-deactivated
 * medication.
 */
function DispenseHistory({ prescriptionId }) {
  const { t } = useTranslation();
  const records = useDispenseRecords(prescriptionId);
  const { data: allMedications } = useMedications(true);
  const [expandedId, setExpandedId] = useState(null);

  function medicationName(medicationId) {
    return (allMedications || []).find((m) => m.id === medicationId)?.name || medicationId;
  }

  if (records.isLoading || !records.data || records.data.length === 0) {
    return null;
  }

  return (
    <div className="mt-4 border-t border-slate-100 pt-4">
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('pharmacistPage.dispenseHistory')}</h3>
      <ul className="flex flex-col gap-2">
        {records.data.map((record) => (
          <li key={record.id} className="rounded-lg border border-slate-200 p-3">
            <button
              type="button"
              onClick={() => setExpandedId((id) => (id === record.id ? null : record.id))}
              className="flex w-full items-center justify-between text-left text-sm"
            >
              <span className="text-ink">
                {medicationName(record.medicationId)} &times; {record.quantityDispensed}
                {record.notes && <span className="text-ink-muted"> - {record.notes}</span>}
              </span>
              <span className="text-xs font-semibold text-brand-text">
                {expandedId === record.id ? t('pharmacistPage.hideBilling') : t('pharmacistPage.billing')}
              </span>
            </button>
            {expandedId === record.id && <DispenseBillingPanel dispenseRecordId={record.id} />}
          </li>
        ))}
      </ul>
    </div>
  );
}

function DispenseBillingPanel({ dispenseRecordId }) {
  const paymentsQuery = useDispensePayments(dispenseRecordId);
  const createPayment = useCreateDispensePayment(dispenseRecordId);
  const invoiceQuery = useDispenseInvoice(dispenseRecordId);
  const generateInvoice = useGenerateDispenseInvoice(dispenseRecordId);

  return (
    <div className="mt-3">
      <PaymentsPanel paymentsQuery={paymentsQuery} createPayment={createPayment} extraRoles={['pharmacist']} />
      <InvoicePanel
        invoiceQuery={invoiceQuery}
        generateInvoice={generateInvoice}
        pdfUrl={`/api/dispense-records/${dispenseRecordId}/invoice/pdf`}
        extraRoles={['pharmacist']}
      />
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
