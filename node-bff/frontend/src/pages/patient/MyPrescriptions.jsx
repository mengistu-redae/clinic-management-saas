import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMyPrescriptions, useMyRefillRequests, useCreateRefillRequest } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import { inputClass } from '../../components/Field.jsx';
import { formatDateTime } from '../../lib/format.js';

/**
 * GET /api/my-prescriptions (phase 33 backend, phase 39 frontend) - no
 * separate detail route, matching the DataTable+renderExpanded shape
 * this session already used twice for a single simple action
 * (DrugInteractions.jsx, ControlledSubstanceQueue.jsx's reject form) -
 * a prescription has exactly one patient-facing action (request a
 * refill), not a staff-driven status machine worth its own page the
 * way a lab order is.
 */
const PRESCRIPTION_STATUSES = ['active', 'completed', 'discontinued'];

export default function MyPrescriptions() {
  const { t } = useTranslation();
  const [statusFilter, setStatusFilter] = useState('');
  const { data: prescriptions, isLoading, isError, error, refetch } = useMyPrescriptions(true);
  const { data: refillRequests } = useMyRefillRequests(true);

  const visiblePrescriptions = (prescriptions || []).filter((p) => !statusFilter || p.status === statusFilter);

  function latestRequestFor(prescriptionId) {
    const matches = (refillRequests || []).filter((r) => r.prescriptionId === prescriptionId);
    if (matches.length === 0) return null;
    return matches.reduce((latest, r) => (new Date(r.createdAt) > new Date(latest.createdAt) ? r : latest));
  }

  const columns = [
    { key: 'medication', header: t('pharmacistPage.medication'), accessor: (p) => p.medicationName, sortable: true, className: 'font-semibold' },
    { key: 'dosage', header: t('pharmacistPage.dosage'), accessor: (p) => p.dosage || '', render: (p) => p.dosage || '—' },
    {
      key: 'progress',
      header: t('pharmacistPage.progress'),
      accessor: (p) => p.quantityPrescribed ?? 0,
      sortable: true,
      render: (p) =>
        p.quantityPrescribed
          ? t('pharmacistPage.progressValue', { dispensed: p.quantityAlreadyDispensed ?? 0, prescribed: p.quantityPrescribed })
          : t('pharmacistPage.noTotal', { dispensed: p.quantityAlreadyDispensed ?? 0 }),
    },
    { key: 'status', header: t('referralsPage.status'), accessor: (p) => p.status, sortable: true, render: (p) => <StatusPill status={p.status} /> },
    {
      key: 'createdAt',
      header: t('common.bookedAt'),
      accessor: (p) => p.createdAt,
      sortAccessor: (p) => new Date(p.createdAt),
      sortable: true,
      render: (p) => formatDateTime(p.createdAt),
    },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader
        title={t('myPrescriptionsPage.title')}
        actions={
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            aria-label={t('common.filterByStatus')}
            className={`${inputClass} w-44`}
          >
            <option value="">{t('common.all')}</option>
            {PRESCRIPTION_STATUSES.map((s) => (
              <option key={s} value={s}>{t(`prescriptionStatus.${s}`)}</option>
            ))}
          </select>
        }
      />

      <DataTable
        columns={columns}
        rows={visiblePrescriptions}
        rowKey="id"
        searchAccessors={[(p) => p.medicationName]}
        defaultSortKey="createdAt"
        defaultSortDir="desc"
        renderExpanded={(prescription) => (
          <PrescriptionDetail prescription={prescription} latestRequest={latestRequestFor(prescription.id)} />
        )}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('myPrescriptionsPage.emptyTitle')}
        emptyDescription={t('myPrescriptionsPage.emptyDescription')}
      />
    </PageContainer>
  );
}

function PrescriptionDetail({ prescription, latestRequest }) {
  const { t } = useTranslation();
  const createRequest = useCreateRefillRequest(prescription.id);
  const [notes, setNotes] = useState('');
  const [error, setError] = useState(null);
  const [submitted, setSubmitted] = useState(false);

  const pending = latestRequest?.status === 'requested';

  async function handleSubmit(event) {
    event.preventDefault();
    setError(null);
    try {
      await createRequest.mutateAsync(notes.trim() ? { notes: notes.trim() } : undefined);
      setSubmitted(true);
      setNotes('');
    } catch (err) {
      setError(err.message || t('myPrescriptionsPage.errorRequest'));
    }
  }

  return (
    <div>
      {prescription.instructions && <p className="mb-3 text-sm text-ink">{prescription.instructions}</p>}

      {latestRequest && (
        <p className="mb-3 text-sm text-ink-muted">
          {t('myPrescriptionsPage.latestRefillStatus', { status: t(`myPrescriptionsPage.refillStatus_${latestRequest.status}`, { defaultValue: latestRequest.status }) })}
          {latestRequest.status === 'denied' && latestRequest.reviewNotes && (
            <span> - {latestRequest.reviewNotes}</span>
          )}
        </p>
      )}

      {pending ? (
        <p className="text-sm text-ink-muted">{t('myPrescriptionsPage.refillAlreadyPending')}</p>
      ) : (
        <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3">
          <label className="block text-left">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('pharmacistPage.notesOptional')}</span>
            <input value={notes} onChange={(e) => setNotes(e.target.value)} className={`${inputClass} w-64`} />
          </label>
          <Button type="submit" variant="accent" disabled={createRequest.isPending}>
            {createRequest.isPending ? t('myPrescriptionsPage.requesting') : t('myPrescriptionsPage.requestRefill')}
          </Button>
          {submitted && <span className="text-sm text-success">{t('myPrescriptionsPage.refillRequested')}</span>}
        </form>
      )}
      {error && <div className="mt-3"><ErrorBanner message={error} /></div>}
    </div>
  );
}
