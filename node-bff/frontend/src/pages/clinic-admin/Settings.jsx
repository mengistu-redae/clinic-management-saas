import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useClinicSettings, useUpdateClinicSettings } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const str = (v) => (v === null || v === undefined ? '' : String(v));

function overridesToForm(o) {
  return {
    taxRatePercent: str(o?.taxRatePercent),
    rescheduleFeePatientPortal: str(o?.rescheduleFeePatientPortal),
    rescheduleFeeFrontDesk: str(o?.rescheduleFeeFrontDesk),
    rescheduleMinNoticeHours: str(o?.rescheduleMinNoticeHours),
    appointmentReminderLeadHours: str(o?.appointmentReminderLeadHours),
    supportPhone: str(o?.supportPhone),
    supportEmail: str(o?.supportEmail),
    address: str(o?.address),
    website: str(o?.website),
  };
}

const numOrNull = (v) => (v.trim() === '' ? null : Number(v));

/**
 * "General" tab of the settings hub - GET/POST /api/clinic/settings. A
 * singleton per clinic: one form, not a list. Each field left blank falls
 * back to the platform default shown beside it (full-replace on save, per
 * ClinicSettingsController - a blank field reverts that column to the
 * platform default, it isn't merged with what was there before).
 */
export default function ClinicAdminSettings() {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch } = useClinicSettings(true);
  const updateSettings = useUpdateClinicSettings();

  const [form, setForm] = useState(overridesToForm(null));
  const [formError, setFormError] = useState(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (data) setForm(overridesToForm(data.overrides));
  }, [data]);

  const set = (k) => (e) => {
    setForm((f) => ({ ...f, [k]: e.target.value }));
    setSaved(false);
  };

  async function handleSave(event) {
    event.preventDefault();
    setFormError(null);
    setSaved(false);
    try {
      await updateSettings.mutateAsync({
        taxRatePercent: numOrNull(form.taxRatePercent),
        rescheduleFeePatientPortal: numOrNull(form.rescheduleFeePatientPortal),
        rescheduleFeeFrontDesk: numOrNull(form.rescheduleFeeFrontDesk),
        rescheduleMinNoticeHours: numOrNull(form.rescheduleMinNoticeHours),
        appointmentReminderLeadHours: numOrNull(form.appointmentReminderLeadHours),
        supportPhone: form.supportPhone.trim() || null,
        supportEmail: form.supportEmail.trim() || null,
        address: form.address.trim() || null,
        website: form.website.trim() || null,
      });
      setSaved(true);
    } catch (err) {
      setFormError(err.message || t('settingsPage.errorSave'));
    }
  }

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (isError) return <ErrorBanner message={error?.message} onRetry={refetch} />;

  const d = data.defaults;

  return (
    <div className="max-w-2xl">
      <p className="mb-6 text-sm text-ink-muted">{t('settingsPage.intro')}</p>

      <form onSubmit={handleSave} className="flex flex-col gap-6">
        <Section title={t('settingsPage.taxSection')}>
          <Field label={t('settingsPage.taxRate')} hint={t('settingsPage.defaultHint', { value: d.taxRatePercent })}>
            <input type="number" step="0.01" min="0" max="100" value={form.taxRatePercent} onChange={set('taxRatePercent')} placeholder={String(d.taxRatePercent)} className={inputClass} />
          </Field>
        </Section>

        <Section title={t('settingsPage.rescheduleSection')}>
          <Field label={t('settingsPage.minNotice')} hint={t('settingsPage.defaultHint', { value: d.rescheduleMinNoticeHours })}>
            <input type="number" min="0" value={form.rescheduleMinNoticeHours} onChange={set('rescheduleMinNoticeHours')} placeholder={String(d.rescheduleMinNoticeHours)} className={inputClass} />
          </Field>
          <Field label={t('settingsPage.feePatientPortal')} hint={t('settingsPage.defaultHint', { value: d.rescheduleFeePatientPortal })}>
            <input type="number" step="0.01" min="0" value={form.rescheduleFeePatientPortal} onChange={set('rescheduleFeePatientPortal')} placeholder={String(d.rescheduleFeePatientPortal)} className={inputClass} />
          </Field>
          <Field label={t('settingsPage.feeFrontDesk')} hint={t('settingsPage.defaultHint', { value: d.rescheduleFeeFrontDesk })}>
            <input type="number" step="0.01" min="0" value={form.rescheduleFeeFrontDesk} onChange={set('rescheduleFeeFrontDesk')} placeholder={String(d.rescheduleFeeFrontDesk)} className={inputClass} />
          </Field>
          <p className="text-xs text-ink-muted">{t('settingsPage.tieredFeeNote')}</p>
        </Section>

        <Section title={t('settingsPage.remindersSection')}>
          <Field label={t('settingsPage.reminderLead')} hint={t('settingsPage.defaultHint', { value: d.appointmentReminderLeadHours })}>
            <input type="number" min="0" value={form.appointmentReminderLeadHours} onChange={set('appointmentReminderLeadHours')} placeholder={String(d.appointmentReminderLeadHours)} className={inputClass} />
          </Field>
        </Section>

        <Section title={t('settingsPage.contactSection')}>
          <p className="text-xs text-ink-muted">{t('settingsPage.contactNote')}</p>
          <Field label={t('settingsPage.supportPhone')}>
            <input value={form.supportPhone} onChange={set('supportPhone')} placeholder="+15551234567" className={inputClass} />
          </Field>
          <Field label={t('settingsPage.supportEmail')}>
            <input type="email" value={form.supportEmail} onChange={set('supportEmail')} className={inputClass} />
          </Field>
          <Field label={t('settingsPage.address')}>
            <input value={form.address} onChange={set('address')} className={inputClass} />
          </Field>
          <Field label={t('settingsPage.website')}>
            <input value={form.website} onChange={set('website')} placeholder="https://…" className={inputClass} />
          </Field>
        </Section>

        {formError && <ErrorBanner message={formError} />}

        <div className="flex items-center gap-3">
          <Button type="submit" variant="accent" disabled={updateSettings.isPending}>
            {updateSettings.isPending ? t('settingsPage.saving') : t('settingsPage.saveSettings')}
          </Button>
          <span aria-live="polite">
            {saved && <span className="text-sm text-success">{t('settingsPage.saved')}</span>}
          </span>
        </div>
      </form>
    </div>
  );
}

function Section({ title, children }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4">
      <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-ink-muted">{title}</h2>
      <div className="flex flex-col gap-3">{children}</div>
    </div>
  );
}

function Field({ label, hint, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 flex items-baseline justify-between gap-2">
        <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
        {hint && <span className="text-xs font-normal normal-case text-ink-muted">{hint}</span>}
      </span>
      {children}
    </label>
  );
}
