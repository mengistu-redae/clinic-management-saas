import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useProviders,
  useRooms,
  useCreateProvider,
  useUpdateProvider,
  useLinkProviderLogin,
  useUnlinkProviderLogin,
  useProviderWorkingHours,
  useCreateWorkingHours,
  useRemoveWorkingHours,
  useUploadProviderSignature,
  useRemoveProviderSignature,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import Button from '../../components/Button.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import Field, { inputClass } from '../../components/Field.jsx';

// 0=Sunday..6=Saturday - matches CreateWorkingHoursRequest's own convention (see SlotGenerator), NOT java.time.DayOfWeek's ISO numbering.
const DAY_KEYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

/** Phase 13 - matches ProviderController.VALID_EMPLOYMENT_STATUSES exactly. */
const EMPLOYMENT_STATUSES = ['full_time', 'part_time', 'locum'];

/**
 * clinic-admin provider management - GET/POST/POST .../update
 * ProviderController, plus two nested per-provider panels toggled inline
 * rather than separate routes: working hours (ProviderWorkingHoursController)
 * and login linking (link-login/unlink-login). All providers shown
 * regardless of status (unlike the booking flow's active-only lists) - an
 * admin needs to see and reactivate deactivated ones too. Rendered as a
 * searchable/sortable DataTable (modern-UI redesign) - clicking a row
 * expands the same edit form + hours/login/signature panels this page
 * always had, just re-hosted under DataTable's shared expand mechanism.
 */
