import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMyLabOrders } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';
import PageContainer from '../../components/PageContainer.jsx';

/**
 * Read-only patient view of one lab order - there's no dedicated
 * GET /api/my-lab-orders/{id} endpoint (only the list), so this filters
 * the same GET /api/my-lab-orders list MyLabOrders.jsx already fetches,
 * same "no extra endpoint needed" spirit as elsewhere in this app. Every
 * staff action (status transitions/confirm-and-order/payments) stays
 * off this page entirely - a patient only ever reads. Result values ARE
 * shown once resulted - only the anonymous /track-lab-order view hides
 * them, per the phase-7 visibility decision (resulted-but-unreviewed is
 * visible immediately, not gated on review).
 */
export default function MyLabOrderDetail() {
  const { t } = useTranslation();
  const { id } = useParams();
  const query = useMyLabOrders(true);
  const resolved = (query.data || []).find(({ order }) => order.id === id);

  if (query.isLoading) {
    return <Skeleton className="h-48 w-full max-w-xl" />;
  }
  if (query.isError) {
    return <ErrorBanner message={query.error?.message} onRetry={query.refetch} />;
  }
  if (!resolved) {
    return <ErrorBanner message={t('myLabOrderDetail.notFound')} />;
  }

  const { order, tests } = resolved;
  const showResults = order.status === 'resulted' || order.status === 'reviewed';

  return (
    <PageContainer width="xl">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-ink">{t('myLabOrderDetail.title')}</h1>
          <p className="font-mono text-xs text-ink-muted">{order.orderRef}</p>
        </div>
        <StatusPill status={order.status} />
      </div>

      {order.status === 'requested' && (
        <div className="mb-4 rounded-lg bg-warning-light px-3 py-2 text-sm text-warning">
          {t('myLabOrderDetail.pendingReview')}
        </div>
      )}

      <div className="rounded-xl border border-slate-200 bg-surface p-5">
        <dl className="grid grid-cols-2 gap-3 text-sm">
          {order.totalCost != null && (
            <div><dt className="text-ink-muted">{t('invoicePanel.total')}</dt><dd className="font-mono font-semibold text-ink">{formatCurrency(order.totalCost)}</dd></div>
          )}
          <div><dt className="text-ink-muted">{t('trackLabOrder.ordered')}</dt><dd className="text-ink">{formatDateTime(order.orderedAt)}</dd></div>
          {order.specimenCollectedAt && <div><dt className="text-ink-muted">{t('trackLabOrder.specimenCollected')}</dt><dd className="text-ink">{formatDateTime(order.specimenCollectedAt)}</dd></div>}
          {order.sentAt && <div><dt className="text-ink-muted">{t('trackLabOrder.sentToLab')}</dt><dd className="text-ink">{formatDateTime(order.sentAt)}</dd></div>}
          {order.resultedAt && <div><dt className="text-ink-muted">{t('trackLabOrder.resulted')}</dt><dd className="text-ink">{formatDateTime(order.resultedAt)}</dd></div>}
        </dl>
        {order.notes && <p className="mt-3 text-sm text-ink-muted">{order.notes}</p>}

        <div className="mt-4 overflow-x-auto border-t border-slate-100 pt-4">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-ink-muted">
                <th className="pb-1 pr-3 font-semibold">{t('myLabOrderDetail.test')}</th>
                {showResults && <th className="pb-1 font-semibold">{t('myLabOrderDetail.result')}</th>}
              </tr>
            </thead>
            <tbody>
              {tests.map((t2) => (
                <tr key={t2.id} className="border-t border-slate-100">
                  <td className="py-1 pr-3 text-ink">{t2.testName}</td>
                  {showResults && (
                    <td className="py-1 text-ink">
                      {t2.resultValue ? (
                        <>
                          {t2.resultValue}{t2.resultUnit && ` ${t2.resultUnit}`}
                          {t2.referenceRange && <span className="ml-1 text-xs text-ink-muted">(ref: {t2.referenceRange})</span>}
                          {t2.abnormalFlag && <span className="ml-1 text-xs font-semibold text-danger">{t('myLabOrderDetail.abnormal')}</span>}
                        </>
                      ) : '—'}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </PageContainer>
  );
}
