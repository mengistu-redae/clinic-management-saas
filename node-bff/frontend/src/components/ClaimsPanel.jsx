import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useInvoiceClaims,
  useCreateClaim,
  useSubmitClaim,
  useRecordClaimAdjudication,
  useAppealClaim,
  useCloseClaim,
  useInsurancePolicies,
} from '../api/queries.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { formatCurrency } from '../lib/format.js';
import StatusPill from './StatusPill.jsx';
import Skeleton from './Skeleton.jsx';
import ErrorBanner from './ErrorBanner.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-2 py-1.5 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const ADJUDICATION_OUTCOMES = ['paid', 'partially_paid', 'denied'];

/**
 * One claim's own lifecycle actions (phase 40 backend) - a separate
 * component, not inlined in the list `.map`, matching `PaymentRow`'s own
 * "each row drives its own mutations" shape. `invoiceId` is only needed so
 * each action hook invalidates the right `['invoice-claims', invoiceId]`
 * list - the claim itself is addressed by id in every endpoint.
 *
 * At most one inline action form is open at a time (`action` state) - the
 * same "click a link, a small form appears below" shape `PaymentRow`'s own
 * refund affordance and `Medications.jsx`'s write-off form already use.
 * `submit` is available from both `draft` (first submission) and
 * `submitted` (correcting the claim number in place) - mirrors
 * `ClaimService.submit`'s own deliberate non-idempotent re-call.
 */
