import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useRooms, useCreateRoom, useUpdateRoom } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';

/**
 * clinic-admin room management - GET/POST/POST .../update RoomController.
 * All statuses shown, same reasoning as Providers.jsx. Edits expand into a
 * panel via DataTable's own `renderExpanded`, matching every sibling CRUD
 * page in this same settings area (AppointmentTypes/FeePolicies/LabRates) -
 * this page used to be the one inline-in-the-cell outlier (2026-10-01 UI
 * audit), converted here to match.
 */
export default function ClinicAdminRooms() {
  const { t } = useTranslation();
  const { data: rooms, isLoading, isError, error, refetch } = useRooms(true);
  const createRoom = useCreateRoom();

  const [name, setName] = useState('');
  const [formError, setFormError] = useState(null);

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
    { key: 'name', header: t('rooms.roomName'), accessor: (room) => room.name, sortable: true, className: 'font-semibold' },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (room) => room.status,
      sortable: true,
      render: (room) => <StatusPill status={room.status} />,
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

      <DataTable
        columns={columns}
        rows={rooms || []}
        rowKey="id"
        searchAccessors={[(room) => room.name]}
        defaultSortKey="name"
        renderExpanded={(room) => <RoomEditPanel room={room} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('rooms.emptyTitle')}
        emptyDescription={t('rooms.emptyDescription')}
      />
    </PageContainer>
  );
}

function RoomEditPanel({ room }) {
  const { t } = useTranslation();
  const updateRoom = useUpdateRoom(room.id);
  const [name, setName] = useState(room.name);
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updateRoom.mutateAsync({ name: name.trim() });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function toggleActive() {
    setRowError(null);
    try {
      await updateRoom.mutateAsync({ status: room.status === 'active' ? 'inactive' : 'active' });
    } catch (err) {
      setRowError(err.message || t('rooms.errorUpdate'));
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('rooms.roomName')}>
          <input value={name} onChange={(e) => setName(e.target.value)} className={`${inputClass} w-48`} />
        </Field>
        <Button type="button" variant="accent" onClick={saveEdit} disabled={updateRoom.isPending}>
          {t('common.save')}
        </Button>
        <button type="button" onClick={toggleActive} className="text-sm text-ink-muted hover:underline">
          {room.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
        </button>
      </div>
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
    </div>
  );
}
