import { useTranslation } from 'react-i18next';
import { ApiError } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { formatCurrency } from '../lib/format.js';
import ErrorBanner from './ErrorBanner.jsx';

/**
 * Shared invoice panel for AppointmentDetail.jsx/LabOrderDetail.jsx (phase
 * 15 backend, frontend phase M) - mirrors those pages' own Payments panel
 * shape (a list-or-empty-state plus one action), but there's at most one
 * invoice per owner (no list) and no edit form once generated - matches
 * AppointmentInvoiceController/LabOrderInvoiceController's own
 * generate-once, immutable design. A 404 on the GET means "not generated
 * yet", not an error - same convention as provider/Encounter.jsx's own
 * useEncounter 404 handling.
 *
 * `pdfUrl` (phase 16) - a same-origin `GET .../invoice/pdf` path, session
 * -cookie-authenticated like every other API call this app makes, so a
 * plain anchor needs no download-handling code of its own; only rendered
 * once an invoice actually exists.
 *
 * A real, pre-existing gap found and fixed alongside PaymentsPanel's own
 * (phase 16): the "Generate invoice" button rendered unconditionally even
 * though `AppointmentInvoiceController`/`LabOrderInvoiceController`'s own
 * generate endpoint is `front_desk`/`clinic_admin`-only - a `provider`
 * viewing a shared lab order would see a working-looking button that
 * 403s on click. Gated the same way, on the same two roles.
 *
 * `extraRoles` (phase 37/dispense billing) - same opt-in extra-role list
 * `PaymentsPanel`'s own `extraRoles` prop documents; defaults to `[]` so
 * neither existing caller's behavior changes.
 */
export default function InvoicePanel({ invoiceQuery, generateInvoice, pdfUrl, extraRoles = [] }) {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canGenerate = hasRole('front_desk') || hasRole('clinic_admin') || extraRoles.some((r) => hasRole(r));
  const notGenerated = invoiceQuery.isError && invoiceQuery.error instanceof ApiError && invoiceQuery.error.status === 404;

  if (invoiceQuery.isLoading) {
    return null;
  }

  if (invoiceQuery.isError && !notGenerated) {
    return (
      <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
        <ErrorBanner message={invoiceQuery.error?.message} onRetry={invoiceQuery.refetch} />
      </div>
    );
  }

  const invoice = invoiceQuery.data;

  return (
    <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-ink">{t('invoicePanel.title')}</h2>
        {invoice && pdfUrl ? (
          <a
            href={pdfUrl}
            target="_blank"
            rel="noreferrer"
            className="rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium text-ink hover:bg-slate-50"
          >
            {t('invoicePanel.downloadPdf')}
          </a>
        ) : !invoice && canGenerate ? (
          <button
            type="button"
            disabled={generateInvoice.isPending}
            onClick={() => generateInvoice.mutate()}
            className="rounded-lg bg-accent px-3 py-1.5 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            {generateInvoice.isPending ? t('invoicePanel.generating') : t('invoicePanel.generate')}
          </button>
        ) : null}
      </div>
      {invoice ? (
        <dl className="grid grid-cols-3 gap-3 text-sm">
          <div>
            <dt className="text-ink-muted">{t('invoicePanel.subtotal')}</dt>
            <dd className="font-mono text-ink">{formatCurrency(invoice.subtotalAmount)}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">{t('invoicePanel.tax')}</dt>
            <dd className="font-mono text-ink">{formatCurrency(invoice.taxAmount)}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">{t('invoicePanel.total')}</dt>
            <dd className="font-mono font-semibold text-ink">{formatCurrency(invoice.totalAmount)}</dd>
          </div>
        </dl>
      ) : (
        <p className="text-sm text-ink-muted">{t('invoicePanel.notGenerated')}</p>
      )}
      {generateInvoice.isError && (
        <div className="mt-3">
          <ErrorBanner message={generateInvoice.error?.message} />
        </div>
      )}
    </div>
  );
}