function ClaimRow({ claim, invoiceId }) {
  const { t } = useTranslation();
  const submitClaim = useSubmitClaim(invoiceId);
  const recordAdjudication = useRecordClaimAdjudication(invoiceId);
  const appealClaim = useAppealClaim(invoiceId);
  const closeClaim = useCloseClaim(invoiceId);

  const [action, setAction] = useState(null);
  const [error, setError] = useState(null);
  const [claimNumber, setClaimNumber] = useState(claim.claimNumber || '');
  const [outcome, setOutcome] = useState('paid');
  const [allowedAmount, setAllowedAmount] = useState('');
  const [paidAmount, setPaidAmount] = useState('');
  const [patientResponsibilityAmount, setPatientResponsibilityAmount] = useState('');
  const [denialReason, setDenialReason] = useState('');
  const [appealReason, setAppealReason] = useState('');
  const [closeNotes, setCloseNotes] = useState('');

  function toggle(name) {
    setError(null);
    setAction((current) => (current === name ? null : name));
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setError(null);
    try {
      await submitClaim.mutateAsync({ claimId: claim.id, body: { claimNumber: claimNumber.trim() || undefined } });
      setAction(null);
    } catch (err) {
      setError(err.message || t('claimsPanel.errorSubmit'));
    }
  }

  async function handleAdjudicate(event) {
    event.preventDefault();
    setError(null);
    if (outcome === 'denied') {
      if (!denialReason.trim()) {
        setError(t('claimsPanel.errorDenialReasonRequired'));
        return;
      }
    } else if (allowedAmount === '' || paidAmount === '' || patientResponsibilityAmount === '') {
      setError(t('claimsPanel.errorAmountsRequired'));
      return;
    }
    try {
      await recordAdjudication.mutateAsync({
        claimId: claim.id,
        body: {
          outcome,
          allowedAmount: outcome === 'denied' ? undefined : Number(allowedAmount),
          paidAmount: outcome === 'denied' ? undefined : Number(paidAmount),
          patientResponsibilityAmount: outcome === 'denied' ? undefined : Number(patientResponsibilityAmount),
          denialReason: outcome === 'denied' ? denialReason.trim() : undefined,
        },
      });
      setAction(null);
    } catch (err) {
      setError(err.message || t('claimsPanel.errorAdjudicate'));
    }
  }

  async function handleAppeal(event) {
    event.preventDefault();
    setError(null);
    if (!appealReason.trim()) {
      setError(t('claimsPanel.errorAppealReasonRequired'));
      return;
    }
    try {
      await appealClaim.mutateAsync({ claimId: claim.id, body: { appealReason: appealReason.trim() } });
      setAction(null);
    } catch (err) {
      setError(err.message || t('claimsPanel.errorAppeal'));
    }
  }

  async function handleClose(event) {
    event.preventDefault();
    setError(null);
    try {
      await closeClaim.mutateAsync({ claimId: claim.id, body: { notes: closeNotes.trim() || undefined } });
      setAction(null);
    } catch (err) {
      setError(err.message || t('claimsPanel.errorClose'));
    }
  }

  const canSubmit = claim.status === 'draft' || claim.status === 'submitted';
  const canAdjudicate = claim.status === 'submitted' || claim.status === 'appealed';
  const canAppeal = claim.status === 'denied';
  const canClose = claim.status !== 'closed';
  const hasAdjudicationAmounts = claim.allowedAmount != null || claim.paidAmount != null || claim.patientResponsibilityAmount != null;

  return (
    <li className="rounded-lg border border-slate-100 px-3 py-2 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <StatusPill status={claim.status} />
          {claim.claimNumber && <span className="font-mono text-xs text-ink-muted">#{claim.claimNumber}</span>}
        </div>
        <span className="font-mono text-ink">{formatCurrency(claim.billedAmount)}</span>
      </div>

      {hasAdjudicationAmounts && (
        <dl className="mt-2 grid grid-cols-3 gap-2 text-xs">
          <div>
            <dt className="text-ink-muted">{t('claimsPanel.allowedAmount')}</dt>
            <dd className="font-mono text-ink">{formatCurrency(claim.allowedAmount)}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">{t('claimsPanel.paidAmount')}</dt>
            <dd className="font-mono text-ink">{formatCurrency(claim.paidAmount)}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">{t('claimsPanel.patientResponsibilityAmount')}</dt>
            <dd className="font-mono text-ink">{formatCurrency(claim.patientResponsibilityAmount)}</dd>
          </div>
        </dl>
      )}
      {claim.denialReason && <p className="mt-1 text-xs text-danger">{claim.denialReason}</p>}
      {claim.appealReason && <p className="mt-1 text-xs text-ink-muted">{t('claimsPanel.appealReasonPrefix', { reason: claim.appealReason })}</p>}
      {claim.notes && <p className="mt-1 text-xs text-ink-muted">{claim.notes}</p>}

      <div className="mt-2 flex flex-wrap gap-3 text-xs font-semibold">
        {canSubmit && (
          <button type="button" onClick={() => toggle('submit')} className="text-brand-text hover:underline">
            {claim.status === 'draft' ? t('claimsPanel.submit') : t('claimsPanel.correctClaimNumber')}
          </button>
        )}
        {canAdjudicate && (
          <button type="button" onClick={() => toggle('adjudicate')} className="text-brand-text hover:underline">
            {t('claimsPanel.recordAdjudication')}
          </button>
        )}
        {canAppeal && (
          <button type="button" onClick={() => toggle('appeal')} className="text-brand-text hover:underline">
            {t('claimsPanel.appeal')}
          </button>
        )}
        {canClose && (
          <button type="button" onClick={() => toggle('close')} className="text-ink-muted hover:underline">
            {t('claimsPanel.close')}
          </button>
        )}
      </div>

      {action === 'submit' && (
        <form onSubmit={handleSubmit} className="mt-2 flex flex-wrap items-end gap-2 rounded-lg border border-slate-200 bg-slate-50 p-2">
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('claimsPanel.claimNumberOptional')}</span>
            <input value={claimNumber} onChange={(e) => setClaimNumber(e.target.value)} className={`${inputClass} w-32`} />
          </label>
          <button type="submit" disabled={submitClaim.isPending} className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
            {t('claimsPanel.confirm')}
          </button>
          <button type="button" onClick={() => setAction(null)} className="text-xs text-ink-muted hover:underline">{t('common.cancel')}</button>
        </form>
      )}

      {action === 'adjudicate' && (
        <form onSubmit={handleAdjudicate} className="mt-2 flex flex-wrap items-end gap-2 rounded-lg border border-slate-200 bg-slate-50 p-2">
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('claimsPanel.outcome')}</span>
            <select value={outcome} onChange={(e) => setOutcome(e.target.value)} className={inputClass}>
              {ADJUDICATION_OUTCOMES.map((o) => (
                <option key={o} value={o}>{t(`status.${o}`)}</option>
              ))}
            </select>
          </label>
          {outcome === 'denied' ? (
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('claimsPanel.denialReason')}</span>
              <input value={denialReason} onChange={(e) => setDenialReason(e.target.value)} className={`${inputClass} w-48`} />
            </label>
          ) : (
            <>
              <label className="block">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('claimsPanel.allowedAmount')}</span>
                <input type="number" min="0" step="0.01" value={allowedAmount} onChange={(e) => setAllowedAmount(e.target.value)} className={`${inputClass} w-24`} />
              </label>
              <label className="block">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('claimsPanel.paidAmount')}</span>
                <input type="number" min="0" step="0.01" value={paidAmount} onChange={(e) => setPaidAmount(e.target.value)} className={`${inputClass} w-24`} />
              </label>
              <label className="block">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('claimsPanel.patientResponsibilityAmount')}</span>
                <input type="number" min="0" step="0.01" value={patientResponsibilityAmount} onChange={(e) => setPatientResponsibilityAmount(e.target.value)} className={`${inputClass} w-24`} />
              </label>
            </>
          )}
          <button type="submit" disabled={recordAdjudication.isPending} className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
            {t('claimsPanel.confirm')}
          </button>
          <button type="button" onClick={() => setAction(null)} className="text-xs text-ink-muted hover:underline">{t('common.cancel')}</button>
        </form>
      )}

      {action === 'appeal' && (
        <form onSubmit={handleAppeal} className="mt-2 flex flex-wrap items-end gap-2 rounded-lg border border-slate-200 bg-slate-50 p-2">
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('claimsPanel.appealReason')}</span>
            <input value={appealReason} onChange={(e) => setAppealReason(e.target.value)} className={`${inputClass} w-56`} />
          </label>
          <button type="submit" disabled={appealClaim.isPending} className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
            {t('claimsPanel.confirm')}
          </button>
          <button type="button" onClick={() => setAction(null)} className="text-xs text-ink-muted hover:underline">{t('common.cancel')}</button>
        </form>
      )}

      {action === 'close' && (
        <form onSubmit={handleClose} className="mt-2 flex flex-wrap items-end gap-2 rounded-lg border border-slate-200 bg-slate-50 p-2">
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('claimsPanel.closeNotesOptional')}</span>
            <input value={closeNotes} onChange={(e) => setCloseNotes(e.target.value)} className={`${inputClass} w-56`} />
          </label>
          <button type="submit" disabled={closeClaim.isPending} className="rounded-lg bg-danger px-3 py-1.5 text-xs font-semibold text-white hover:bg-danger/90 disabled:cursor-not-allowed disabled:opacity-50">
            {t('claimsPanel.confirm')}
          </button>
          <button type="button" onClick={() => setAction(null)} className="text-xs text-ink-muted hover:underline">{t('common.cancel')}</button>
        </form>
      )}

      {error && <p className="mt-1 text-xs text-danger">{error}</p>}
    </li>
  );
}

