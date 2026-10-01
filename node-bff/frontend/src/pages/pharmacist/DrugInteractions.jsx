import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useDrugInteractionPairs,
  useCreateDrugInteractionPair,
  useUpdateDrugInteractionPair,
  useDeleteDrugInteractionPair,
  useMedications,
} from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';

const SEVERITIES = ['mild', 'moderate', 'severe'];

/**
 * GET/POST/POST .../update/POST .../delete DrugInteractionPairController -
 * a true hard delete (not soft-deactivate), same shape as
 * clinic-admin/LabRates.jsx. medicationAId/medicationBId are immutable
 * once created (correcting which two medications a pair covers means
 * delete+recreate, matching the backend's own UpdateDrugInteractionPairRequest
 * shape), so the edit panel only ever touches severity/description.
 * severity has no server-side allow-list - the <select> below is a
 * client-side UX convenience only, matching the entity's own javadoc
 * convention (mild/moderate/severe), not a new backend constraint.
 */
export default function DrugInteractions() {
  const { t } = useTranslation();
  const { data: pairs, isLoading, isError, error, refetch } = useDrugInteractionPairs(true);
  const activeMedications = useMedications(true, 'active');
  // All statuses, purely for resolving a pair's own medication names - a
  // since-deactivated medication already paired should stay displayable,
  // not fall back to a raw id (the same lesson phase 35 fixed live for
  // Purchase Orders/Assets).
  const allMedications = useMedications(true);
  const createPair = useCreateDrugInteractionPair();

  const [medicationAId, setMedicationAId] = useState('');
  const [medicationBId, setMedicationBId] = useState('');
  const [severity, setSeverity] = useState('');
  const [description, setDescription] = useState('');
  const [formError, setFormError] = useState(null);

  const medicationById = useMemo(
    () => Object.fromEntries((allMedications.data || []).map((m) => [m.id, m])),
    [allMedications.data],
  );

  function medicationName(id) {
    return medicationById[id]?.name || id;
  }

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!medicationAId || !medicationBId) {
      setFormError(t('drugInteractionsPage.errorMedicationsRequired'));
      return;
    }
    try {
      await createPair.mutateAsync({
        medicationAId,
        medicationBId,
        severity: severity || undefined,
        description: description.trim() || undefined,
      });
      setMedicationAId('');
      setMedicationBId('');
      setSeverity('');
      setDescription('');
    } catch (err) {
      setFormError(err.message || t('drugInteractionsPage.errorCreate'));
    }
  }

  const columns = [
    { key: 'medicationA', header: t('drugInteractionsPage.medicationA'), accessor: (p) => medicationName(p.medicationAId), sortable: true, className: 'font-semibold' },
    { key: 'medicationB', header: t('drugInteractionsPage.medicationB'), accessor: (p) => medicationName(p.medicationBId), sortable: true, className: 'font-semibold' },
    { key: 'severity', header: t('drugInteractionsPage.severity'), accessor: (p) => p.severity || '', render: (p) => (p.severity ? t(`drugInteractionsPage.severity_${p.severity}`, { defaultValue: p.severity }) : '—') },
    { key: 'description', header: t('drugInteractionsPage.description'), accessor: (p) => p.description || '', render: (p) => p.description || '—' },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.pharmacist.drugInteractions')} description={t('drugInteractionsPage.intro')} />

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('drugInteractionsPage.medicationA')}>
          <select value={medicationAId} onChange={(e) => setMedicationAId(e.target.value)} className={`${inputClass} w-48`}>
            <option value="">{t('booking.select')}</option>
            {(activeMedications.data || []).map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
          </select>
        </Field>
        <Field label={t('drugInteractionsPage.medicationB')}>
          <select value={medicationBId} onChange={(e) => setMedicationBId(e.target.value)} className={`${inputClass} w-48`}>
            <option value="">{t('booking.select')}</option>
            {(activeMedications.data || []).map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
          </select>
        </Field>
        <Field label={t('drugInteractionsPage.severity')}>
          <select value={severity} onChange={(e) => setSeverity(e.target.value)} className={`${inputClass} w-36`}>
            <option value="">{t('drugInteractionsPage.severityUnset')}</option>
            {SEVERITIES.map((s) => <option key={s} value={s}>{t(`drugInteractionsPage.severity_${s}`)}</option>)}
          </select>
        </Field>
        <Field label={t('drugInteractionsPage.description')}>
          <input value={description} onChange={(e) => setDescription(e.target.value)} className={`${inputClass} w-64`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createPair.isPending}>
          {createPair.isPending ? t('common.adding') : t('drugInteractionsPage.addPair')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      <DataTable
        columns={columns}
        rows={pairs || []}
        rowKey="id"
        searchAccessors={[(p) => medicationName(p.medicationAId), (p) => medicationName(p.medicationBId)]}
        defaultSortKey="medicationA"
        renderExpanded={(pair) => <PairEditPanel pair={pair} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('drugInteractionsPage.emptyTitle')}
        emptyDescription={t('drugInteractionsPage.emptyDescription')}
      />
    </PageContainer>
  );
}

function PairEditPanel({ pair }) {
  const { t } = useTranslation();
  const updatePair = useUpdateDrugInteractionPair(pair.id);
  const deletePair = useDeleteDrugInteractionPair(pair.id);
  const [severity, setSeverity] = useState(pair.severity || '');
  const [description, setDescription] = useState(pair.description || '');
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updatePair.mutateAsync({ severity: severity || null, description: description.trim() || null });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function handleDelete() {
    setRowError(null);
    try {
      await deletePair.mutateAsync();
    } catch (err) {
      setRowError(err.message || t('drugInteractionsPage.errorDelete'));
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('drugInteractionsPage.severity')}>
          <select value={severity} onChange={(e) => setSeverity(e.target.value)} className={`${inputClass} w-36`}>
            <option value="">{t('drugInteractionsPage.severityUnset')}</option>
            {SEVERITIES.map((s) => <option key={s} value={s}>{t(`drugInteractionsPage.severity_${s}`)}</option>)}
          </select>
        </Field>
        <Field label={t('drugInteractionsPage.description')}>
          <input value={description} onChange={(e) => setDescription(e.target.value)} className={`${inputClass} w-64`} />
        </Field>
        <Button type="button" variant="accent" onClick={saveEdit} disabled={updatePair.isPending}>
          {t('common.save')}
        </Button>
        <Button type="button" variant="danger" size="sm" onClick={handleDelete} disabled={deletePair.isPending}>
          {t('common.delete')}
        </Button>
      </div>
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
    </div>
  );
}
