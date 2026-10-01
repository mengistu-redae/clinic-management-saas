import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useFeePolicies, useCreateFeePolicy, useUpdateFeePolicy, useDeleteFeePolicy, useProviders } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import DataTable from '../../components/DataTable.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';

/**
 * "Fee Policies" tab of the clinic-admin settings hub - GET/POST/POST
 * .../update/POST .../delete FeePolicyController. A specific provider's
 * tiers *replace* the clinic-wide default entirely (never merged, see
 * FeeCalculator) - grouped here by provider for the same reason the
 * reference project's RefundPolicies.jsx groups by route; each group's own
 * tiers now render as one small sortable DataTable.
 */
export default function ClinicAdminFeePolicies() {
  const { t } = useTranslation();
  const { data: policies, isLoading, isError, error, refetch } = useFeePolicies();
  const { data: providers } = useProviders(true);
  const createPolicy = useCreateFeePolicy();

  const providerById = Object.fromEntries((providers || []).map((p) => [p.id, p]));

  const [providerId, setProviderId] = useState('');
  const [cutoffHours, setCutoffHours] = useState('24');
  const [feePercent, setFeePercent] = useState('0');
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    try {
      await createPolicy.mutateAsync({
        providerId: providerId || undefined,
        cutoffHours: Number(cutoffHours),
        feePercent: Number(feePercent),
      });
      setCutoffHours('24');
      setFeePercent('0');
    } catch (err) {
      setFormError(err.message || t('feePoliciesPage.errorCreate'));
    }
  }

  const groups = new Map();
  (policies || []).forEach((p) => {
    const key = p.providerId || 'default';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(p);
  });
  // Clinic-wide default first, then providers by name.
  const orderedKeys = [...groups.keys()].sort((a, b) => {
    if (a === 'default') return -1;
    if (b === 'default') return 1;
    return (providerById[a]?.fullName || '').localeCompare(providerById[b]?.fullName || '');
  });

  const columns = [
    { key: 'cutoffHours', header: t('feePoliciesPage.cutoffHours'), accessor: (t2) => t2.cutoffHours, sortable: true },
    { key: 'feePercent', header: t('feePoliciesPage.feePercent'), accessor: (t2) => t2.feePercent, sortable: true, render: (t2) => `${t2.feePercent}%` },
  ];

  return (
    <div>
      <p className="mb-6 text-sm text-ink-muted">{t('feePoliciesPage.intro')}</p>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('feePoliciesPage.appliesTo')}>
          <select value={providerId} onChange={(e) => setProviderId(e.target.value)} className={`${inputClass} w-56`}>
            <option value="">{t('feePoliciesPage.clinicWideDefault')}</option>
            {(providers || []).map((p) => (
              <option key={p.id} value={p.id}>{p.fullName}</option>
            ))}
          </select>
        </Field>
        <Field label={t('feePoliciesPage.cutoffHours')}>
          <input type="number" min="0" value={cutoffHours} onChange={(e) => setCutoffHours(e.target.value)} className={`${inputClass} w-24`} />
        </Field>
        <Field label={t('feePoliciesPage.feePercent')}>
          <input type="number" min="0" max="100" value={feePercent} onChange={(e) => setFeePercent(e.target.value)} className={`${inputClass} w-24`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createPolicy.isPending}>
          {createPolicy.isPending ? t('common.adding') : t('feePoliciesPage.addTier')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && (policies || []).length === 0 && (
        <EmptyState title={t('feePoliciesPage.emptyTitle')} description={t('feePoliciesPage.emptyDescription')} />
      )}

      {!isLoading && !isError && orderedKeys.length > 0 && (
        <div className="flex flex-col gap-4">
          {orderedKeys.map((key) => (
            <div key={key} className="rounded-xl border border-slate-200 bg-surface p-4">
              <h3 className="mb-3 text-sm font-semibold text-ink">
                {key === 'default' ? t('feePoliciesPage.clinicWideDefault') : providerById[key]?.fullName || t('feePoliciesPage.unknownProvider')}
              </h3>
              <DataTable
                columns={columns}
                rows={groups.get(key)}
                rowKey="id"
                defaultSortKey="cutoffHours"
                defaultSortDir="desc"
                renderExpanded={(tier) => <TierEditPanel tier={tier} />}
              />
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TierEditPanel({ tier }) {
  const { t } = useTranslation();
  const updatePolicy = useUpdateFeePolicy(tier.id);
  const deletePolicy = useDeleteFeePolicy(tier.id);

  const [cutoffHours, setCutoffHours] = useState(String(tier.cutoffHours));
  const [feePercent, setFeePercent] = useState(String(tier.feePercent));
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updatePolicy.mutateAsync({ cutoffHours: Number(cutoffHours), feePercent: Number(feePercent) });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function handleDelete() {
    setRowError(null);
    try {
      await deletePolicy.mutateAsync();
    } catch (err) {
      setRowError(err.message || t('feePoliciesPage.errorDelete'));
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('feePoliciesPage.cutoffHours')}>
          <input type="number" min="0" value={cutoffHours} onChange={(e) => setCutoffHours(e.target.value)} className={`${inputClass} w-24`} />
        </Field>
        <Field label={t('feePoliciesPage.feePercent')}>
          <input type="number" min="0" max="100" value={feePercent} onChange={(e) => setFeePercent(e.target.value)} className={`${inputClass} w-24`} />
        </Field>
        <Button type="button" variant="accent" onClick={saveEdit} disabled={updatePolicy.isPending}>
          {t('common.save')}
        </Button>
        {/* A true hard delete (no soft-deactivate fallback, unlike every
            other entity in this role) - a plain text link gave it no more
            visual weight than a reversible action. Still no confirm
            dialog, matching this app's own convention; just heavier. */}
        <Button type="button" variant="danger" size="sm" onClick={handleDelete}>
          {t('common.delete')}
        </Button>
      </div>
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
    </div>
  );
}
