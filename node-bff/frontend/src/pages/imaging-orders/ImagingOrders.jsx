import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  useImagingOrders,
  useCreateImagingOrder,
  usePatients,
  useProviders,
  useImagingStudyRates,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

const emptyForm = { patientId: '', orderingProviderId: '', modality: 'xray', studyType: '', studyCode: '', priority: 'routine', notes: '' };

/**
 * Staff imaging-order list + create form - GET/POST /api/imaging-orders
 * (ImagingOrderController, phase 42 backend). Standalone/always staff
 * -created, unlike lab orders - no patient-initiated request flow and no
 * multi-line tests editor (one study per order), so this is a trimmed
 * version of LabOrders.jsx's own shape.
 */
export default function ImagingOrders() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [statusFilter, setStatusFilter] = useState('');
  const { data: orders, isLoading, isError, error, refetch } = useImagingOrders(true);
  const { data: patients } = usePatients();
  const { data: providers } = useProviders(true, 'active');
  const { data: studyRates } = useImagingStudyRates(true);
  const createOrder = useCreateImagingOrder();

  const patientById = useMemo(() => Object.fromEntries((patients || []).map((p) => [p.id, p])), [patients]);
  const providerById = useMemo(() => Object.fromEntries((providers || []).map((p) => [p.id, p])), [providers]);

  function patientName(order) {
    const p = patientById[order.patientId];
    return p ? `${p.firstName} ${p.lastName}` : '';
  }
  function providerName(order) {
    return providerById[order.orderingProviderId]?.fullName || '';
  }

  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.patientId || !form.orderingProviderId || !form.studyType.trim() || !form.studyCode.trim()) {
      setFormError(t('imagingOrdersPage.errorFields'));
      return;
    }
    try {
      await createOrder.mutateAsync({
        patientId: form.patientId,
        orderingProviderId: form.orderingProviderId,
        modality: form.modality,
        studyType: form.studyType.trim(),
        studyCode: form.studyCode.trim(),
        priority: form.priority,
        notes: form.notes.trim() || undefined,
      });
      setForm(emptyForm);
      setShowCreate(false);
    } catch (err) {
      setFormError(err.message || t('imagingOrdersPage.errorCreate'));
    }
  }

  const visibleOrders = (orders || []).filter((o) => !statusFilter || o.status === statusFilter);

  const columns = [
    { key: 'orderRef', header: t('appointmentDetail.reference'), accessor: (o) => o.orderRef, sortable: true, className: 'font-mono text-xs' },
    { key: 'patient', header: t('labOrdersPage.patient'), accessor: patientName, sortable: true },
    { key: 'provider', header: t('labOrdersPage.orderingProvider'), accessor: providerName, sortable: true },
    { key: 'studyType', header: t('imagingOrdersPage.studyType'), accessor: (o) => o.studyType, sortable: true },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (o) => o.status,
      sortable: true,
      render: (o) => <StatusPill status={o.status} />,
    },
    { key: 'orderedAt', header: t('common.bookedAt'), accessor: (o) => o.orderedAt, sortAccessor: (o) => new Date(o.orderedAt), sortable: true, render: (o) => formatDateTime(o.orderedAt) },
    { key: 'totalCost', header: t('appointmentTypesPage.price'), accessor: (o) => o.totalCost, sortable: true, className: 'font-mono font-semibold', render: (o) => formatCurrency(o.totalCost) },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader
        title={t('nav.provider.imagingOrders')}
        actions={
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} aria-label={t('labOrdersPage.filterByStatus')} className={`${inputClass} w-44`}>
            <option value="">{t('labOrdersPage.allStatuses')}</option>
            <option value="ordered">{t('status.ordered')}</option>
            <option value="scheduled">{t('status.scheduled')}</option>
            <option value="in_progress">{t('status.in_progress')}</option>
            <option value="completed">{t('status.completed')}</option>
            <option value="reviewed">{t('status.reviewed')}</option>
            <option value="cancelled">{t('status.cancelled')}</option>
          </select>
        }
      />

      <div className="mb-6">
        <button
          type="button"
          onClick={() => setShowCreate((v) => !v)}
          className="rounded-lg border border-brand/40 px-3 py-1.5 text-sm font-medium text-brand-text hover:bg-brand-light/40"
        >
          {showCreate ? t('labOrdersPage.close') : t('imagingOrdersPage.newImagingOrder')}
        </button>
      </div>

      {showCreate && (
        <>
          <form onSubmit={handleCreate} className="mb-4 rounded-xl border border-slate-200 bg-surface p-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Field label={t('labOrdersPage.patient')}>
                <select value={form.patientId} onChange={(e) => setForm({ ...form, patientId: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="">{t('booking.select')}</option>
                  {(patients || []).map((p) => (
                    <option key={p.id} value={p.id}>{p.firstName} {p.lastName}</option>
                  ))}
                </select>
              </Field>
              <Field label={t('labOrdersPage.orderingProvider')}>
                <select value={form.orderingProviderId} onChange={(e) => setForm({ ...form, orderingProviderId: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="">{t('booking.select')}</option>
                  {(providers || []).map((p) => (
                    <option key={p.id} value={p.id}>{p.fullName}</option>
                  ))}
                </select>
              </Field>
              <Field label={t('labOrdersPage.priority')}>
                <select value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="routine">{t('labOrdersPage.routine')}</option>
                  <option value="urgent">{t('labOrdersPage.urgent')}</option>
                  <option value="stat">{t('labOrdersPage.stat')}</option>
                </select>
              </Field>
            </div>

            <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Field label={t('imagingOrdersPage.modality')}>
                <select value={form.modality} onChange={(e) => setForm({ ...form, modality: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="xray">{t('imagingOrdersPage.modalityXray')}</option>
                  <option value="ultrasound">{t('imagingOrdersPage.modalityUltrasound')}</option>
                  <option value="ct">{t('imagingOrdersPage.modalityCt')}</option>
                  <option value="mri">{t('imagingOrdersPage.modalityMri')}</option>
                  <option value="other">{t('imagingOrdersPage.modalityOther')}</option>
                </select>
              </Field>
              <Field label={t('imagingOrdersPage.studyType')}>
                <input value={form.studyType} onChange={(e) => setForm({ ...form, studyType: e.target.value })} className={`${inputClass} w-full`} placeholder={t('imagingOrdersPage.studyTypePlaceholder')} />
              </Field>
              <Field
                label={t('imagingOrdersPage.studyCode')}
                hint={studyRates && studyRates.length > 0 ? t('labOrdersPage.knownCodes', { codes: studyRates.map((r) => r.studyCode).join(', ') }) : undefined}
              >
                <input value={form.studyCode} onChange={(e) => setForm({ ...form, studyCode: e.target.value })} className={`${inputClass} w-full`} />
              </Field>
            </div>

            <div className="mt-4">
              <Field label={t('labOrdersPage.notesOptional')}>
                <input value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} className={`${inputClass} w-full max-w-md`} />
              </Field>
            </div>

            <Button type="submit" variant="accent" className="mt-4" disabled={createOrder.isPending}>
              {createOrder.isPending ? t('labOrdersPage.creating') : t('imagingOrdersPage.createImagingOrder')}
            </Button>
          </form>
          {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
        </>
      )}

      <DataTable
        columns={columns}
        rows={visibleOrders}
        rowKey="id"
        searchAccessors={[(o) => o.orderRef, patientName, providerName, (o) => o.studyType]}
        onRowClick={(o) => navigate(`/imaging-orders/${o.id}`)}
        defaultSortKey="orderedAt"
        defaultSortDir="desc"
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('imagingOrdersPage.emptyTitle')}
        emptyDescription={t('imagingOrdersPage.emptyDescription')}
      />
    </PageContainer>
  );
}