export default function ClinicAdminProviders() {
  const { t } = useTranslation();
  const { data: providers, isLoading, isError, error, refetch } = useProviders(true);
  const { data: rooms } = useRooms(true);
  const createProvider = useCreateProvider();

  const [form, setForm] = useState({ fullName: '', specialty: '', roomId: '', licenseNumber: '', licenseExpiry: '', employmentStatus: '' });
  const [formError, setFormError] = useState(null);
  // A clinic's own provider roster accumulates departed/inactive staff
  // alongside active ones with no way to hide them (2026-10-01 search/filter
  // audit) - defaults to "all" since an admin reactivating someone still
  // needs to find them.
  const [statusFilter, setStatusFilter] = useState('');
  const visibleProviders = (providers || []).filter((p) => !statusFilter || p.status === statusFilter);

  const roomById = Object.fromEntries((rooms || []).map((r) => [r.id, r]));

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.fullName.trim()) {
      setFormError(t('providersPage.errorNameRequired'));
      return;
    }
    try {
      await createProvider.mutateAsync({
        fullName: form.fullName.trim(),
        specialty: form.specialty.trim() || undefined,
        roomId: form.roomId || undefined,
        licenseNumber: form.licenseNumber.trim() || undefined,
        licenseExpiry: form.licenseExpiry || undefined,
        employmentStatus: form.employmentStatus || undefined,
      });
      setForm({ fullName: '', specialty: '', roomId: '', licenseNumber: '', licenseExpiry: '', employmentStatus: '' });
    } catch (err) {
      setFormError(err.message || t('providersPage.errorCreate'));
    }
  }

  const columns = [
    { key: 'fullName', header: t('providersPage.fullName'), accessor: (p) => p.fullName, sortable: true, className: 'font-semibold' },
    { key: 'specialty', header: t('providersPage.specialty'), accessor: (p) => p.specialty || t('providersPage.noSpecialty'), sortable: true },
    { key: 'room', header: t('providersPage.room'), accessor: (p) => (p.roomId ? roomById[p.roomId]?.name : null), sortable: true, render: (p) => (p.roomId && roomById[p.roomId]?.name) || '—' },
    {
      key: 'employment',
      header: t('providersPage.employment'),
      accessor: (p) => (p.employmentStatus ? t(`employmentStatus.${p.employmentStatus}`) : null),
      sortable: true,
      render: (p) => (p.employmentStatus ? t(`employmentStatus.${p.employmentStatus}`) : '—'),
    },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (p) => p.status,
      sortable: true,
      render: (p) => <StatusPill status={p.status} />,
    },
  ];

  return (
    <PageContainer width="lg">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-ink">{t('nav.clinicAdmin.providers')}</h1>
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          aria-label={t('common.filterByStatus')}
          className={`${inputClass} w-40`}
        >
          <option value="">{t('common.all')}</option>
          <option value="active">{t('status.active')}</option>
          <option value="inactive">{t('status.inactive')}</option>
        </select>
      </div>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('providersPage.fullName')}>
          <input value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} placeholder="Dr. Jane Doe" className={`${inputClass} w-48`} />
        </Field>
        <Field label={t('providersPage.specialty')}>
          <input value={form.specialty} onChange={(e) => setForm({ ...form, specialty: e.target.value })} placeholder="General practice" className={`${inputClass} w-48`} />
        </Field>
        <Field label={t('providersPage.room')}>
          <select value={form.roomId} onChange={(e) => setForm({ ...form, roomId: e.target.value })} className={`${inputClass} w-40`}>
            <option value="">{t('providersPage.none')}</option>
            {(rooms || []).map((r) => (
              <option key={r.id} value={r.id}>{r.name}</option>
            ))}
          </select>
        </Field>
        <Field label={t('providersPage.licenseNumberOptional')}>
          <input value={form.licenseNumber} onChange={(e) => setForm({ ...form, licenseNumber: e.target.value })} className={`${inputClass} w-36`} />
        </Field>
        <Field label={t('providersPage.licenseExpiryOptional')}>
          <input type="date" value={form.licenseExpiry} onChange={(e) => setForm({ ...form, licenseExpiry: e.target.value })} className={inputClass} />
        </Field>
        <Field label={t('providersPage.employmentOptional')}>
          <select value={form.employmentStatus} onChange={(e) => setForm({ ...form, employmentStatus: e.target.value })} className={inputClass}>
            <option value="">—</option>
            {EMPLOYMENT_STATUSES.map((s) => <option key={s} value={s}>{t(`employmentStatus.${s}`)}</option>)}
          </select>
        </Field>
        <Button type="submit" variant="accent" disabled={createProvider.isPending}>
          {createProvider.isPending ? t('common.adding') : t('providersPage.addProvider')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && providers?.length === 0 && (
        <EmptyState title={t('providersPage.emptyTitle')} description={t('providersPage.emptyDescription')} />
      )}
      {!isLoading && !isError && providers?.length > 0 && (
        <DataTable
          columns={columns}
          rows={visibleProviders}
          rowKey="id"
          searchAccessors={[(p) => p.fullName, (p) => p.specialty]}
          defaultSortKey="fullName"
          renderExpanded={(provider) => <ProviderPanel provider={provider} roomById={roomById} rooms={rooms || []} />}
        />
      )}
    </PageContainer>
  );
}

