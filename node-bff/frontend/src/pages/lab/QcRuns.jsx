import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQcRuns, useCreateQcRun } from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { formatDateTime } from '../../lib/format.js';

const PASS_STYLES = {
  true: 'bg-success-light text-success',
  false: 'bg-danger-light text-danger',
};

const emptyForm = { instrumentIdentifier: '', analyteName: '', controlMaterialLot: '', expectedRangeLow: '', expectedRangeHigh: '', observedValue: '' };

/**
 * Lab module L4 backend, L8 frontend - a genuinely append-only log
 * (no edit/delete endpoint exists, matching the backend's own
 * audit-only-table shape), so this mirrors DrugInteractions.jsx's own
 * create-form-above-DataTable shape minus its edit/delete panel. `pass`
 * is computed server-side, never entered here - flag-only, no
 * enforcement (the pinned fork's answer): a failed run shows here but
 * never blocks real result entry anywhere else in this app.
 */
export default function QcRuns() {
  const { t } = useTranslation();
  const { data: runs, isLoading, isError, error, refetch } = useQcRuns(true);
  const createRun = useCreateQcRun();

  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState(null);

  function setField(field, value) {
    setForm((prev) => ({ ...prev, [field]: value }));
  }

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.instrumentIdentifier.trim() || !form.analyteName.trim() || !form.controlMaterialLot.trim() || !form.observedValue.trim()) {
      setFormError(t('qcRunsPage.errorRequired'));
      return;
    }
    const low = Number(form.expectedRangeLow);
    const high = Number(form.expectedRangeHigh);
    if (form.expectedRangeLow === '' || form.expectedRangeHigh === '' || Number.isNaN(low) || Number.isNaN(high)) {
      setFormError(t('qcRunsPage.errorRangeRequired'));
      return;
    }
    try {
      await createRun.mutateAsync({
        instrumentIdentifier: form.instrumentIdentifier.trim(),
        analyteName: form.analyteName.trim(),
        controlMaterialLot: form.controlMaterialLot.trim(),
        expectedRangeLow: low,
        expectedRangeHigh: high,
        observedValue: form.observedValue.trim(),
      });
      setForm(emptyForm);
    } catch (err) {
      setFormError(err.message || t('qcRunsPage.errorCreate'));
    }
  }

  const columns = [
    { key: 'instrumentIdentifier', header: t('qcRunsPage.instrument'), accessor: (r) => r.instrumentIdentifier, sortable: true, className: 'font-semibold' },
    { key: 'analyteName', header: t('qcRunsPage.analyteName'), accessor: (r) => r.analyteName, sortable: true },
    { key: 'controlMaterialLot', header: t('qcRunsPage.controlMaterialLot'), accessor: (r) => r.controlMaterialLot },
    { key: 'observedValue', header: t('qcRunsPage.observedValue'), accessor: (r) => r.observedValue, render: (r) => `${r.observedValue} (${r.expectedRangeLow}–${r.expectedRangeHigh})` },
    {
      key: 'pass',
      header: t('qcRunsPage.result'),
      accessor: (r) => String(r.pass),
      sortable: true,
      render: (r) => (
        <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${PASS_STYLES[String(r.pass)]}`}>
          {r.pass ? t('qcRunsPage.pass') : t('qcRunsPage.fail')}
        </span>
      ),
    },
    { key: 'performedAt', header: t('qcRunsPage.performedAt'), accessor: (r) => r.performedAt, sortable: true, render: (r) => formatDateTime(r.performedAt) },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.lab.qcLog')} description={t('qcRunsPage.intro')} />

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('qcRunsPage.instrument')}>
          <input value={form.instrumentIdentifier} onChange={(e) => setField('instrumentIdentifier', e.target.value)} className={`${inputClass} w-36`} />
        </Field>
        <Field label={t('qcRunsPage.analyteName')}>
          <input value={form.analyteName} onChange={(e) => setField('analyteName', e.target.value)} className={`${inputClass} w-32`} />
        </Field>
        <Field label={t('qcRunsPage.controlMaterialLot')}>
          <input value={form.controlMaterialLot} onChange={(e) => setField('controlMaterialLot', e.target.value)} className={`${inputClass} w-32`} />
        </Field>
        <Field label={t('qcRunsPage.expectedRangeLow')}>
          <input type="number" step="0.0001" value={form.expectedRangeLow} onChange={(e) => setField('expectedRangeLow', e.target.value)} className={`${inputClass} w-24`} />
        </Field>
        <Field label={t('qcRunsPage.expectedRangeHigh')}>
          <input type="number" step="0.0001" value={form.expectedRangeHigh} onChange={(e) => setField('expectedRangeHigh', e.target.value)} className={`${inputClass} w-24`} />
        </Field>
        <Field label={t('qcRunsPage.observedValue')}>
          <input value={form.observedValue} onChange={(e) => setField('observedValue', e.target.value)} className={`${inputClass} w-24`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createRun.isPending}>
          {createRun.isPending ? t('common.adding') : t('qcRunsPage.logRun')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      <DataTable
        columns={columns}
        rows={runs || []}
        rowKey="id"
        searchAccessors={[(r) => r.instrumentIdentifier, (r) => r.analyteName]}
        defaultSortKey="performedAt"
        defaultSortDir="desc"
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('qcRunsPage.emptyTitle')}
        emptyDescription={t('qcRunsPage.emptyDescription')}
      />
    </PageContainer>
  );
}
