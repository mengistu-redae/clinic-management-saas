import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useImagingStudyRates, useCreateImagingStudyRate, useUpdateImagingStudyRate, useDeleteImagingStudyRate } from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { formatCurrency } from '../../lib/format.js';

const MODALITIES = ['xray', 'ultrasound', 'ct', 'mri', 'other'];

/**
 * "Imaging Rates" tab of the clinic-admin settings hub - GET/POST/POST
 * .../update/POST .../delete ImagingStudyRateController (phase 42
 * backend). Mirrors clinic-admin/LabRates.jsx's own shape exactly, minus
 * a collectionFee field - ImagingStudyRate has no equivalent column.
 * studyCode isn't editable once created, matching UpdateImagingStudyRateRequest's
 * own shape.
 */
export default function ClinicAdminImagingStudyRates() {
  const { t } = useTranslation();
  const { data: rates, isLoading, isError, error, refetch } = useImagingStudyRates(true);
  const createRate = useCreateImagingStudyRate();

  const [studyCode, setStudyCode] = useState('');
  const [studyName, setStudyName] = useState('');
  const [modality, setModality] = useState('xray');
  const [baseCharge, setBaseCharge] = useState('');
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    const base = Number(baseCharge);
    if (!studyCode.trim() || !studyName.trim() || baseCharge === '' || base < 0) {
      setFormError(t('imagingStudyRatesPage.errorFields'));
      return;
    }
    try {
      await createRate.mutateAsync({ studyCode: studyCode.trim().toUpperCase(), studyName: studyName.trim(), modality, baseCharge: base });
      setStudyCode('');
      setStudyName('');
      setBaseCharge('');
    } catch (err) {
      setFormError(err.message || t('imagingStudyRatesPage.errorCreate'));
    }
  }

  const columns = [
    { key: 'studyCode', header: t('imagingOrdersPage.studyCode'), accessor: (r) => r.studyCode, sortable: true, className: 'font-mono font-semibold' },
    { key: 'studyName', header: t('imagingOrdersPage.studyType'), accessor: (r) => r.studyName, sortable: true },
    { key: 'modality', header: t('imagingOrdersPage.modality'), accessor: (r) => r.modality, sortable: true, render: (r) => t(`imagingOrdersPage.modality${r.modality.charAt(0).toUpperCase()}${r.modality.slice(1)}`, { defaultValue: r.modality }) },
    { key: 'baseCharge', header: t('labRatesPage.baseCharge'), accessor: (r) => r.baseCharge, sortable: true, render: (r) => formatCurrency(r.baseCharge) },
  ];

  return (
    <div>
      <p className="mb-6 text-sm text-ink-muted">{t('imagingStudyRatesPage.intro')}</p>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('imagingOrdersPage.studyCode')}>
          <input value={studyCode} onChange={(e) => setStudyCode(e.target.value)} placeholder="XR-CHEST" className={`${inputClass} w-32`} />
        </Field>
        <Field label={t('imagingOrdersPage.studyType')}>
          <input value={studyName} onChange={(e) => setStudyName(e.target.value)} placeholder={t('imagingOrdersPage.studyTypePlaceholder')} className={`${inputClass} w-44`} />
        </Field>
        <Field label={t('imagingOrdersPage.modality')}>
          <select value={modality} onChange={(e) => setModality(e.target.value)} className={inputClass}>
            {MODALITIES.map((m) => (
              <option key={m} value={m}>{t(`imagingOrdersPage.modality${m.charAt(0).toUpperCase()}${m.slice(1)}`)}</option>
            ))}
          </select>
        </Field>
        <Field label={t('labRatesPage.baseCharge')}>
          <input type="number" min="0" step="0.01" value={baseCharge} onChange={(e) => setBaseCharge(e.target.value)} className={`${inputClass} w-28`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createRate.isPending}>
          {createRate.isPending ? t('common.adding') : t('imagingStudyRatesPage.addRate')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      <DataTable
        columns={columns}
        rows={rates || []}
        rowKey="id"
        searchAccessors={[(r) => r.studyCode, (r) => r.studyName]}
        defaultSortKey="studyCode"
        renderExpanded={(rate) => <RateEditPanel rate={rate} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('imagingStudyRatesPage.emptyTitle')}
        emptyDescription={t('imagingStudyRatesPage.emptyDescription')}
      />
    </div>
  );
}

function RateEditPanel({ rate }) {
  const { t } = useTranslation();
  const updateRate = useUpdateImagingStudyRate(rate.id);
  const deleteRate = useDeleteImagingStudyRate(rate.id);
  const [studyName, setStudyName] = useState(rate.studyName);
  const [modality, setModality] = useState(rate.modality);
  const [baseCharge, setBaseCharge] = useState(String(rate.baseCharge));
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updateRate.mutateAsync({ studyName: studyName.trim(), modality, baseCharge: Number(baseCharge) });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function handleDelete() {
    setRowError(null);
    try {
      await deleteRate.mutateAsync();
    } catch (err) {
      setRowError(err.message || t('imagingStudyRatesPage.errorDelete'));
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('imagingOrdersPage.studyCode')}>
          <span className={`${inputClass} inline-block w-32 bg-slate-50 font-mono text-ink-muted`}>{rate.studyCode}</span>
        </Field>
        <Field label={t('imagingOrdersPage.studyType')}>
          <input value={studyName} onChange={(e) => setStudyName(e.target.value)} className={`${inputClass} w-44`} />
        </Field>
        <Field label={t('imagingOrdersPage.modality')}>
          <select value={modality} onChange={(e) => setModality(e.target.value)} className={inputClass}>
            {MODALITIES.map((m) => (
              <option key={m} value={m}>{t(`imagingOrdersPage.modality${m.charAt(0).toUpperCase()}${m.slice(1)}`)}</option>
            ))}
          </select>
        </Field>
        <Field label={t('labRatesPage.baseCharge')}>
          <input type="number" min="0" step="0.01" value={baseCharge} onChange={(e) => setBaseCharge(e.target.value)} className={`${inputClass} w-28`} />
        </Field>
        <Button type="button" variant="accent" onClick={saveEdit} disabled={updateRate.isPending}>
          {t('common.save')}
        </Button>
        <Button type="button" variant="danger" size="sm" onClick={handleDelete}>
          {t('common.delete')}
        </Button>
      </div>
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
    </div>
  );
}
