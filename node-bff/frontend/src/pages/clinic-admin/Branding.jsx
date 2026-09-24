import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useClinicBranding, useUpdateClinicBranding } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { themeVars } from '../../lib/color.js';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const HEX = /^#[0-9a-fA-F]{6}$/;
const str = (v) => (v == null ? '' : String(v));

/**
 * "Branding" tab of the settings hub - GET/POST /api/clinic/branding, a
 * disjoint column group from the settings tab's own endpoint (see
 * ClinicBrandingController's javadoc) so saving one never wipes the other.
 * Reads the already-resolved GET view (not a raw override row - unlike
 * ClinicSettingsController, this endpoint has no {overrides, effective}
 * split) - displayName so pre-fills with the clinic's own legal name until
 * something's been saved here, which is harmless to resave as-is.
 */
export default function ClinicAdminBranding() {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch } = useClinicBranding(true);
  const updateBranding = useUpdateClinicBranding();

  const [form, setForm] = useState({ displayName: '', logoUrl: '', brandColor: '', accentColor: '', footerNote: '' });
  const [formError, setFormError] = useState(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (data) {
      setForm({
        displayName: str(data.displayName),
        logoUrl: str(data.logoUrl),
        brandColor: str(data.brandColor),
        accentColor: str(data.accentColor),
        footerNote: str(data.footerNote),
      });
    }
  }, [data]);

  const set = (k) => (e) => {
    setForm((f) => ({ ...f, [k]: e.target.value }));
    setSaved(false);
  };

  async function handleSave(event) {
    event.preventDefault();
    setFormError(null);
    setSaved(false);
    for (const [k, label] of [['brandColor', t('brandingPage.brandColor')], ['accentColor', t('brandingPage.accentColor')]]) {
      if (form[k].trim() && !HEX.test(form[k].trim())) {
        setFormError(t('brandingPage.errorColorHex', { label }));
        return;
      }
    }
    if (form.logoUrl.trim() && !/^https?:\/\/.+/.test(form.logoUrl.trim())) {
      setFormError(t('brandingPage.errorLogoUrl'));
      return;
    }
    try {
      await updateBranding.mutateAsync({
        displayName: form.displayName.trim() || null,
        logoUrl: form.logoUrl.trim() || null,
        brandColor: form.brandColor.trim() || null,
        accentColor: form.accentColor.trim() || null,
        footerNote: form.footerNote.trim() || null,
      });
      setSaved(true);
    } catch (err) {
      setFormError(err.message || t('brandingPage.errorSave'));
    }
  }

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (isError) return <ErrorBanner message={error?.message} onRetry={refetch} />;

  const previewBrand = HEX.test(form.brandColor.trim()) ? form.brandColor.trim() : '#1D4ED8';
  const previewAccent = HEX.test(form.accentColor.trim()) ? form.accentColor.trim() : '#F59E0B';

  return (
    <div className="max-w-2xl">
      <p className="mb-6 text-sm text-ink-muted">{t('brandingPage.intro')}</p>

      <form onSubmit={handleSave} className="flex flex-col gap-6">
        <Section title={t('brandingPage.identitySection')}>
          <Field label={t('brandingPage.displayName')} hint={t('brandingPage.displayNameHint')}>
            <input value={form.displayName} onChange={set('displayName')} placeholder="Clinic Management" className={inputClass} />
          </Field>
          <Field label={t('brandingPage.logoUrl')} hint={t('brandingPage.logoUrlHint')}>
            <input value={form.logoUrl} onChange={set('logoUrl')} placeholder="https://…/logo.png" className={inputClass} />
          </Field>
          {form.logoUrl.trim() && (
            <img
              src={form.logoUrl.trim()}
              alt="Logo preview"
              className="h-10 w-auto max-w-[10rem] rounded border border-slate-200 bg-white object-contain p-1"
            />
          )}
          <Field label={t('brandingPage.footerNote')} hint={t('brandingPage.footerNoteHint')}>
            <textarea value={form.footerNote} onChange={set('footerNote')} rows={2} className={inputClass} />
          </Field>
        </Section>

        <Section title={t('brandingPage.colorsSection')}>
          <ColorField label={t('brandingPage.brandColor')} value={form.brandColor} onChange={set('brandColor')} placeholder="#1D4ED8" />
          <ColorField label={t('brandingPage.accentColor')} value={form.accentColor} onChange={set('accentColor')} placeholder="#F59E0B" />
        </Section>

        <Section title={t('brandingPage.previewSection')}>
          <div
            className="overflow-hidden rounded-xl border border-slate-200"
            style={{ ...themeVars(previewBrand, 'brand'), ...themeVars(previewAccent, 'accent') }}
          >
            <div className="flex items-center gap-2 bg-brand px-4 py-2.5 text-white">
              {form.logoUrl.trim() && <img src={form.logoUrl.trim()} alt="" className="h-6 w-auto max-w-[7rem] object-contain" />}
              <span className="text-sm font-semibold">{form.displayName.trim() || 'Clinic Management'}</span>
            </div>
            <div className="flex items-center justify-between p-4">
              <span className="text-sm text-ink-muted">{t('nav.dashboard')}</span>
              <span className="rounded-lg bg-accent px-3 py-1.5 text-sm font-semibold text-white">{t('nav.frontDesk.bookWalkIn')}</span>
            </div>
          </div>
        </Section>

        {formError && <ErrorBanner message={formError} />}

        <div className="flex items-center gap-3">
          <button type="submit" disabled={updateBranding.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
            {updateBranding.isPending ? t('settingsPage.saving') : t('brandingPage.saveBranding')}
          </button>
          {saved && <span className="text-sm text-success">{t('brandingPage.saved')}</span>}
        </div>
      </form>
    </div>
  );
}

function ColorField({ label, value, onChange, placeholder }) {
  const valid = HEX.test(value.trim());
  return (
    <Field label={label}>
      <div className="flex items-center gap-2">
        <input
          type="color"
          value={valid ? value.trim() : placeholder}
          onChange={onChange}
          className="h-9 w-12 shrink-0 cursor-pointer rounded border border-slate-300"
        />
        <input value={value} onChange={onChange} placeholder={placeholder} className={`${inputClass} font-mono`} />
      </div>
    </Field>
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
