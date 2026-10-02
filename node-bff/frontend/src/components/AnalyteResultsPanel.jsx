import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAnalyteResults, useEnterAnalyteResults, useAcknowledgeCriticalResult } from '../api/queries.js';
import ErrorBanner from './ErrorBanner.jsx';
import Button from './Button.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-2 py-1.5 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const FLAG_STYLES = {
  normal: 'bg-success-light text-success',
  abnormal: 'bg-warning-light text-warning',
  critical: 'bg-danger-light text-danger',
  unflagged: 'bg-slate-100 text-ink-muted',
};

/**
 * Lab module L2/L3 - structured per-analyte results for one LabOrderTest,
 * alongside (not replacing) LabOrderDetail.jsx's own pre-existing flat
 * result form. New in L8 - this structured path had zero frontend before
 * this phase, even though L2/L3 built the full backend (auto-flagging,
 * critical-range detection, acknowledgment) for it. Viewing (and the
 * mounting page's own role check) matches AnalyteResultController's
 * widened read gate (provider+clinic_admin+lab_technician) - entering
 * results needs `canEnterResults` (lab_technician+clinic_admin, the
 * backend's own write gate); acknowledging a critical result needs
 * `canAcknowledgeCritical` (provider+clinic_admin - the ordering
 * clinician's own job, not lab_technician's).
 */
export default function AnalyteResultsPanel({ orderId, test, canEnterResults, canAcknowledgeCritical }) {
  const { t } = useTranslation();
  const resultsQuery = useAnalyteResults(orderId, test.id);
  const enterResults = useEnterAnalyteResults(orderId, test.id);
  const acknowledge = useAcknowledgeCriticalResult(orderId, test.id);

  const [entering, setEntering] = useState(false);
  const [rows, setRows] = useState([{ analyteName: '', value: '' }]);
  const [error, setError] = useState(null);
  const [ackError, setAckError] = useState(null);

  const results = resultsQuery.data || [];

  function startEntry() {
    setError(null);
    setRows(results.length > 0 ? results.map((r) => ({ analyteName: r.analyteName, value: r.value })) : [{ analyteName: '', value: '' }]);
    setEntering(true);
  }

  function updateRow(index, field, value) {
    setRows((prev) => prev.map((r, i) => (i === index ? { ...r, [field]: value } : r)));
  }

  function addRow() {
    setRows((prev) => [...prev, { analyteName: '', value: '' }]);
  }

  function removeRow(index) {
    setRows((prev) => prev.filter((_, i) => i !== index));
  }

  async function handleSave(event) {
    event.preventDefault();
    setError(null);
    const cleaned = rows.filter((r) => r.analyteName.trim() && r.value.trim());
    if (cleaned.length === 0) {
      setError(t('analyteResultsPanel.errorAtLeastOneRow'));
      return;
    }
    try {
      await enterResults.mutateAsync({ results: cleaned.map((r) => ({ analyteName: r.analyteName.trim(), value: r.value.trim() })) });
      setEntering(false);
    } catch (err) {
      setError(err.message || t('analyteResultsPanel.errorSave'));
    }
  }

  async function handleAcknowledge(resultId) {
    setAckError(null);
    try {
      await acknowledge.mutateAsync(resultId);
    } catch (err) {
      setAckError(err.message || t('analyteResultsPanel.errorAcknowledge'));
    }
  }

  // Nothing entered yet and this viewer can't enter anything either (e.g. a provider on a test
  // no lab_technician has touched yet) - an empty panel with no action would just be noise.
  if (results.length === 0 && !canEnterResults && !entering) {
    return null;
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-5">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-ink">{t('analyteResultsPanel.title', { testName: test.testName })}</h2>
        {canEnterResults && !entering && (
          <button type="button" onClick={startEntry} className="text-xs font-semibold text-accent hover:underline">
            {results.length > 0 ? t('analyteResultsPanel.editResults') : t('analyteResultsPanel.enterResults')}
          </button>
        )}
      </div>

      {results.length > 0 && !entering && (
        <ul className="flex flex-col gap-2">
          {results.map((r) => {
            const isCritical = r.flag === 'critical';
            const needsAck = isCritical && !r.criticalAcknowledgedAt;
            return (
              <li key={r.id} className={`flex flex-col gap-1 rounded-lg p-2 text-sm ${needsAck ? 'border border-danger/40 bg-danger-light' : ''}`}>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="font-semibold text-ink">
                    {r.analyteName}: <span className="font-mono">{r.value}</span>{r.unit && <span className="text-ink-muted"> {r.unit}</span>}
                  </span>
                  <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold capitalize ${FLAG_STYLES[r.flag] || FLAG_STYLES.unflagged}`}>
                    {t(`analyteResultsPanel.flag_${r.flag}`, { defaultValue: r.flag })}
                  </span>
                </div>
                {r.referenceRangeDisplay && <p className="text-xs text-ink-muted">{t('analyteResultsPanel.referenceRange', { range: r.referenceRangeDisplay })}</p>}
                {needsAck && canAcknowledgeCritical && (
                  <div className="flex items-center gap-2">
                    <p className="text-xs font-semibold text-danger">{t('analyteResultsPanel.criticalUnacknowledged')}</p>
                    <button type="button" onClick={() => handleAcknowledge(r.id)} disabled={acknowledge.isPending} className="rounded-lg bg-danger px-2.5 py-1 text-xs font-semibold text-white hover:bg-danger/90 disabled:opacity-50">
                      {t('analyteResultsPanel.acknowledge')}
                    </button>
                  </div>
                )}
                {needsAck && !canAcknowledgeCritical && (
                  <p className="text-xs font-semibold text-danger">{t('analyteResultsPanel.criticalUnacknowledged')}</p>
                )}
                {isCritical && r.criticalAcknowledgedAt && (
                  <p className="text-xs text-ink-muted">{t('analyteResultsPanel.acknowledged')}</p>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {ackError && <div className="mt-2"><ErrorBanner message={ackError} /></div>}

      {entering && (
        <form onSubmit={handleSave} className="flex flex-col gap-2">
          {rows.map((row, index) => (
            <div key={index} className="flex flex-wrap items-end gap-2">
              <label className="block">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('analyteResultsPanel.analyteName')}</span>
                <input value={row.analyteName} onChange={(e) => updateRow(index, 'analyteName', e.target.value)} className={`${inputClass} w-40`} />
              </label>
              <label className="block">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('analyteResultsPanel.value')}</span>
                <input value={row.value} onChange={(e) => updateRow(index, 'value', e.target.value)} className={`${inputClass} w-32`} />
              </label>
              {rows.length > 1 && (
                <button type="button" onClick={() => removeRow(index)} className="pb-2 text-xs text-ink-muted hover:underline">{t('labOrderTestsEditor.remove')}</button>
              )}
            </div>
          ))}
          <div className="flex items-center gap-3">
            <button type="button" onClick={addRow} className="text-xs font-semibold text-accent hover:underline">{t('analyteResultsPanel.addAnalyte')}</button>
            <Button type="submit" variant="accent" disabled={enterResults.isPending}>
              {enterResults.isPending ? t('settingsPage.saving') : t('common.save')}
            </Button>
            <button type="button" onClick={() => setEntering(false)} className="text-xs text-ink-muted hover:underline">{t('common.cancel')}</button>
          </div>
          {error && <ErrorBanner message={error} />}
        </form>
      )}
    </div>
  );
}
