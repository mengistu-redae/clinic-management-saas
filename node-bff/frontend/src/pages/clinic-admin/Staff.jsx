import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../../api/client.js';
import {
  useStaff,
  useCreateStaff,
  useUpdateStaff,
  useLinkStaffLogin,
  useUnlinkStaffLogin,
  useStaffShifts,
  useCreateShift,
  useShiftAttendance,
  useMarkAttendance,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import Button from '../../components/Button.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import Field, { inputClass } from '../../components/Field.jsx';

/** Matches StaffController.VALID_ROLES exactly. */
const ROLES = ['clinic_admin', 'provider', 'front_desk', 'pharmacist', 'accountant', 'lab_technician', 'imaging_technologist'];

/** Matches AttendanceController.VALID_STATUSES exactly. */
const ATTENDANCE_STATUSES = ['present', 'absent', 'leave'];

/**
 * clinic-admin staff roster (phase 47) - GET/POST/POST .../update
 * StaffController, plus two nested per-staff-member panels toggled inline:
 * login linking (same link-login/unlink-login shape as
 * clinic-admin/Providers.jsx) and shifts (ad-hoc per-date assignments,
 * each with its own inline attendance marker). clinic_admin-only
 * throughout, including reads.
 */
export default function ClinicAdminStaff() {
  const { t } = useTranslation();
  const { data: staff, isLoading, isError, error, refetch } = useStaff(true);
  const createStaff = useCreateStaff();

  const [form, setForm] = useState({ firstName: '', lastName: '', role: '' });
  const [formError, setFormError] = useState(null);
  const [statusFilter, setStatusFilter] = useState('');
  const visibleStaff = (staff || []).filter((s) => !statusFilter || s.status === statusFilter);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.firstName.trim() || !form.lastName.trim()) {
      setFormError(t('staffPage.errorNameRequired'));
      return;
    }
    if (!form.role) {
      setFormError(t('staffPage.errorRoleRequired'));
      return;
    }
    try {
      await createStaff.mutateAsync({
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim(),
        role: form.role,
      });
      setForm({ firstName: '', lastName: '', role: '' });
    } catch (err) {
      setFormError(err.message || t('staffPage.errorCreate'));
    }
  }

  const columns = [
    { key: 'firstName', header: t('staffPage.firstName'), accessor: (s) => s.firstName, sortable: true, className: 'font-semibold' },
    { key: 'lastName', header: t('staffPage.lastName'), accessor: (s) => s.lastName, sortable: true },
    { key: 'role', header: t('staffPage.role'), accessor: (s) => t(`roleLabel.${s.role}`), sortable: true },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (s) => s.status,
      sortable: true,
      render: (s) => <StatusPill status={s.status} />,
    },
  ];

  return (
    <PageContainer width="lg">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-ink">{t('nav.clinicAdmin.staff')}</h1>
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
        <Field label={t('staffPage.firstName')}>
          <input value={form.firstName} onChange={(e) => setForm({ ...form, firstName: e.target.value })} className={`${inputClass} w-40`} />
        </Field>
        <Field label={t('staffPage.lastName')}>
          <input value={form.lastName} onChange={(e) => setForm({ ...form, lastName: e.target.value })} className={`${inputClass} w-40`} />
        </Field>
        <Field label={t('staffPage.role')}>
          <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })} className={`${inputClass} w-44`}>
            <option value="">{t('staffPage.selectRole')}</option>
            {ROLES.map((r) => <option key={r} value={r}>{t(`roleLabel.${r}`)}</option>)}
          </select>
        </Field>
        <Button type="submit" variant="accent" disabled={createStaff.isPending}>
          {createStaff.isPending ? t('common.adding') : t('staffPage.addStaff')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && staff?.length === 0 && (
        <EmptyState title={t('staffPage.emptyTitle')} description={t('staffPage.emptyDescription')} />
      )}
      {!isLoading && !isError && staff?.length > 0 && (
        <DataTable
          columns={columns}
          rows={visibleStaff}
          rowKey="id"
          searchAccessors={[(s) => s.firstName, (s) => s.lastName]}
          defaultSortKey="firstName"
          renderExpanded={(staffMember) => <StaffPanel staffMember={staffMember} />}
        />
      )}
    </PageContainer>
  );
}

