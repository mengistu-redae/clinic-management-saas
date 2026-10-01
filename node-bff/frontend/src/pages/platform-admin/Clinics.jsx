import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  usePlatformClinics,
  useCreateClinic,
  useUpdateClinic,
  useDeactivateClinic,
  useReactivateClinic,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { ClipboardIcon } from '../../components/icons.jsx';
import { formatDateTime } from '../../lib/format.js';

const emptyForm = { name: '', orgAlias: '', domain: '', adminEmail: '', adminFullName: '' };

/**
 * platform_admin clinic onboarding - GET/POST/POST .../update
 * PlatformController, ported from the reference bus-ticketing-saas
 * project's own platform/Operators.jsx (read in full before writing
 * anything) - same "the only create call in this app that reaches
 * Keycloak before Postgres" shape. Unlike that reference page, this one
 * offers Reactivate too - PlatformController has a real reactivate
 * endpoint (see CLAUDE.md's phase 6 write-up), not just deactivate.
 * `orgAlias`/`keycloakOrgId` isn't editable after creation - it's what
 * TenantContextFilter matches a staff token's organization claim against,
 * so changing it would silently break tenant resolution for every
 * existing staff login at that clinic.
 *
 * The admin email/full name fields are optional - closes the "no initial
 * clinic_admin login" gap (see CLAUDE.md's known gaps): when given, the
 * create response carries a one-time temporary password
 * (initialAdminTemporaryPassword) that's shown once right here and never
 * retrievable again - there's no SMTP configured for this realm in local
 * dev, so the platform_admin has to hand it to the new clinic's admin out
 * of band.
 */
export default function PlatformAdminClinics() {
  const { t } = useTranslation();
  const { data: clinics, isLoading, isError, error, refetch } = usePlatformClinics(true);
  const createClinic = useCreateClinic();

  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState(null);
  const [provisionedAdmin, setProvisionedAdmin] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    setProvisionedAdmin(null);
    if (!form.name.trim() || !form.orgAlias.trim() || !form.domain.trim()) {
      setFormError(t('clinicsPage.errorFieldsRequired'));
      return;
    }
    try {
      const result = await createClinic.mutateAsync({
        name: form.name.trim(),
        orgAlias: form.orgAlias.trim(),
        domain: form.domain.trim(),
        adminEmail: form.adminEmail.trim() || null,
        adminFullName: form.adminFullName.trim() || null,
      });
      if (result?.initialAdminTemporaryPassword) {
        setProvisionedAdmin({ email: form.adminEmail.trim(), temporaryPassword: result.initialAdminTemporaryPassword });
      }
      setForm(emptyForm);
    } catch (err) {
      setFormError(err.message || t('clinicsPage.errorOnboard'));
    }
  }

  const columns = [
    { key: 'name', header: t('common.name'), accessor: (c) => c.name, sortable: true, className: 'font-semibold' },
    { key: 'orgAlias', header: t('clinicsPage.orgAlias'), accessor: (c) => c.keycloakOrgId, sortable: true, className: 'font-mono text-xs' },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (c) => c.status,
      sortable: true,
      render: (c) => <StatusPill status={c.status} />,
    },
    {
      key: 'createdAt',
      header: t('clinicsPage.onboardedPrefix'),
      accessor: (c) => c.createdAt,
      sortAccessor: (c) => new Date(c.createdAt),
      sortable: true,
      render: (c) => formatDateTime(c.createdAt),
    },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.platformAdmin.clinics')} description={t('clinicsPage.intro')} />

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('common.name')}>
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Sunrise Family Clinic" className={`${inputClass} w-56`} />
        </Field>
        <Field label={t('clinicsPage.orgAlias')}>
          <input value={form.orgAlias} onChange={(e) => setForm({ ...form, orgAlias: e.target.value })} placeholder="sunrise-clinic" className={`${inputClass} w-44`} />
        </Field>
        <Field label={t('clinicsPage.domain')}>
          <input value={form.domain} onChange={(e) => setForm({ ...form, domain: e.target.value })} placeholder="sunriseclinic.example" className={`${inputClass} w-56`} />
        </Field>
        <Field label={t('clinicsPage.initialAdminEmail')}>
          <input type="email" value={form.adminEmail} onChange={(e) => setForm({ ...form, adminEmail: e.target.value })} placeholder="admin@sunriseclinic.example" className={`${inputClass} w-56`} />
        </Field>
        <Field label={t('clinicsPage.initialAdminName')}>
          <input value={form.adminFullName} onChange={(e) => setForm({ ...form, adminFullName: e.target.value })} placeholder="Jane Doe" className={`${inputClass} w-44`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createClinic.isPending}>
          {createClinic.isPending ? t('clinicsPage.provisioning') : t('clinicsPage.onboardClinic')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      {provisionedAdmin && (
        <div className="mb-4 rounded-xl border border-warning/30 bg-warning-light p-4 text-sm text-warning">
          <p className="mb-1 font-semibold">{t('clinicsPage.adminCreatedTitle', { email: provisionedAdmin.email })}</p>
          <p className="mb-2 flex flex-wrap items-center gap-2">
            {t('clinicsPage.tempPasswordNote')}
            <span className="font-mono font-semibold">{provisionedAdmin.temporaryPassword}</span>
            <CopyButton value={provisionedAdmin.temporaryPassword} />
          </p>
          <button type="button" onClick={() => setProvisionedAdmin(null)} className="underline">
            {t('clinicsPage.dismiss')}
          </button>
        </div>
      )}

      <DataTable
        columns={columns}
        rows={clinics || []}
        rowKey="id"
        searchAccessors={[(c) => c.name, (c) => c.keycloakOrgId]}
        defaultSortKey="createdAt"
        defaultSortDir="desc"
        renderExpanded={(clinic) => <ClinicEditPanel clinic={clinic} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('clinicsPage.emptyTitle')}
        emptyDescription={t('clinicsPage.emptyDescription')}
      />
    </PageContainer>
  );
}

