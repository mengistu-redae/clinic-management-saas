import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLabRates, useCreateLabRate, useUpdateLabRate, useDeleteLabRate } from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { formatCurrency } from '../../lib/format.js';

/**
 * "Lab Rates" tab of the clinic-admin settings hub - GET/POST/POST
 * .../update/POST .../delete LabRateController. A test only becomes
 * orderable once a rate exists for its code (no fixed test catalog, see
 * NoLabRateConfiguredException) - testCode isn't editable once created,
 * matching UpdateLabTestRateRequest's own shape.
 */
export default function ClinicAdminLabRates() {
  const { t } = useTranslation();
  const { data: rates, isLoading, isError, error, refetch } = useLabRates(true);
  const createRate = useCreateLabRate();

  const [testCode, setTestCode] = useState('');
  const [baseCharge, setBaseCharge] = useState('');
  const [collectionFee, setCollectionFee] = useState('0');
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    const base = Number(baseCharge);
    if (!testCode.trim() || baseCharge === '' || base < 0) {
      setFormError(t('labRatesPage.errorFields'));
      return;
    }
    try {
      await createRate.mutateAsync({
        testCode: testCode.trim().toUpperCase(),
        baseCharge: base,
        collectionFee: collectionFee === '' ? undefined : Number(collectionFee),
      });
      setTestCode('');
      setBaseCharge('');
      setCollectionFee('0');
    } catch (err) {
      setFormError(err.message || t('labRatesPage.errorCreate'));
    }
  }

  const columns = [
    { key: 'testCode', header: t('labOrderTestsEditor.testCode'), accessor: (r) => r.testCode, sortable: true, className: 'font-mono font-semibold' },
    { key: 'baseCharge', header: t('labRatesPage.baseCharge'), accessor: (r) => r.baseCharge, sortable: true, render: (r) => formatCurrency(r.baseCharge) },
    {
      key: 'collectionFee',
      header: t('labRatesPage.collectionFee'),
      accessor: (r) => r.collectionFee,
      sortable: true,
      render: (r) => (Number(r.collectionFee) > 0 ? formatCurrency(r.collectionFee) : '—'),
    },
  ];

  return (
    <div>
      <p className="mb-6 text-sm text-ink-muted">{t('labRatesPage.intro')}</p>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('labOrderTestsEditor.testCode')}>
          <input value={testCode} onChange={(e) => setTestCode(e.target.value)} placeholder="CBC" className={`${inputClass} w-32`} />
        </Field>
        <Field label={t('labRatesPage.baseCharge')}>
          <input type="number" min="0" step="0.01" value={baseCharge} onChange={(e) => setBaseCharge(e.target.value)} className={`${inputClass} w-28`} />
        </Field>
        <Field label={t('labRatesPage.collectionFee')}>
          <input type="number" min="0" step="0.01" value={collectionFee} onChange={(e) => setCollectionFee(e.target.value)} className={`${inputClass} w-28`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createRate.isPending}>
          {createRate.isPending ? t('common.adding') : t('labRatesPage.addRate')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      <DataTable
        columns={columns}
        rows={rates || []}
        rowKey="id"
        searchAccessors={[(r) => r.testCode]}
        defaultSortKey="testCode"
        renderExpanded={(rate) => <RateEditPanel rate={rate} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('labRatesPage.emptyTitle')}
        emptyDescription={t('labRatesPage.emptyDescription')}
      />
    </div>
  );
}

function RateEditPanel({ rate }) {
  const { t } = useTranslation();
  const updateRate = useUpdateLabRate(rate.id);
  const deleteRate = useDeleteLabRate(rate.id);
  const [baseCharge, setBaseCharge] = useState(String(rate.baseCharge));
  const [collectionFee, setCollectionFee] = useState(String(rate.collectionFee));
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updateRate.mutateAsync({ baseCharge: Number(baseCharge), collectionFee: Number(collectionFee) });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function handleDelete() {
    setRowError(null);
    try {
      await deleteRate.mutateAsync();
    } catch (err) {
      setRowError(err.message || t('labRatesPage.errorDelete'));
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('labOrderTestsEditor.testCode')}>
          <span className={`${inputClass} inline-block w-32 bg-slate-50 font-mono text-ink-muted`}>{rate.testCode}</span>
        </Field>
        <Field label={t('labRatesPage.baseCharge')}>
          <input type="number" min="0" step="0.01" value={baseCharge} onChange={(e) => setBaseCharge(e.target.value)} className={`${inputClass} w-28`} />
        </Field>
        <Field label={t('labRatesPage.collectionFee')}>
          <input type="number" min="0" step="0.01" value={collectionFee} onChange={(e) => setCollectionFee(e.target.value)} className={`${inputClass} w-28`} />
        </Field>
        <Button type="button" variant="accent" onClick={saveEdit} disabled={updateRate.isPending}>
          {t('common.save')}
        </Button>
        {/* True hard delete, no soft-deactivate fallback - same heavier-weight
            treatment as FeePolicies.jsx's own equivalent button. */}
        <Button type="button" variant="danger" size="sm" onClick={handleDelete}>
          {t('common.delete')}
        </Button>
      </div>
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
    </div>
  );
}
