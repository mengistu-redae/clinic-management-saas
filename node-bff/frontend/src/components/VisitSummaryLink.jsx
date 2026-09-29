import { useTranslation } from 'react-i18next';

/**
 * A plain same-origin download link for the phase-26 visit-summary PDF -
 * unlike InvoicePanel, there's no JSON resource to check for existence
 * first (the PDF is generated fresh on every request), so this is just
 * an anchor, no query/generate state at all. Session-cookie-authenticated
 * the same way InvoicePanel's own `pdfUrl` link already is - the browser
 * handles auth on a normal navigation, no fetch/blob code needed.
 */
export default function VisitSummaryLink({ href }) {
  const { t } = useTranslation();
  return (
    <a
      href={href}
      target="_blank"
      rel="noreferrer"
      className="mb-4 inline-flex w-fit items-center rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium text-ink hover:bg-slate-50"
    >
      {t('visitSummary.downloadPdf')}
    </a>
  );
}
