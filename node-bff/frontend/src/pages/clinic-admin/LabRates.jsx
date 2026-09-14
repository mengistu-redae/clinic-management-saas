import { useState } from 'react';
import { useLabRates, useCreateLabRate, useUpdateLabRate, useDeleteLabRate } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * "Lab Rates" tab of the clinic-admin settings hub - GET/POST/POST
 * .../update/POST .../delete LabRateController. A test only becomes
 * orderable once a rate exists for its code (no fixed test catalog, see
 * NoLabRateConfiguredException) - testCode isn't editable once created,
 * matching UpdateLabTestRateRequest's own shape.
 */
export default function ClinicAdminLabRates() {
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
      setFormError('A test code and a non-negative base charge are required.');
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
      setFormError(err.message || 'Could not create this rate - a rate for this test code may already exist.');
    }
  }

  return (
    <div>
      <p className="mb-6 text-sm text-ink-muted">
        A lab test can only be ordered once a rate exists for its code - there's no separate fixed test catalog.
      </p>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label="Test code">
          <input value={testCode} onChange={(e) => setTestCode(e.target.value)} placeholder="CBC" className={`${inputClass} w-32`} />
        </Field>
        <Field label="Base charge">
          <input type="number" min="0" step="0.01" value={baseCharge} onChange={(e) => setBaseCharge(e.target.value)} className={`${inputClass} w-28`} />
        </Field>
        <Field label="Collection fee">
          <input type="number" min="0" step="0.01" value={collectionFee} onChange={(e) => setCollectionFee(e.target.value)} className={`${inputClass} w-28`} />
        </Field>
        <button type="submit" disabled={createRate.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {createRate.isPending ? 'Adding…' : 'Add rate'}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      {isLoading && <Skeleton className="h-24 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && rates?.length === 0 && (
        <EmptyState title="No lab rates configured" description="Add your first rate above." />
      )}

      {!isLoading && !isError && rates?.length > 0 && (
        <div className="flex flex-col gap-2">
          {rates.map((rate) => (
            <RateRow key={rate.id} rate={rate} />
          ))}
        </div>
      )}
    </div>
  );
}

function RateRow({ rate }) {
  const updateRate = useUpdateLabRate(rate.id);
  const deleteRate = useDeleteLabRate(rate.id);

  const [editing, setEditing] = useState(false);
  const [baseCharge, setBaseCharge] = useState(String(rate.baseCharge));
  const [collectionFee, setCollectionFee] = useState(String(rate.collectionFee));
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updateRate.mutateAsync({ baseCharge: Number(baseCharge), collectionFee: Number(collectionFee) });
      setEditing(false);
    } catch (err) {
      setRowError(err.message || 'Could not save changes.');
    }
  }

  async function handleDelete() {
    setRowError(null);
    try {
      await deleteRate.mutateAsync();
    } catch (err) {
      setRowError(err.message || 'Could not delete this rate.');
    }
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4">
      {editing ? (
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Test code">
            <span className={`${inputClass} inline-block w-32 bg-slate-50 font-mono text-ink-muted`}>{rate.testCode}</span>
          </Field>
          <Field label="Base charge">
            <input type="number" min="0" step="0.01" value={baseCharge} onChange={(e) => setBaseCharge(e.target.value)} className={`${inputClass} w-28`} />
          </Field>
          <Field label="Collection fee">
            <input type="number" min="0" step="0.01" value={collectionFee} onChange={(e) => setCollectionFee(e.target.value)} className={`${inputClass} w-28`} />
          </Field>
          <button type="button" onClick={saveEdit} disabled={updateRate.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
            Save
          </button>
          <button type="button" onClick={() => setEditing(false)} className="text-sm text-ink-muted hover:underline">
            Cancel
          </button>
        </div>
      ) : (
        <div className="flex items-center justify-between">
          <div>
            <span className="font-mono text-sm font-semibold text-ink">{rate.testCode}</span>
            <span className="ml-3 text-sm text-ink-muted">
              {formatCurrency(rate.baseCharge)} base
              {Number(rate.collectionFee) > 0 && ` + ${formatCurrency(rate.collectionFee)} collection`}
            </span>
          </div>
          <div className="flex items-center gap-3 text-sm">
            <button type="button" onClick={() => setEditing(true)} className="text-brand hover:underline">Edit</button>
            <button type="button" onClick={handleDelete} className="text-danger hover:underline">Delete</button>
          </div>
        </div>
      )}
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
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
