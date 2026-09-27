import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useRooms, useCreateRoom, useUpdateRoom } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';

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

  const columns = [
    {
      key: 'name',
      header: t('rooms.roomName'),
      accessor: (room) => room.name,
      sortable: true,
      render: (room) =>
        editingId === room.id ? (
          <input autoFocus value={editName} onChange={(e) => setEditName(e.target.value)} className={`${inputClass} w-48`} />
        ) : (
          <span className="font-semibold">{room.name}</span>
        ),
    },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (room) => room.status,
      sortable: true,
      render: (room) => <StatusPill status={room.status} />,
    },
    {
      key: 'actions',
      header: '',
      headerClassName: 'w-0',
      render: (room) => <RoomActions room={room} editingId={editingId} editName={editName} setEditingId={setEditingId} setEditName={setEditName} onError={setRowError} />,
    },
  ];

  return (
    <PageContainer width="lg">
      <h1 className="mb-6 text-2xl font-bold text-ink">{t('nav.clinicAdmin.rooms')}</h1>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('rooms.roomName')}>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Room 1" className={`${inputClass} w-48`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createRoom.isPending}>
          {createRoom.isPending ? t('common.adding') : t('rooms.addRoom')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      {rowError && <div className="mb-4"><ErrorBanner message={rowError} /></div>}

      <DataTable
        columns={columns}
        rows={rooms || []}
        rowKey="id"
        searchAccessors={[(room) => room.name]}
        defaultSortKey="name"
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('rooms.emptyTitle')}
        emptyDescription={t('rooms.emptyDescription')}
      />
    </PageContainer>
  );
}

function RoomActions({ room, editingId, editName, setEditingId, setEditName, onError }) {
  const { t } = useTranslation();
  const updateRoom = useUpdateRoom(room.id);
  const editing = editingId === room.id;

  async function saveEdit() {
    onError(null);
    try {
      await updateRoom.mutateAsync({ name: editName.trim() });
      setEditingId(null);
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

  if (editing) {
    return (
      <div className="flex items-center gap-3 text-sm">
        <button type="button" onClick={saveEdit} disabled={updateRoom.isPending} className="font-semibold text-accent hover:underline disabled:opacity-50">
          {t('common.save')}
        </button>
        <button type="button" onClick={() => setEditingId(null)} className="text-ink-muted hover:underline">
          {t('common.cancel')}
        </button>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-3 text-sm">
      <button
        type="button"
        onClick={() => {
          onError(null);
          setEditName(room.name);
          setEditingId(room.id);
        }}
        className="text-brand-text hover:underline"
      >
        {t('common.edit')}
      </button>
      <button type="button" onClick={toggleActive} className="text-ink-muted hover:underline">
        {room.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
      </button>
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
