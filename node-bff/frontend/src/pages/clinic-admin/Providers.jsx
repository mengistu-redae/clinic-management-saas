import { useState } from 'react';
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
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

// 0=Sunday..6=Saturday - matches CreateWorkingHoursRequest's own convention (see SlotGenerator), NOT java.time.DayOfWeek's ISO numbering.
const DAY_LABELS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

/**
 * clinic-admin provider management - GET/POST/POST .../update
 * ProviderController, plus two nested per-provider panels toggled inline
 * rather than separate routes: working hours (ProviderWorkingHoursController)
 * and login linking (link-login/unlink-login). All providers shown
 * regardless of status (unlike the booking flow's active-only lists) - an
 * admin needs to see and reactivate deactivated ones too.
 */
export default function ClinicAdminProviders() {
  const { data: providers, isLoading, isError, error, refetch } = useProviders(true);
  const { data: rooms } = useRooms(true);
  const createProvider = useCreateProvider();

  const [form, setForm] = useState({ fullName: '', specialty: '', roomId: '' });
  const [formError, setFormError] = useState(null);

  const roomById = Object.fromEntries((rooms || []).map((r) => [r.id, r]));

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.fullName.trim()) {
      setFormError('Full name is required.');
      return;
    }
    try {
      await createProvider.mutateAsync({
        fullName: form.fullName.trim(),
        specialty: form.specialty.trim() || undefined,
        roomId: form.roomId || undefined,
      });
      setForm({ fullName: '', specialty: '', roomId: '' });
    } catch (err) {
      setFormError(err.message || 'Could not create provider.');
    }
  }

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-ink">Providers</h1>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label="Full name">
          <input value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} placeholder="Dr. Jane Doe" className={`${inputClass} w-48`} />
        </Field>
        <Field label="Specialty">
          <input value={form.specialty} onChange={(e) => setForm({ ...form, specialty: e.target.value })} placeholder="General practice" className={`${inputClass} w-48`} />
        </Field>
        <Field label="Room">
          <select value={form.roomId} onChange={(e) => setForm({ ...form, roomId: e.target.value })} className={`${inputClass} w-40`}>
            <option value="">None</option>
            {(rooms || []).map((r) => (
              <option key={r.id} value={r.id}>{r.name}</option>
            ))}
          </select>
        </Field>
        <button type="submit" disabled={createProvider.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {createProvider.isPending ? 'Adding…' : 'Add provider'}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && providers?.length === 0 && (
        <EmptyState title="No providers yet" description="Add your first provider above." />
      )}

      {!isLoading && !isError && providers?.length > 0 && (
        <div className="flex flex-col gap-3">
          {providers.map((provider) => (
            <ProviderRow key={provider.id} provider={provider} roomById={roomById} rooms={rooms || []} />
          ))}
        </div>
      )}
    </div>
  );
}