/**
 * Insurance claims against one already-issued invoice (phase 40 backend) -
 * mounted next to `InvoicePanel`/`PaymentsPanel` on
 * `front-desk/AppointmentDetail.jsx` only (never `provider/Encounter.jsx`)
 * since `ClaimController`'s own gate is `front_desk`+`clinic_admin`, with
 * deliberately **no** provider read access at all - unlike every section in
 * `PatientChart.jsx`, there's no "read-only for provider" carve-out here.
 * The internal `canAccess` check is defensive (the host page is already
 * route-gated to those two roles), matching `InvoicePanel`'s own precedent.
 *
 * Only rendered once an invoice actually exists and the owner has a known
 * patient - a guest/walk-in invoice has no patient to bill insurance for at
 * all (`ClaimService.createClaim`'s own 400), so the host page skips
 * mounting this rather than this component rendering a permanent error.
 */
export default function ClaimsPanel({ invoiceId, patientId }) {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canAccess = hasRole('front_desk') || hasRole('clinic_admin');
  const claimsQuery = useInvoiceClaims(invoiceId);
  const policiesQuery = useInsurancePolicies(patientId);
  const createClaim = useCreateClaim(invoiceId);

  const [policyId, setPolicyId] = useState('');
  const [notes, setNotes] = useState('');
  const [formError, setFormError] = useState(null);

  if (!canAccess) {
    return null;
  }

  const claims = claimsQuery.data || [];
  const activePolicies = (policiesQuery.data || []).filter((p) => p.status === 'active');

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!policyId) {
      setFormError(t('claimsPanel.errorPolicyRequired'));
      return;
    }
    try {
      await createClaim.mutateAsync({ insurancePolicyId: policyId, notes: notes.trim() || undefined });
      setNotes('');
      setPolicyId('');
    } catch (err) {
      setFormError(err.message || t('claimsPanel.errorCreateClaim'));
    }
  }

  return (
    <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
      <h2 className="mb-3 text-sm font-semibold text-ink">{t('claimsPanel.title')}</h2>
      {claimsQuery.isLoading && <Skeleton className="h-12 w-full" />}
      {claimsQuery.isError && <ErrorBanner message={claimsQuery.error?.message} onRetry={claimsQuery.refetch} />}
      {!claimsQuery.isLoading && !claimsQuery.isError && claims.length === 0 && (
        <p className="text-sm text-ink-muted">{t('claimsPanel.noClaims')}</p>
      )}
      {claims.length > 0 && (
        <ul className="mb-4 flex flex-col gap-3">
          {claims.map((c) => (
            <ClaimRow key={c.id} claim={c} invoiceId={invoiceId} />
          ))}
        </ul>
      )}
      {activePolicies.length > 0 ? (
        <form onSubmit={handleCreate} className="flex flex-wrap items-end gap-3">
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('claimsPanel.policy')}</span>
            <select value={policyId} onChange={(e) => setPolicyId(e.target.value)} className={inputClass}>
              <option value="">{t('claimsPanel.selectPolicy')}</option>
              {activePolicies.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.payerName} ({t(`patientChart.${p.rank}`, { defaultValue: p.rank })}) · {p.memberId}
                </option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('claimsPanel.notesOptional')}</span>
            <input value={notes} onChange={(e) => setNotes(e.target.value)} className={`${inputClass} w-48`} />
          </label>
          <button type="submit" disabled={createClaim.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
            {createClaim.isPending ? t('claimsPanel.filing') : t('claimsPanel.fileClaim')}
          </button>
        </form>
      ) : (
        <p className="text-sm text-ink-muted">{t('claimsPanel.noActivePolicies')}</p>
      )}
      {formError && <div className="mt-3"><ErrorBanner message={formError} /></div>}
    </div>
  );
}
