import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSuppliers, useCreateSupplier, useUpdateSupplier } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/** Plain CRUD, same inline-edit-in-place shape as clinic-admin/Rooms.jsx - simpler than Items.jsx, no nested panel. */
export default function InventorySuppliers() {
  const { t } = useTranslation();
  const { data: suppliers, isLoading, isError, error, refetch } = useSuppliers(true);
  const createSupplier = useCreateSupplier();

  const [form, setForm] = useState({ name: '', contactName: '', phone: '', email: '' });
  const [formError, setFormError] = useState(null);

  const [editingId, setEditingId] = useState(null);
  const [editForm, setEditForm] = useState(null);
  const [rowError, setRowError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.name.trim()) {
      setFormError(t('pharmacistPage.errorNameRequired'));
      return;
    }
    try {
      await createSupplier.mutateAsync({
        name: form.name.trim(),
        contactName: form.contactName.trim() || undefined,
        phone: form.phone.trim() || undefined,
        email: form.email.trim() || undefined,
      });
      setForm({ name: '', contactName: '', phone: '', email: '' });
    } catch (err) {
      setFormError(err.message || t('inventoryPage.errorCreateSupplier'));
    }
  }

  const columns = [
    { key: 'name', header: t('common.name'), accessor: (s) => s.name, sortable: true, className: 'font-semibold' },
    { key: 'contactName', header: t('inventoryPage.contactNameOptional'), accessor: (s) => s.contactName || '', render: (s) => s.contactName || '—' },
    { key: 'phone', header: t('inventoryPage.phoneOptional'), accessor: (s) => s.phone || '', render: (s) => s.phone || '—' },
    { key: 'email', header: t('inventoryPage.emailOptional'), accessor: (s) => s.email || '', render: (s) => s.email || '—' },
    { key: 'status', header: t('referralsPage.status'), accessor: (s) => s.status, sortable: true, render: (s) => <StatusPill status={s.status} /> },
    {
      key: 'actions',
      header: '',
      headerClassName: 'w-0',
      render: (s) => (
        <SupplierActions
          supplier={s}
          editing={editingId === s.id}
          editForm={editForm}
          setEditForm={setEditForm}
          onStartEdit={() => { setRowError(null); setEditForm({ name: s.name, contactName: s.contactName || '', phone: s.phone || '', email: s.email || '' }); setEditingId(s.id); }}
          onCancelEdit={() => setEditingId(null)}
          onSaved={() => setEditingId(null)}
          onError={setRowError}
        />
      ),
    },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.inventory.suppliers')} />

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('common.name')}>
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className={`${inputClass} w-56`} />
        </Field>
        <Field label={t('inventoryPage.contactNameOptional')}>
          <input value={form.contactName} onChange={(e) => setForm({ ...form, contactName: e.target.value })} className={`${inputClass} w-40`} />
        </Field>
        <Field label={t('inventoryPage.phoneOptional')}>
          <input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} className={`${inputClass} w-36`} />
        </Field>
        <Field label={t('inventoryPage.emailOptional')}>
          <input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} className={`${inputClass} w-48`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createSupplier.isPending}>
          {createSupplier.isPending ? t('common.adding') : t('inventoryPage.addSupplier')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      {rowError && <div className="mb-4"><ErrorBanner message={rowError} /></div>}

      <DataTable
        columns={columns}
        rows={suppliers || []}
        rowKey="id"
        searchAccessors={[(s) => s.name, (s) => s.contactName]}
        defaultSortKey="name"
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('inventoryPage.emptySuppliersTitle')}
        emptyDescription={t('inventoryPage.emptySuppliersDescription')}
      />
    </PageContainer>
  );
}

function SupplierActions({ supplier, editing, editForm, setEditForm, onStartEdit, onCancelEdit, onSaved, onError }) {
  const { t } = useTranslation();
  const updateSupplier = useUpdateSupplier(supplier.id);

  async function saveEdit() {
    onError(null);
    try {
      await updateSupplier.mutateAsync({
        name: editForm.name.trim(),
        contactName: editForm.contactName.trim() || null,
        phone: editForm.phone.trim() || null,
        email: editForm.email.trim() || null,
      });
      onSaved();
    } catch (err) {
      onError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function toggleActive() {
    onError(null);
    try {
      await updateSupplier.mutateAsync({ status: supplier.status === 'active' ? 'inactive' : 'active' });
    } catch (err) {
      onError(err.message || t('common.errorSaveChanges'));
    }
  }

  if (editing) {
    return (
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <input value={editForm.name} onChange={(e) => setEditForm({ ...editForm, name: e.target.value })} className={`${inputClass} w-40`} />
        <input value={editForm.contactName} onChange={(e) => setEditForm({ ...editForm, contactName: e.target.value })} className={`${inputClass} w-32`} />
        <input value={editForm.phone} onChange={(e) => setEditForm({ ...editForm, phone: e.target.value })} className={`${inputClass} w-28`} />
        <input value={editForm.email} onChange={(e) => setEditForm({ ...editForm, email: e.target.value })} className={`${inputClass} w-40`} />
        <button type="button" onClick={saveEdit} disabled={updateSupplier.isPending} className="font-semibold text-accent hover:underline disabled:opacity-50">
          {t('common.save')}
        </button>
        <button type="button" onClick={onCancelEdit} className="text-ink-muted hover:underline">
          {t('common.cancel')}
        </button>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-3 text-sm">
      <button type="button" onClick={onStartEdit} className="text-brand-text hover:underline">{t('common.edit')}</button>
      <button type="button" onClick={toggleActive} className="text-ink-muted hover:underline">
        {supplier.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
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
