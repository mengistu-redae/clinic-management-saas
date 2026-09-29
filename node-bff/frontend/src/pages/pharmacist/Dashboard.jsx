import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { usePharmacyQueue, useMedications, useStockBatches, useDispensePrescription } from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import StatCard from '../../components/StatCard.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

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