function ProviderPanel({ provider, roomById, rooms }) {
  const { t } = useTranslation();
  const updateProvider = useUpdateProvider(provider.id);
  const linkLogin = useLinkProviderLogin(provider.id);
  const unlinkLogin = useUnlinkProviderLogin(provider.id);

  const [editing, setEditing] = useState(false);
  const [editForm, setEditForm] = useState(null);
  const [rowError, setRowError] = useState(null);

  const [showHours, setShowHours] = useState(false);
  const [showLogin, setShowLogin] = useState(false);
  const [showSignature, setShowSignature] = useState(false);
  const [loginEmail, setLoginEmail] = useState('');
  const [loginError, setLoginError] = useState(null);

  function startEdit() {
    setRowError(null);
    setEditForm({
      fullName: provider.fullName, specialty: provider.specialty || '', roomId: provider.roomId || '',
      licenseNumber: provider.licenseNumber || '', licenseExpiry: provider.licenseExpiry || '', employmentStatus: provider.employmentStatus || '',
    });
    setEditing(true);
  }

  async function saveEdit() {
    setRowError(null);
    try {
      await updateProvider.mutateAsync({
        fullName: editForm.fullName.trim(),
        specialty: editForm.specialty.trim() || null,
        roomId: editForm.roomId || null,
        licenseNumber: editForm.licenseNumber.trim() || null,
        licenseExpiry: editForm.licenseExpiry || null,
        employmentStatus: editForm.employmentStatus || null,
      });
      setEditing(false);
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function toggleActive() {
    setRowError(null);
    try {
      await updateProvider.mutateAsync({ status: provider.status === 'active' ? 'inactive' : 'active' });
    } catch (err) {
      setRowError(err.message || t('clinicsPage.errorUpdateClinic'));
    }
  }

  async function handleLink(event) {
    event.preventDefault();
    setLoginError(null);
    if (!loginEmail.trim()) {
      setLoginError(t('providersPage.errorEmailRequired'));
      return;
    }
    try {
      await linkLogin.mutateAsync(loginEmail.trim());
      setLoginEmail('');
    } catch (err) {
      setLoginError(err.message || t('providersPage.errorLink'));
    }
  }

  async function handleUnlink() {
    setLoginError(null);
    try {
      await unlinkLogin.mutateAsync();
    } catch (err) {
      setLoginError(err.message || t('providersPage.errorUnlink'));
    }
  }

  const room = provider.roomId ? roomById[provider.roomId] : null;

  return (
    <div>
      {editing ? (
        <div className="flex flex-wrap items-end gap-3">
          <Field label={t('providersPage.fullName')}>
            <input value={editForm.fullName} onChange={(e) => setEditForm({ ...editForm, fullName: e.target.value })} className={`${inputClass} w-48`} />
          </Field>
          <Field label={t('providersPage.specialty')}>
            <input value={editForm.specialty} onChange={(e) => setEditForm({ ...editForm, specialty: e.target.value })} className={`${inputClass} w-48`} />
          </Field>
          <Field label={t('providersPage.room')}>
            <select value={editForm.roomId} onChange={(e) => setEditForm({ ...editForm, roomId: e.target.value })} className={`${inputClass} w-40`}>
              <option value="">{t('providersPage.none')}</option>
              {rooms.map((r) => (
                <option key={r.id} value={r.id}>{r.name}</option>
              ))}
            </select>
          </Field>
          <Field label={t('providersPage.licenseNumberShort')}>
            <input value={editForm.licenseNumber} onChange={(e) => setEditForm({ ...editForm, licenseNumber: e.target.value })} className={`${inputClass} w-36`} />
          </Field>
          <Field label={t('providersPage.licenseExpiry')}>
            <input type="date" value={editForm.licenseExpiry} onChange={(e) => setEditForm({ ...editForm, licenseExpiry: e.target.value })} className={inputClass} />
          </Field>
          <Field label={t('providersPage.employment')}>
            <select value={editForm.employmentStatus} onChange={(e) => setEditForm({ ...editForm, employmentStatus: e.target.value })} className={inputClass}>
              <option value="">—</option>
              {EMPLOYMENT_STATUSES.map((s) => <option key={s} value={s}>{t(`employmentStatus.${s}`)}</option>)}
            </select>
          </Field>
          <Button type="button" variant="accent" onClick={saveEdit} disabled={updateProvider.isPending}>
            {t('common.save')}
          </Button>
          <button type="button" onClick={() => setEditing(false)} className="text-sm text-ink-muted hover:underline">
            {t('common.cancel')}
          </button>
        </div>
      ) : (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-xs text-ink-muted">
            {provider.appUserId && `${t('providersPage.hasLogin')} · `}
            {provider.licenseNumber && `${t('providersPage.licensePrefix', { number: provider.licenseNumber })} · `}
            {room ? room.name : t('providersPage.none')}
          </p>
          <div className="flex items-center gap-3 text-sm">
            <button type="button" onClick={startEdit} className="text-brand-text hover:underline">{t('common.edit')}</button>
            <button type="button" onClick={toggleActive} className="text-ink-muted hover:underline">
              {provider.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
            </button>
            <button type="button" onClick={() => setShowHours((v) => !v)} className="text-ink-muted hover:underline">
              {showHours ? t('providersPage.hideHours') : t('providersPage.workingHoursLink')}
            </button>
            <button type="button" onClick={() => setShowLogin((v) => !v)} className="text-ink-muted hover:underline">
              {showLogin ? t('providersPage.hideLogin') : t('providersPage.loginLink')}
            </button>
            <button type="button" onClick={() => setShowSignature((v) => !v)} className="text-ink-muted hover:underline">
              {showSignature ? t('providersPage.hideSignature') : t('providersPage.signatureLink')}
            </button>
          </div>
        </div>
      )}
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}

      {showLogin && (
        <div className="mt-4 border-t border-slate-100 pt-4">
          {provider.appUserId ? (
            <div className="flex items-center gap-3">
              <p className="text-sm text-ink">{t('providersPage.linkedNote')}</p>
              <button type="button" onClick={handleUnlink} disabled={unlinkLogin.isPending} className="text-sm text-danger hover:underline">
                {t('providersPage.unlink')}
              </button>
            </div>
          ) : (
            <form onSubmit={handleLink} className="flex flex-wrap items-end gap-3">
              <Field label={t('providersPage.accountEmail')} hint={t('providersPage.accountEmailHint')}>
                <input type="email" value={loginEmail} onChange={(e) => setLoginEmail(e.target.value)} className={`${inputClass} w-64`} />
              </Field>
              <Button type="submit" variant="accent" disabled={linkLogin.isPending}>
                {t('providersPage.linkLogin')}
              </Button>
            </form>
          )}
          {loginError && <div className="mt-3"><ErrorBanner message={loginError} /></div>}
        </div>
      )}

      {showHours && <WorkingHoursPanel providerId={provider.id} />}
      {showSignature && <SignaturePanel provider={provider} />}
    </div>
  );
}

/**
 * The first file-upload UI in this app (ProviderController.uploadSignature,
 * phase 13) - a raw <input type="file"> wrapped in FormData, posted via
 * apiPostForm (frontend phase K). The preview <img> points straight at
 * GET /api/providers/{id}/signature - the browser fetches the raw image
 * bytes same-origin with the session cookie automatically, no separate
 * JS fetch/blob-URL dance needed. Only rendered once signatureFilename is
 * set (the list response already carries it - no extra existence check).
 */
function SignaturePanel({ provider }) {
  const { t } = useTranslation();
  const uploadSignature = useUploadProviderSignature(provider.id);
  const removeSignature = useRemoveProviderSignature(provider.id);
  const [uploadError, setUploadError] = useState(null);
  const [cacheBust, setCacheBust] = useState(0);

  async function handleFileChange(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    setUploadError(null);
    try {
      await uploadSignature.mutateAsync(file);
      setCacheBust((n) => n + 1);
    } catch (err) {
      setUploadError(err.message || t('providersPage.errorUploadSignature'));
    } finally {
      event.target.value = '';
    }
  }

  async function handleRemove() {
    setUploadError(null);
    try {
      await removeSignature.mutateAsync();
    } catch (err) {
      setUploadError(err.message || t('providersPage.errorRemoveSignature'));
    }
  }

  return (
    <div className="mt-4 border-t border-slate-100 pt-4">
      <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('providersPage.signature')}</p>
      {provider.signatureFilename ? (
        <div className="flex flex-wrap items-center gap-4">
          <img
            src={`/api/providers/${provider.id}/signature?v=${cacheBust}`}
            alt={`${provider.fullName}'s signature`}
            className="h-16 rounded border border-slate-200 bg-white p-1"
          />
          <button type="button" onClick={handleRemove} disabled={removeSignature.isPending} className="text-sm text-danger hover:underline">
            {removeSignature.isPending ? t('providersPage.removing') : t('common.delete')}
          </button>
        </div>
      ) : (
        <p className="mb-3 text-sm text-ink-muted">{t('providersPage.noSignature')}</p>
      )}
      <label className="mt-3 block text-left">
        <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">
          {provider.signatureFilename ? t('providersPage.replaceHint') : t('providersPage.uploadHint')}
        </span>
        <input type="file" accept="image/png,image/jpeg" onChange={handleFileChange} disabled={uploadSignature.isPending} className="text-sm" />
      </label>
      {uploadError && <div className="mt-3"><ErrorBanner message={uploadError} /></div>}
    </div>
  );
}

function WorkingHoursPanel({ providerId }) {
  const { t } = useTranslation();
  const { data: hours, isLoading, isError, error, refetch } = useProviderWorkingHours(providerId);
  const createHours = useCreateWorkingHours(providerId);
  const removeHours = useRemoveWorkingHours(providerId);

  const [form, setForm] = useState({ dayOfWeek: '1', startTime: '09:00', endTime: '17:00' });
  const [formError, setFormError] = useState(null);

  async function handleAdd(event) {
    event.preventDefault();
    setFormError(null);
    try {
      await createHours.mutateAsync({
        dayOfWeek: Number(form.dayOfWeek),
        startTime: form.startTime,
        endTime: form.endTime,
      });
    } catch (err) {
      setFormError(err.message || t('providersPage.errorAddWindow'));
    }
  }

  async function handleRemove(id) {
    setFormError(null);
    try {
      await removeHours.mutateAsync(id);
    } catch (err) {
      setFormError(err.message || t('providersPage.errorRemoveWindow'));
    }
  }

  const sorted = [...(hours || [])].sort((a, b) => a.dayOfWeek - b.dayOfWeek || a.startTime.localeCompare(b.startTime));

  return (
    <div className="mt-4 border-t border-slate-100 pt-4">
      <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('providersPage.workingHours')}</p>

      {isLoading && <Skeleton className="h-12 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && sorted.length === 0 && (
        <p className="mb-3 text-sm text-ink-muted">{t('providersPage.noHours')}</p>
      )}
      {!isLoading && !isError && sorted.length > 0 && (
        <ul className="mb-3 flex flex-col gap-1.5">
          {sorted.map((h) => (
            <li key={h.id} className="flex items-center justify-between text-sm text-ink">
              <span>
                {t(`dayLabel.${DAY_KEYS[h.dayOfWeek]}`)} · {h.startTime.slice(0, 5)}–{h.endTime.slice(0, 5)}
              </span>
              <button type="button" onClick={() => handleRemove(h.id)} className="text-xs text-danger hover:underline">
                {t('common.delete')}
              </button>
            </li>
          ))}
        </ul>
      )}

      <form onSubmit={handleAdd} className="flex flex-wrap items-end gap-3">
        <Field label={t('providersPage.day')}>
          <select value={form.dayOfWeek} onChange={(e) => setForm({ ...form, dayOfWeek: e.target.value })} className={`${inputClass} w-32`}>
            {DAY_KEYS.map((key, i) => (
              <option key={i} value={i}>{t(`dayLabel.${key}`)}</option>
            ))}
          </select>
        </Field>
        <Field label={t('providersPage.start')}>
          <input type="time" value={form.startTime} onChange={(e) => setForm({ ...form, startTime: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Field label={t('providersPage.end')}>
          <input type="time" value={form.endTime} onChange={(e) => setForm({ ...form, endTime: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createHours.isPending}>
          {t('providersPage.addWindow')}
        </Button>
      </form>
      {formError && <div className="mt-3"><ErrorBanner message={formError} /></div>}
    </div>
  );
}
