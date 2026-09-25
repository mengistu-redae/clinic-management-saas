import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useRooms, useCreateRoom, useUpdateRoom } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/** clinic-admin room management - GET/POST/POST .../update RoomController. All statuses shown, same reasoning as Providers.jsx. */
export default function ClinicAdminRooms() {
  const { t } = useTranslation();
  const { data: rooms, isLoading, isError, error, refetch } = useRooms(true);
  const createRoom = useCreateRoom();

  const [name, setName] = useState('');
  const [formError, setFormError] = useState(null);

  const [editingId, setEditingId] = useState(null);
  const [editName, setEditName] = useState('');
  const [rowError, setRowError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!name.trim()) {
      setFormError(t('rooms.errorNameRequired'));
      return;
    }
    try {
      await createRoom.mutateAsync({ name: name.trim() });
      setName('');
    } catch (err) {
      setFormError(err.message || t('rooms.errorCreate'));
    }
  }

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-ink">{t('nav.clinicAdmin.rooms')}</h1>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('rooms.roomName')}>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Room 1" className={`${inputClass} w-48`} />
        </Field>
        <button type="submit" disabled={createRoom.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {createRoom.isPending ? t('common.adding') : t('rooms.addRoom')}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      {isLoading && <Skeleton className="h-24 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && rooms?.length === 0 && (
        <EmptyState title={t('rooms.emptyTitle')} description={t('rooms.emptyDescription')} />
      )}

      {!isLoading && !isError && rooms?.length > 0 && (
        <div className="flex flex-col gap-2">
          {rooms.map((room) => (
            <RoomRow
              key={room.id}
              room={room}
              editing={editingId === room.id}
              editName={editName}
              onEditNameChange={setEditName}
              onStartEdit={() => {
                setRowError(null);
                setEditName(room.name);
                setEditingId(room.id);
              }}
              onCancelEdit={() => setEditingId(null)}
              onError={setRowError}
              afterSave={() => setEditingId(null)}
            />
          ))}
        </div>
      )}
      {rowError && <div className="mt-4"><ErrorBanner message={rowError} /></div>}
    </div>
  );
}

function RoomRow({ room, editing, editName, onEditNameChange, onStartEdit, onCancelEdit, onError, afterSave }) {
  const { t } = useTranslation();
  const updateRoom = useUpdateRoom(room.id);

  async function saveEdit() {
    onError(null);
    try {
      await updateRoom.mutateAsync({ name: editName.trim() });
      afterSave();
    } catch (err) {
      onError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function toggleActive() {
    onError(null);
    try {
      await updateRoom.mutateAsync({ status: room.status === 'active' ? 'inactive' : 'active' });
    } catch (err) {
      onError(err.message || t('rooms.errorUpdate'));
    }
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4">
      {editing ? (
        <div className="flex flex-wrap items-end gap-3">
          <Field label={t('rooms.roomName')}>
            <input value={editName} onChange={(e) => onEditNameChange(e.target.value)} className={`${inputClass} w-48`} />
          </Field>
          <button type="button" onClick={saveEdit} disabled={updateRoom.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
            {t('common.save')}
          </button>
          <button type="button" onClick={onCancelEdit} className="text-sm text-ink-muted hover:underline">
            {t('common.cancel')}
          </button>
        </div>
      ) : (
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <StatusPill status={room.status} />
            <span className="text-sm font-semibold text-ink">{room.name}</span>
          </div>
          <div className="flex items-center gap-3 text-sm">
            <button type="button" onClick={onStartEdit} className="text-brand-text hover:underline">{t('common.edit')}</button>
            <button type="button" onClick={toggleActive} className="text-ink-muted hover:underline">
              {room.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
            </button>
          </div>
        </div>
      )}
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