function ProviderRow({ provider, roomById, rooms }) {
  const updateProvider = useUpdateProvider(provider.id);
  const linkLogin = useLinkProviderLogin(provider.id);
  const unlinkLogin = useUnlinkProviderLogin(provider.id);

  const [editing, setEditing] = useState(false);
  const [editForm, setEditForm] = useState(null);
  const [rowError, setRowError] = useState(null);

  const [showHours, setShowHours] = useState(false);
  const [showLogin, setShowLogin] = useState(false);
  const [loginEmail, setLoginEmail] = useState('');
  const [loginError, setLoginError] = useState(null);

  function startEdit() {
    setRowError(null);
    setEditForm({ fullName: provider.fullName, specialty: provider.specialty || '', roomId: provider.roomId || '' });
    setEditing(true);
  }

  async function saveEdit() {
    setRowError(null);
    try {
      await updateProvider.mutateAsync({
        fullName: editForm.fullName.trim(),
        specialty: editForm.specialty.trim() || null,
        roomId: editForm.roomId || null,
      });
      setEditing(false);
    } catch (err) {
      setRowError(err.message || 'Could not save changes.');
    }
  }

  async function toggleActive() {
    setRowError(null);
    try {
      await updateProvider.mutateAsync({ status: provider.status === 'active' ? 'inactive' : 'active' });
    } catch (err) {
      setRowError(err.message || 'Could not update this provider.');
    }
  }

  async function handleLink(event) {
    event.preventDefault();
    setLoginError(null);
    if (!loginEmail.trim()) {
      setLoginError('Enter the email they logged in with.');
      return;
    }
    try {
      await linkLogin.mutateAsync(loginEmail.trim());
      setLoginEmail('');
    } catch (err) {
      setLoginError(err.message || 'Could not link this login.');
    }
  }

  async function handleUnlink() {
    setLoginError(null);
    try {
      await unlinkLogin.mutateAsync();
    } catch (err) {
      setLoginError(err.message || 'Could not unlink this login.');
    }
  }

  const room = provider.roomId ? roomById[provider.roomId] : null;

  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4">
      {editing ? (
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Full name">
            <input value={editForm.fullName} onChange={(e) => setEditForm({ ...editForm, fullName: e.target.value })} className={`${inputClass} w-48`} />
          </Field>
          <Field label="Specialty">
            <input value={editForm.specialty} onChange={(e) => setEditForm({ ...editForm, specialty: e.target.value })} className={`${inputClass} w-48`} />
          </Field>
          <Field label="Room">
            <select value={editForm.roomId} onChange={(e) => setEditForm({ ...editForm, roomId: e.target.value })} className={`${inputClass} w-40`}>
              <option value="">None</option>
              {rooms.map((r) => (
                <option key={r.id} value={r.id}>{r.name}</option>
              ))}
            </select>
          </Field>
          <button type="button" onClick={saveEdit} disabled={updateProvider.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
            Save
          </button>
          <button type="button" onClick={() => setEditing(false)} className="text-sm text-ink-muted hover:underline">
            Cancel
          </button>
        </div>
      ) : (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <StatusPill status={provider.status} />
            <div>
              <p className="text-sm font-semibold text-ink">{provider.fullName}</p>
              <p className="text-xs text-ink-muted">
                {provider.specialty || 'No specialty set'}
                {room && ` · ${room.name}`}
                {provider.appUserId && ' · has a login'}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-3 text-sm">
            <button type="button" onClick={startEdit} className="text-brand hover:underline">Edit</button>
            <button type="button" onClick={toggleActive} className="text-ink-muted hover:underline">
              {provider.status === 'active' ? 'Deactivate' : 'Reactivate'}
            </button>
            <button type="button" onClick={() => setShowHours((v) => !v)} className="text-ink-muted hover:underline">
              {showHours ? 'Hide hours' : 'Working hours'}
            </button>
            <button type="button" onClick={() => setShowLogin((v) => !v)} className="text-ink-muted hover:underline">
              {showLogin ? 'Hide login' : 'Login'}
            </button>
          </div>
        </div>
      )}
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}

      {showLogin && (
        <div className="mt-4 border-t border-slate-100 pt-4">
          {provider.appUserId ? (
            <div className="flex items-center gap-3">
              <p className="text-sm text-ink">This provider is linked to a login.</p>
              <button type="button" onClick={handleUnlink} disabled={unlinkLogin.isPending} className="text-sm text-danger hover:underline">
                Unlink
              </button>
            </div>
          ) : (
            <form onSubmit={handleLink} className="flex flex-wrap items-end gap-3">
              <Field label="Account email" hint="Must have logged in at least once">
                <input type="email" value={loginEmail} onChange={(e) => setLoginEmail(e.target.value)} className={`${inputClass} w-64`} />
              </Field>
              <button type="submit" disabled={linkLogin.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
                Link login
              </button>
            </form>
          )}
          {loginError && <div className="mt-3"><ErrorBanner message={loginError} /></div>}
        </div>
      )}

      {showHours && <WorkingHoursPanel providerId={provider.id} />}
    </div>
  );
}

function WorkingHoursPanel({ providerId }) {
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
      setFormError(err.message || 'Could not add this window.');
    }
  }

  async function handleRemove(id) {
    setFormError(null);
    try {
      await removeHours.mutateAsync(id);
    } catch (err) {
      setFormError(err.message || 'Could not remove this window.');
    }
  }

  const sorted = [...(hours || [])].sort((a, b) => a.dayOfWeek - b.dayOfWeek || a.startTime.localeCompare(b.startTime));

  return (
    <div className="mt-4 border-t border-slate-100 pt-4">
      <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-ink-muted">Working hours</p>

      {isLoading && <Skeleton className="h-12 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && sorted.length === 0 && (
        <p className="mb-3 text-sm text-ink-muted">No working hours set - this provider has no open slots.</p>
      )}
      {!isLoading && !isError && sorted.length > 0 && (
        <ul className="mb-3 flex flex-col gap-1.5">
          {sorted.map((h) => (
            <li key={h.id} className="flex items-center justify-between text-sm text-ink">
              <span>
                {DAY_LABELS[h.dayOfWeek]} · {h.startTime.slice(0, 5)}–{h.endTime.slice(0, 5)}
              </span>
              <button type="button" onClick={() => handleRemove(h.id)} className="text-xs text-danger hover:underline">
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}

      <form onSubmit={handleAdd} className="flex flex-wrap items-end gap-3">
        <Field label="Day">
          <select value={form.dayOfWeek} onChange={(e) => setForm({ ...form, dayOfWeek: e.target.value })} className={`${inputClass} w-32`}>
            {DAY_LABELS.map((label, i) => (
              <option key={i} value={i}>{label}</option>
            ))}
          </select>
        </Field>
        <Field label="Start">
          <input type="time" value={form.startTime} onChange={(e) => setForm({ ...form, startTime: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Field label="End">
          <input type="time" value={form.endTime} onChange={(e) => setForm({ ...form, endTime: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <button type="submit" disabled={createHours.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          Add window
        </button>
      </form>
      {formError && <div className="mt-3"><ErrorBanner message={formError} /></div>}
    </div>
  );
}

function Field({ label, hint, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 flex items-baseline gap-2">
        <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
        {hint && <span className="text-xs font-normal normal-case text-ink-muted">({hint})</span>}
      </span>
      {children}
    </label>
  );
}
