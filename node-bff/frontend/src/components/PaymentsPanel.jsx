import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { usePaymentRefunds, useCreateRefund } from '../api/queries.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { formatCurrency } from '../lib/format.js';
import ErrorBanner from './ErrorBanner.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-2 py-1.5 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * One payment row plus its own refund affordance (phase 16) - a separate
 * component, not inlined in the list `.map`, since each row needs its own
 * `usePaymentRefunds` query (React's rules of hooks forbid calling a hook
 * conditionally/in a loop at the parent's own top level).
 */
function PaymentRow({ payment, canRefund }) {
  const { t } = useTranslation();
  const refundsQuery = usePaymentRefunds(payment.id);
  const createRefund = useCreateRefund(payment.id);
  const [refunding, setRefunding] = useState(false);
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState(null);

  const refunds = refundsQuery.data || [];
  const refundedTotal = refunds.reduce((sum, r) => sum + Number(r.amount), 0);
  const remaining = Number(payment.amount) - refundedTotal;
  const fullyRefunded = remaining <= 0;

  async function handleRefund(event) {
    event.preventDefault();
    setError(null);
    const value = Number(amount);
    if (!amount || Number.isNaN(value) || value <= 0) {
      setError(t('paymentsPanel.errorAmount'));
      return;
    }
    try {
      await createRefund.mutateAsync({ amount: value, reason: reason.trim() || undefined });
      setAmount('');
      setReason('');
      setRefunding(false);
    } catch (err) {
      setError(err.message || t('paymentsPanel.errorRefund'));
    }
  }

  return (
    <li className="flex flex-col gap-1.5 border-b border-slate-100 pb-2 text-sm last:border-0 last:pb-0">
      <div className="flex items-center justify-between text-ink">
        <span className="capitalize">
          {t(`paymentMethod.${payment.method}`, { defaultValue: payment.method })}
          {payment.transactionId && <span className="font-mono text-xs text-ink-muted"> ({payment.transactionId})</span>}
        </span>
        <div className="flex items-center gap-2">
          <span className="font-mono">{formatCurrency(payment.amount)}</span>
          {canRefund && !fullyRefunded && (
            <button
              type="button"
              onClick={() => setRefunding((r) => !r)}
              className="text-xs font-semibold text-danger hover:underline"
            >
              {t('paymentsPanel.refund')}
            </button>
          )}
        </div>
      </div>
      {refundedTotal > 0 && (
        <p className="text-xs text-ink-muted">
          {fullyRefunded
            ? t('paymentsPanel.fullyRefunded')
            : t('paymentsPanel.partiallyRefunded', { amount: formatCurrency(refundedTotal) })}
        </p>
      )}
      {refunding && (
        <form onSubmit={handleRefund} className="flex flex-wrap items-end gap-2 rounded-lg border border-slate-200 bg-slate-50 p-2">
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('paymentsPanel.refundAmount')}</span>
            <input
              type="number"
              min="0"
              max={remaining}
              step="0.01"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              className={`${inputClass} w-24`}
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('paymentsPanel.refundReasonOptional')}</span>
            <input value={reason} onChange={(e) => setReason(e.target.value)} className={`${inputClass} w-40`} />
          </label>
          <button
            type="submit"
            disabled={createRefund.isPending}
            className="rounded-lg bg-danger px-3 py-1.5 text-xs font-semibold text-white hover:bg-danger/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {createRefund.isPending ? t('paymentsPanel.refunding') : t('paymentsPanel.confirmRefund')}
          </button>
          <button type="button" onClick={() => setRefunding(false)} className="text-xs text-ink-muted hover:underline">
            {t('common.cancel')}
          </button>
        </form>
      )}
      {error && <ErrorBanner message={error} />}
    </li>
  );
}