function CopyButton({ value }) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // clipboard access can be denied/unavailable - the password is still visible to copy by hand
    }
  }

  const label = copied ? t('common.copied') : t('common.copy');

  return (
    <button
      type="button"
      onClick={handleCopy}
      title={label}
      aria-label={label}
      className="inline-flex items-center gap-1 rounded-lg border border-warning/40 px-2 py-1 text-xs font-semibold hover:bg-warning/10"
    >
      <ClipboardIcon className="h-3.5 w-3.5" />
      {label}
    </button>
  );
}

function ClinicEditPanel({ clinic }) {
  const { t } = useTranslation();
  const updateClinic = useUpdateClinic(clinic.id);
  const deactivateClinic = useDeactivateClinic(clinic.id);
  const reactivateClinic = useReactivateClinic(clinic.id);

  const [name, setName] = useState(clinic.name);
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updateClinic.mutateAsync({ name: name.trim() });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function toggleActive() {
    setRowError(null);
    try {
      if (clinic.status === 'active') {
        await deactivateClinic.mutateAsync();
      } else {
        await reactivateClinic.mutateAsync();
      }
    } catch (err) {
      setRowError(err.message || t('clinicsPage.errorUpdateClinic'));
    }
  }

  const pending = updateClinic.isPending || deactivateClinic.isPending || reactivateClinic.isPending;

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('common.name')}>
          <input value={name} onChange={(e) => setName(e.target.value)} className={`${inputClass} w-56`} />
        </Field>
        <Button type="button" variant="accent" onClick={saveEdit} disabled={updateClinic.isPending}>
          {updateClinic.isPending ? t('settingsPage.saving') : t('common.save')}
        </Button>
        {clinic.status === 'active' ? (
          // Deactivating a clinic locks out every staff login at that tenant -
          // a tenant-wide, consequential action, so it gets real visual weight
          // instead of the plain-text-link treatment lower-stakes toggles use.
          <Button type="button" variant="danger" size="sm" onClick={toggleActive} disabled={pending}>
            {t('common.deactivate')}
          </Button>
        ) : (
          <button type="button" onClick={toggleActive} disabled={pending} className="text-sm text-ink-muted hover:underline disabled:opacity-50">
            {t('common.reactivate')}
          </button>
        )}
      </div>
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
    </div>
  );
}
