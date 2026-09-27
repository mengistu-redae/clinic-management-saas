import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMyAppointments } from '../api/queries.js';
import StatusPill from '../components/StatusPill.jsx';
import DataTable from '../components/DataTable.jsx';
import PageContainer from '../components/PageContainer.jsx';
import PageHeader from '../components/PageHeader.jsx';
import Button from '../components/Button.jsx';
import { formatDateTime } from '../lib/format.js';

/** The patient dashboard shows a top-5 preview; this is the full list. */
export default function MyAppointments() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { data, isLoading, isError, error, refetch } = useMyAppointments(true);

  const columns = [
    { key: 'ref', header: t('appointmentDetail.reference'), accessor: (a) => a.appointmentRef, sortable: true, className: 'font-mono text-xs' },
    { key: 'startTime', header: t('appointmentDetail.when'), accessor: (a) => a.startTime, sortAccessor: (a) => new Date(a.startTime), sortable: true, render: (a) => formatDateTime(a.startTime) },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (a) => a.status,
      sortable: true,
      render: (a) => <StatusPill status={a.status} />,
    },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader
        title={t('myAppointments.title')}
        actions={
          <Button as={Link} to="/book">
            {t('myAppointments.bookNew')}
          </Button>
        }
      />

      <DataTable
        columns={columns}
        rows={data || []}
        rowKey="id"
        searchAccessors={[(a) => a.appointmentRef]}
        onRowClick={(a) => navigate(`/appointments/${a.id}`)}
        defaultSortKey="startTime"
        defaultSortDir="desc"
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('myAppointments.emptyTitle')}
        emptyDescription={t('myAppointments.emptyDescription')}
      />
    </PageContainer>
  );
}