/**
 * Shared Payments panel for front-desk/AppointmentDetail.jsx and
 * lab-orders/LabOrderDetail.jsx (phase 16) - previously two
 * near-identical inline copies; consolidated here specifically because
 * the new refund affordance would otherwise have needed writing twice.
 * `totalOwed`, when given, renders the lab-order page's own "collected of
 * total" summary line; omitted on the appointment page, matching its
 * existing behavior exactly (no owed-total concept there today).
 *
 * A real, pre-existing gap found and fixed while consolidating this:
 * `LabOrderDetail.jsx` rendered the record-payment form unconditionally,
 * even though `LabOrderPaymentController.recordPayment` (and now
 * `PaymentController.refund`) are `front_desk`/`clinic_admin`-only - a
 * `provider` viewing a shared lab order (this page is reachable by both
 * roles) would see a working-looking form that 403s on submit. Both the
 * record-payment form and the new refund button are now gated on
 * `hasRole('front_desk') || hasRole('clinic_admin')`, matching the
 * backend exactly; `front-desk/AppointmentDetail.jsx` is already
 * route-gated to those same two roles, so this is a no-op there.
 */
export default function PaymentsPanel({ paymentsQuery, createPayment, totalOwed }) {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canRecordOrRefund = hasRole('front_desk') || hasRole('clinic_admin');

  const [paymentAmount, setPaymentAmount] = useState('');
  const [paymentMethod, setPaymentMethod] = useState('cash');
  const [paymentTxnId, setPaymentTxnId] = useState('');
  const [paymentError, setPaymentError] = useState(null);

  const payments = paymentsQuery.data || [];
  const collected = payments.reduce((sum, p) => sum + Number(p.amount), 0);

  async function handleRecordPayment(event) {
    event.preventDefault();
    setPaymentError(null);
    const amount = Number(paymentAmount);
    if (!paymentAmount || Number.isNaN(amount) || amount <= 0) {
      setPaymentError(t('fdAppointmentDetail.errorAmount'));
      return;
    }
    try {
      await createPayment.mutateAsync({ method: paymentMethod, amount, transactionId: paymentTxnId.trim() || undefined });
      setPaymentAmount('');
      setPaymentTxnId('');
    } catch (err) {
      setPaymentError(err.message || t('fdAppointmentDetail.errorRecordPayment'));
    }
  }

  return (
    <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-ink">{t('fdAppointmentDetail.payments')}</h2>
        {totalOwed != null && (
          <span className="text-sm text-ink-muted">
            {t('labOrderDetail.collectedOf', { collected: formatCurrency(collected), total: formatCurrency(totalOwed) })}
          </span>
        )}
      </div>
      {payments.length > 0 && (
        <ul className="mb-4 flex flex-col gap-2">
          {payments.map((p) => (
            <PaymentRow key={p.id} payment={p} canRefund={canRecordOrRefund} />
          ))}
        </ul>
      )}
      {canRecordOrRefund && (
        <form onSubmit={handleRecordPayment} className="flex flex-wrap items-end gap-3">
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('fdAppointmentDetail.method')}</span>
            <select value={paymentMethod} onChange={(e) => setPaymentMethod(e.target.value)} className={inputClass}>
              <option value="cash">{t('paymentMethod.cash')}</option>
              <option value="card">{t('paymentMethod.card')}</option>
              <option value="mobile_money">{t('paymentMethod.mobile_money')}</option>
              <option value="insurance">{t('paymentMethod.insurance')}</option>
            </select>
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('fdAppointmentDetail.amount')}</span>
            <input
              type="number"
              min="0"
              step="0.01"
              value={paymentAmount}
              onChange={(e) => setPaymentAmount(e.target.value)}
              className={`${inputClass} w-28`}
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('fdAppointmentDetail.txnIdOptional')}</span>
            <input value={paymentTxnId} onChange={(e) => setPaymentTxnId(e.target.value)} className={`${inputClass} w-40`} />
          </label>
          <button
            type="submit"
            disabled={createPayment.isPending}
            className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            {createPayment.isPending ? t('fdAppointmentDetail.recording') : t('fdAppointmentDetail.recordPayment')}
          </button>
        </form>
      )}
      {paymentError && (
        <div className="mt-3">
          <ErrorBanner message={paymentError} />
        </div>
      )}
    </div>
  );
}
