import { useState } from 'react';
import {
  usePlatformClinics,
  useCreateClinic,
  useUpdateClinic,
  useDeactivateClinic,
  useReactivateClinic,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatDateTime } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

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
      setFormError('Name, org alias, and domain are all required.');
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
      setFormError(err.message || 'Could not onboard this clinic - the org alias may already be taken.');
    }
  }

  return (
    <div>
      <h1 className="mb-1 text-2xl font-bold text-ink">Clinics</h1>
      <p className="mb-6 text-sm text-ink-muted">
        Onboarding a new clinic creates a real Keycloak Organization for its staff, then this platform's own record
        of it. Fill in the admin email/name below to also create an initial clinic_admin login for it - leave both
        blank to onboard the clinic with nobody able to log in yet, same as before.
      </p>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label="Name">
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Sunrise Family Clinic" className={`${inputClass} w-56`} />
        </Field>
        <Field label="Org alias">
          <input value={form.orgAlias} onChange={(e) => setForm({ ...form, orgAlias: e.target.value })} placeholder="sunrise-clinic" className={`${inputClass} w-44`} />
        </Field>
        <Field label="Domain">
          <input value={form.domain} onChange={(e) => setForm({ ...form, domain: e.target.value })} placeholder="sunriseclinic.example" className={`${inputClass} w-56`} />
        </Field>
        <Field label="Initial admin email (optional)">
          <input type="email" value={form.adminEmail} onChange={(e) => setForm({ ...form, adminEmail: e.target.value })} placeholder="admin@sunriseclinic.example" className={`${inputClass} w-56`} />
        </Field>
        <Field label="Initial admin name (optional)">
          <input value={form.adminFullName} onChange={(e) => setForm({ ...form, adminFullName: e.target.value })} placeholder="Jane Doe" className={`${inputClass} w-44`} />
        </Field>
        <button type="submit" disabled={createClinic.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
          {createClinic.isPending ? 'Provisioning…' : 'Onboard clinic'}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      {provisionedAdmin && (
        <div className="mb-4 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900">
          <p className="mb-1 font-semibold">Initial clinic_admin login created for {provisionedAdmin.email}</p>
          <p className="mb-2">
            Temporary password (shown once, not recoverable afterward - hand it to them out of band):{' '}
            <span className="font-mono font-semibold">{provisionedAdmin.temporaryPassword}</span>
          </p>
          <button type="button" onClick={() => setProvisionedAdmin(null)} className="text-amber-900 underline">
            Dismiss
          </button>
        </div>
      )}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && clinics?.length === 0 && (
        <EmptyState title="No clinics yet" description="Onboard your first clinic above." />
      )}

      {!isLoading && !isError && clinics?.length > 0 && (
        <div className="flex flex-col gap-2">
          {[...clinics].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)).map((clinic) => (
            <ClinicRow key={clinic.id} clinic={clinic} />
          ))}
        </div>
      )}
    </div>
  );
}

function ClinicRow({ clinic }) {
  const updateClinic = useUpdateClinic(clinic.id);
  const deactivateClinic = useDeactivateClinic(clinic.id);
  const reactivateClinic = useReactivateClinic(clinic.id);

  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(clinic.name);
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updateClinic.mutateAsync({ name: name.trim() });
      setEditing(false);
    } catch (err) {
      setRowError(err.message || 'Could not save changes.');
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
      setRowError(err.message || 'Could not update this clinic.');
    }
  }

  const pending = updateClinic.isPending || deactivateClinic.isPending || reactivateClinic.isPending;

  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4">
      {editing ? (
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Name">
            <input value={name} onChange={(e) => setName(e.target.value)} className={`${inputClass} w-56`} />
          </Field>
          <button type="button" onClick={saveEdit} disabled={updateClinic.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
            {updateClinic.isPending ? 'Saving…' : 'Save'}
          </button>
          <button type="button" onClick={() => setEditing(false)} className="text-sm text-ink-muted hover:underline">
            Cancel
          </button>
        </div>
      ) : (
        <div className="flex items-center justify-between">
          <div>
            <div className="mb-1 flex items-center gap-2">
              <StatusPill status={clinic.status} />
              <span className="text-sm font-semibold text-ink">{clinic.name}</span>
              <span className="font-mono text-xs text-ink-muted">{clinic.keycloakOrgId}</span>
            </div>
            <p className="text-xs text-ink-muted">Onboarded {formatDateTime(clinic.createdAt)}</p>
          </div>
          <div className="flex items-center gap-3 text-sm">
            <button type="button" onClick={() => { setRowError(null); setName(clinic.name); setEditing(true); }} className="text-brand hover:underline">
              Edit
            </button>
            <button type="button" onClick={toggleActive} disabled={pending} className="text-ink-muted hover:underline disabled:opacity-50">
              {clinic.status === 'active' ? 'Deactivate' : 'Reactivate'}
            </button>
          </div>
        </div>
      )}
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
    </div>
  );
}

function Field({ label, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
      {children}
    </label>
  );
}
