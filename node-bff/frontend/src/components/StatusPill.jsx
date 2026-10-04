import { useTranslation } from 'react-i18next';

/**
 * Style map covers every status vocabulary this app has: the check-in state
 * machine (booked -> checked_in -> roomed -> with_provider -> checked_out,
 * plus no_show/cancelled - com.clinicops.appointment), the lab-order
 * lifecycle (requested -> ordered -> specimen_collected -> in_transit ->
 * resulted -> reviewed, plus cancelled - com.clinicops.laborder), the
 * per-specimen lifecycle (lab module L1/L5 - pending_collection ->
 * collected -> in_transit -> received -> processing -> completed, or
 * rejected/sent_to_reference_lab), and the generic active/inactive used by
 * providers/rooms/appointment-types/clinics.
 */
const STYLES = {
  // appointments
  booked: 'bg-brand-light text-brand-text',
  checked_in: 'bg-warning-light text-warning',
  roomed: 'bg-warning-light text-warning',
  with_provider: 'bg-warning-light text-warning',
  checked_out: 'bg-success-light text-success',
  no_show: 'bg-danger-light text-danger',
  cancelled: 'bg-slate-100 text-ink-muted',
  // lab orders
  requested: 'bg-slate-100 text-ink-muted',
  ordered: 'bg-brand-light text-brand-text',
  // imaging orders (phase 42 backend, frontend phase W)
  scheduled: 'bg-warning-light text-warning',
  in_progress: 'bg-warning-light text-warning',
  specimen_collected: 'bg-warning-light text-warning',
  in_transit: 'bg-warning-light text-warning',
  resulted: 'bg-accent-light text-accent',
  reviewed: 'bg-success-light text-success',
  // specimens (lab module L1/L5)
  pending_collection: 'bg-slate-100 text-ink-muted',
  collected: 'bg-warning-light text-warning',
  received: 'bg-warning-light text-warning',
  processing: 'bg-warning-light text-warning',
  completed: 'bg-success-light text-success',
  rejected: 'bg-danger-light text-danger',
  sent_to_reference_lab: 'bg-accent-light text-accent',
  // generic active/inactive
  active: 'bg-success-light text-success',
  inactive: 'bg-slate-100 text-ink-muted',
  // insurance claims (phase 40 backend)
  draft: 'bg-slate-100 text-ink-muted',
  submitted: 'bg-brand-light text-brand-text',
  paid: 'bg-success-light text-success',
  partially_paid: 'bg-warning-light text-warning',
  denied: 'bg-danger-light text-danger',
  appealed: 'bg-accent-light text-accent',
  closed: 'bg-slate-100 text-ink-muted',
};

export default function StatusPill({ status }) {
  const { t } = useTranslation();
  const style = STYLES[status] || 'bg-slate-100 text-ink-muted';
  // Falls back to the raw status value (English, underscore-split) for a
  // status this app doesn't know about yet - same "never blank" fallback
  // this component always had, just no longer the only path.
  const label = t(`status.${status}`, { defaultValue: String(status).replace(/_/g, ' ') });
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold capitalize ${style}`}>
      {label}
    </span>
  );
}