function StaffPanel({ staffMember }) {
  const { t } = useTranslation();
  const updateStaff = useUpdateStaff(staffMember.id);
  const linkLogin = useLinkStaffLogin(staffMember.id);
  const unlinkLogin = useUnlinkStaffLogin(staffMember.id);

  const [editing, setEditing] = useState(false);
  const [editForm, setEditForm] = useState(null);
  const [rowError, setRowError] = useState(null);

  const [showLogin, setShowLogin] = useState(false);
  const [showShifts, setShowShifts] = useState(false);
  const [loginEmail, setLoginEmail] = useState('');
  const [loginError, setLoginError] = useState(null);

  function startEdit() {
    setRowError(null);
    setEditForm({ firstName: staffMember.firstName, lastName: staffMember.lastName, role: staffMember.role });
    setEditing(true);
  }

  async function saveEdit() {
    setRowError(null);
    try {
      await updateStaff.mutateAsync({
        firstName: editForm.firstName.trim(),
        lastName: editForm.lastName.trim(),
        role: editForm.role,
      });
      setEditing(false);
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function toggleActive() {
    setRowError(null);
    try {
      await updateStaff.mutateAsync({ status: staffMember.status === 'active' ? 'inactive' : 'active' });
    } catch (err) {
      setRowError(err.message || t('clinicsPage.errorUpdateClinic'));
    }
  }

  async function handleLink(event) {
    event.preventDefault();
    setLoginError(null);
    if (!loginEmail.trim()) {
      setLoginError(t('staffPage.errorEmailRequired'));
      return;
    }
    try {
      await linkLogin.mutateAsync(loginEmail.trim());
      setLoginEmail('');
    } catch (err) {
      setLoginError(err.message || t('staffPage.errorLink'));
    }
  }

  async function handleUnlink() {
    setLoginError(null);
    try {
      await unlinkLogin.mutateAsync();
    } catch (err) {
      setLoginError(err.message || t('staffPage.errorUnlink'));
    }
  }

  return (
    <div>
      {editing ? (
        <div className="flex flex-wrap items-end gap-3">
          <Field label={t('staffPage.firstName')}>
            <input value={editForm.firstName} onChange={(e) => setEditForm({ ...editForm, firstName: e.target.value })} className={`${inputClass} w-40`} />
          </Field>
          <Field label={t('staffPage.lastName')}>
            <input value={editForm.lastName} onChange={(e) => setEditForm({ ...editForm, lastName: e.target.value })} className={`${inputClass} w-40`} />
          </Field>
          <Field label={t('staffPage.role')}>
            <select value={editForm.role} onChange={(e) => setEditForm({ ...editForm, role: e.target.value })} className={`${inputClass} w-44`}>
              {ROLES.map((r) => <option key={r} value={r}>{t(`roleLabel.${r}`)}</option>)}
            </select>
          </Field>
          <Button type="button" variant="accent" onClick={saveEdit} disabled={updateStaff.isPending}>
            {t('common.save')}
          </Button>
          <button type="button" onClick={() => setEditing(false)} className="text-sm text-ink-muted hover:underline">
            {t('common.cancel')}
          </button>
        </div>
      ) : (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-xs text-ink-muted">
            {staffMember.appUserId && `${t('staffPage.hasLogin')} · `}
            {t(`roleLabel.${staffMember.role}`)}
          </p>
          <div className="flex items-center gap-3 text-sm">
            <button type="button" onClick={startEdit} className="text-brand-text hover:underline">{t('common.edit')}</button>
            <button type="button" onClick={toggleActive} className="text-ink-muted hover:underline">
              {staffMember.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
            </button>
            <button type="button" onClick={() => setShowLogin((v) => !v)} className="text-ink-muted hover:underline">
              {showLogin ? t('providersPage.hideLogin') : t('providersPage.loginLink')}
            </button>
            <button type="button" onClick={() => setShowShifts((v) => !v)} className="text-ink-muted hover:underline">
              {showShifts ? t('staffPage.hideShifts') : t('staffPage.shiftsLink')}
            </button>
          </div>
        </div>
      )}
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}

      {showLogin && (
        <div className="mt-4 border-t border-slate-100 pt-4">
          {staffMember.appUserId ? (
            <div className="flex items-center gap-3">
              <p className="text-sm text-ink">{t('staffPage.linkedNote')}</p>
              <button type="button" onClick={handleUnlink} disabled={unlinkLogin.isPending} className="text-sm text-danger hover:underline">
                {t('staffPage.unlink')}
              </button>
            </div>
          ) : (
            <form onSubmit={handleLink} className="flex flex-wrap items-end gap-3">
              <Field label={t('staffPage.accountEmail')} hint={t('staffPage.accountEmailHint')}>
                <input type="email" value={loginEmail} onChange={(e) => setLoginEmail(e.target.value)} className={`${inputClass} w-64`} />
              </Field>
              <Button type="submit" variant="accent" disabled={linkLogin.isPending}>
                {t('staffPage.linkLogin')}
              </Button>
            </form>
          )}
          {loginError && <div className="mt-3"><ErrorBanner message={loginError} /></div>}
        </div>
      )}

      {showShifts && <ShiftsPanel staffId={staffMember.id} />}
    </div>
  );
}

function ShiftsPanel({ staffId }) {
  const { t } = useTranslation();
  const { data: shifts, isLoading, isError, error, refetch } = useStaffShifts(staffId);
  const createShift = useCreateShift(staffId);

  const [form, setForm] = useState({ shiftDate: '', startTime: '09:00', endTime: '17:00', notes: '' });
  const [formError, setFormError] = useState(null);
  const [expandedShiftId, setExpandedShiftId] = useState(null);

  async function handleAdd(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.shiftDate) {
      setFormError(t('staffPage.errorAddShift'));
      return;
    }
    try {
      await createShift.mutateAsync({
        shiftDate: form.shiftDate,
        startTime: form.startTime,
        endTime: form.endTime,
        notes: form.notes.trim() || undefined,
      });
      setForm({ shiftDate: '', startTime: '09:00', endTime: '17:00', notes: '' });
    } catch (err) {
      setFormError(err.message || t('staffPage.errorAddShift'));
    }
  }

  const sorted = [...(shifts || [])].sort((a, b) => a.shiftDate.localeCompare(b.shiftDate) || a.startTime.localeCompare(b.startTime));

  return (
    <div className="mt-4 border-t border-slate-100 pt-4">
      <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('staffPage.shifts')}</p>

      {isLoading && <Skeleton className="h-12 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && sorted.length === 0 && (
        <p className="mb-3 text-sm text-ink-muted">{t('staffPage.noShifts')}</p>
      )}
      {!isLoading && !isError && sorted.length > 0 && (
        <ul className="mb-3 flex flex-col gap-2">
          {sorted.map((shift) => (
            <li key={shift.id} className="rounded-lg border border-slate-100 p-2">
              <div className="flex items-center justify-between text-sm text-ink">
                <span>
                  {shift.shiftDate} · {shift.startTime.slice(0, 5)}–{shift.endTime.slice(0, 5)}
                  {shift.notes && ` · ${shift.notes}`}
                </span>
                <button
                  type="button"
                  onClick={() => setExpandedShiftId((id) => (id === shift.id ? null : shift.id))}
                  className="text-xs text-brand-text hover:underline"
                >
                  {t('staffPage.attendance')}
                </button>
              </div>
              {expandedShiftId === shift.id && <AttendancePanel shiftId={shift.id} />}
            </li>
          ))}
        </ul>
      )}

      <form onSubmit={handleAdd} className="flex flex-wrap items-end gap-3">
        <Field label={t('staffPage.date')}>
          <input type="date" value={form.shiftDate} onChange={(e) => setForm({ ...form, shiftDate: e.target.value })} className={inputClass} />
        </Field>
        <Field label={t('staffPage.start')}>
          <input type="time" value={form.startTime} onChange={(e) => setForm({ ...form, startTime: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Field label={t('staffPage.end')}>
          <input type="time" value={form.endTime} onChange={(e) => setForm({ ...form, endTime: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Field label={t('staffPage.notesOptional')}>
          <input value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} className={`${inputClass} w-48`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createShift.isPending}>
          {t('staffPage.addShift')}
        </Button>
      </form>
      {formError && <div className="mt-3"><ErrorBanner message={formError} /></div>}
    </div>
  );
}

/**
 * Upsert-in-place (AttendanceController.markAttendance) - re-marking just
 * replaces this same shift's one Attendance row, never adds a second one.
 * A 404 (nothing recorded yet) is the expected empty state, not an error.
 */
function AttendancePanel({ shiftId }) {
  const { t } = useTranslation();
  const attendanceQuery = useShiftAttendance(shiftId);
  const markAttendance = useMarkAttendance(shiftId);
  const [markError, setMarkError] = useState(null);

  const notMarkedYet = attendanceQuery.isError && attendanceQuery.error instanceof ApiError && attendanceQuery.error.status === 404;
  const attendance = attendanceQuery.data;

  async function handleMark(status) {
    setMarkError(null);
    try {
      await markAttendance.mutateAsync({ status });
    } catch (err) {
      setMarkError(err.message || t('staffPage.errorMarkAttendance'));
    }
  }

  if (attendanceQuery.isLoading) {
    return <Skeleton className="mt-2 h-8 w-full" />;
  }
  if (attendanceQuery.isError && !notMarkedYet) {
    return <div className="mt-2"><ErrorBanner message={attendanceQuery.error?.message} onRetry={attendanceQuery.refetch} /></div>;
  }

  return (
    <div className="mt-2 flex flex-wrap items-center gap-3 rounded-lg border border-slate-200 bg-slate-50 p-2">
      <span className="text-xs text-ink-muted">
        {attendance ? t(`attendanceStatus.${attendance.status}`) : t('staffPage.noAttendance')}
      </span>
      <div className="flex gap-2 text-xs">
        {ATTENDANCE_STATUSES.map((status) => (
          <button
            key={status}
            type="button"
            onClick={() => handleMark(status)}
            disabled={markAttendance.isPending}
            className={`rounded px-2 py-1 ${attendance?.status === status ? 'bg-accent text-white' : 'text-ink-muted hover:underline'}`}
          >
            {t(`attendanceStatus.${status}`)}
          </button>
        ))}
      </div>
      {markError && <div className="w-full"><ErrorBanner message={markError} /></div>}
    </div>
  );
}
