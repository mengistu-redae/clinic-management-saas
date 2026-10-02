import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAppointments, usePatients } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Button from '../../components/Button.jsx';
import { inputClass } from '../../components/Field.jsx';
import { formatDateTime } from '../../lib/format.js';

const STATUS_VALUES = ['booked', 'checked_in', 'roomed', 'with_provider', 'checked_out', 'no_show', 'cancelled'];

/**
 * Tenant-wide appointment list (front_desk/clinic_admin/provider) - see
 * GET /api/appointments. Appointment carries only a patientId (or, for a
 * guest booking, a contactName) - patient names are resolved via one
 * GET /api/patients call and an id->name map, rather than an N+1 lookup
 * per row. Rendered as a searchable/sortable DataTable (modern-UI
 * redesign) - the row-click-to-navigate behavior is unchanged.
 */
export default function Appointments() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [statusFilter, setStatusFilter] = useState('');
  const appointments = useAppointments(true);
  const patients = usePatients();

  const patientNames = useMemo(() => {
    const map = new Map();
    (patients.data || []).forEach((p) => map.set(p.id, `${p.firstName} ${p.lastName}`));
    return map;
  }, [patients.data]);

  function nameFor(a) {
    return (a.patientId && patientNames.get(a.patientId)) || a.contactName || t('frontDeskAppointments.guest');
  }

  const isLoading = appointments.isLoading || patients.isLoading;
  const isError = appointments.isError || patients.isError;

  const visibleAppointments = (appointments.data || []).filter((a) => !statusFilter || a.status === statusFilter);

  const columns = [
    { key: 'name', header: t('common.name'), accessor: nameFor, sortable: true },
    { key: 'ref', header: t('appointmentDetail.reference'), accessor: (a) => a.appointmentRef, sortable: true, className: 'font-mono text-xs' },
    { key: 'channel', header: t('fdAppointmentDetail.channel'), accessor: (a) => t(`channel.${a.channel}`, { defaultValue: a.channel }), sortable: true },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (a) => a.status,
      sortable: true,
      render: (a) => <StatusPill status={a.status} />,
    },
    { key: 'bookedAt', header: t('common.bookedAt'), accessor: (a) => a.bookedAt, sortAccessor: (a) => new Date(a.bookedAt), sortable: true, render: (a) => formatDateTime(a.bookedAt) },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader
        title={t('nav.frontDesk.appointments')}
        actions={
          <>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              aria-label={t('common.filterByStatus')}
              className={`${inputClass} w-44`}
            >
              <option value="">{t('common.all')}</option>
              {STATUS_VALUES.map((s) => (
                <option key={s} value={s}>{t(`status.${s}`)}</option>
              ))}
            </select>
            <Button as={Link} to="/front-desk/patients">
              {t('nav.frontDesk.bookWalkIn')}
            </Button>
          </>
        }
      />

      <DataTable
        columns={columns}
        rows={visibleAppointments}
        rowKey="id"
        searchAccessors={[nameFor, (a) => a.appointmentRef]}
        searchPlaceholder={t('common.search')}
        onRowClick={(a) => navigate(`/front-desk/appointments/${a.id}`)}
        defaultSortKey="bookedAt"
        defaultSortDir="desc"
        isLoading={isLoading}
        error={isError ? appointments.error || patients.error : null}
        onRetry={() => {
          appointments.refetch();
          patients.refetch();
        }}
        emptyTitle={t('frontDeskAppointments.emptyTitle')}
        emptyDescription={t('frontDeskAppointments.emptyDescription')}
      />
    </PageContainer>
  );
}
