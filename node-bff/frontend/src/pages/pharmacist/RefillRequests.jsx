import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useRefillRequests, useApproveRefillRequest, useDenyRefillRequest } from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const STATUS_TABS = ['requested', 'approved', 'denied', ''];

/**
 * GET/POST .../approve/.../deny PatientPrescriptionController (phase
 * 33 backend, phase 39 frontend) - same status-filter-tabs +
 * DataTable + renderExpanded shape ControlledSubstanceQueue.jsx just
 * established, directly reused. Each entry already embeds a resolved
 * patientName/medicationName (this phase's own backend addition -
 * see RefillRequestQueueEntry) - the one gap in this set that
 * genuinely needed a backend change, since no other endpoint let a
 * pharmacist resolve either id to a name.
 */
export default function RefillRequests() {
  const { t } = useTranslation();
  const [statusFilter, setStatusFilter] = useState('requested');
  const requests = useRefillRequests(true, statusFilter);

  const columns = [
    { key: 'patient', header: t('common.name'), accessor: (r) => r.patientName || r.patientId, sortable: true, className: 'font-semibold' },
    { key: 'medication', header: t('pharmacistPage.medication'), accessor: (r) => r.medicationName || r.prescriptionId, sortable: true },
    { key: 'notes', header: t('pharmacistPage.notesOptional'), accessor: (r) => r.notes || '', render: (r) => r.notes || '—' },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (r) => r.status,
      sortable: true,
      render: (r) => t(`refillRequestsPage.status_${r.status}`, { defaultValue: r.status }),
    },
  ];

  return (
    <PageContainer width="lg">
      <h1 className="mb-2 text-2xl font-bold text-ink">{t('nav.pharmacist.refillRequests')}</h1>
      <p className="mb-6 text-sm text-ink-muted">{t('refillRequestsPage.intro')}</p>

      <div className="mb-4 inline-flex rounded-lg border border-slate-200 p-0.5 text-xs font-medium" role="group">
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
            {t(s ? `refillRequestsPage.status_${s}` : 'refillRequestsPage.statusAll')}
          </button>
        ))}
      </div>

      <DataTable
        columns={columns}
        rows={requests.data || []}
        rowKey="id"
        searchAccessors={[(r) => r.patientName || '', (r) => r.medicationName || '']}
        renderExpanded={(request) => <RequestDetail request={request} />}
        isLoading={requests.isLoading}
        error={requests.isError ? requests.error : null}
        onRetry={requests.refetch}
        emptyTitle={t('refillRequestsPage.emptyTitle')}
        emptyDescription={t('refillRequestsPage.emptyDescription')}
      />
    </PageContainer>
  );
}

function RequestDetail({ request }) {
  const { t } = useTranslation();
  const approve = useApproveRefillRequest(request.id);
  const deny = useDenyRefillRequest(request.id);
  const [denying, setDenying] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState(null);

  async function handleApprove() {
    setError(null);
    try {
      await approve.mutateAsync();
    } catch (err) {
      setError(err.message || t('refillRequestsPage.errorApprove'));
    }
  }

  async function handleDeny(event) {
    event.preventDefault();
    setError(null);
    try {
      await deny.mutateAsync(reason.trim() ? { reviewNotes: reason.trim() } : undefined);
      setDenying(false);
      setReason('');
    } catch (err) {
      setError(err.message || t('refillRequestsPage.errorDeny'));
    }
  }

  return (
    <div>
      <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-3">
        <div>
          <dt className="text-ink-muted">{t('common.name')}</dt>
          <dd className="text-ink">{request.patientName || request.patientId}</dd>
        </div>
        <div>
          <dt className="text-ink-muted">{t('pharmacistPage.medication')}</dt>
          <dd className="text-ink">{request.medicationName || request.prescriptionId}</dd>
        </div>
        {request.notes && (
          <div>
            <dt className="text-ink-muted">{t('pharmacistPage.notesOptional')}</dt>
            <dd className="text-ink">{request.notes}</dd>
          </div>
        )}
        {request.status !== 'requested' && (
          <>
            <div>
              <dt className="text-ink-muted">{t('refillRequestsPage.reviewedAt')}</dt>
              <dd className="text-ink">{new Date(request.reviewedAt).toLocaleString()}</dd>
            </div>
            {request.reviewNotes && (
              <div>
                <dt className="text-ink-muted">{t('refillRequestsPage.reviewNotes')}</dt>
                <dd className="text-ink">{request.reviewNotes}</dd>
              </div>
            )}
          </>
        )}
      </dl>

      {request.status === 'requested' && (
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <Button type="button" variant="accent" onClick={handleApprove} disabled={approve.isPending}>
            {approve.isPending ? t('refillRequestsPage.approving') : t('refillRequestsPage.approve')}
          </Button>
          {!denying ? (
            <button type="button" onClick={() => setDenying(true)} className="text-sm text-danger hover:underline">
              {t('refillRequestsPage.deny')}
            </button>
          ) : (
            <form onSubmit={handleDeny} className="flex flex-wrap items-end gap-3 rounded-lg bg-slate-50 p-3">
              <label className="block text-left">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('refillRequestsPage.reviewNotes')}</span>
                <input autoFocus value={reason} onChange={(e) => setReason(e.target.value)} className={`${inputClass} w-64`} />
              </label>
              <Button type="submit" variant="danger" size="sm" disabled={deny.isPending}>
                {t('refillRequestsPage.confirmDeny')}
              </Button>
              <button type="button" onClick={() => setDenying(false)} className="text-sm text-ink-muted hover:underline">
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
