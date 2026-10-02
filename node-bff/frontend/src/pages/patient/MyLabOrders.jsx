import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMyLabOrders } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Button from '../../components/Button.jsx';
import { inputClass } from '../../components/Field.jsx';
import { formatDateTime } from '../../lib/format.js';

// A patient's own list includes pre-confirmation 'requested' orders too, unlike
// the staff lab-orders table (which shows those via a separate pending-requests
// banner instead) - see patient/Dashboard.jsx's own OPEN_LAB_ORDER_STATUSES.
const LAB_ORDER_STATUSES = ['requested', 'ordered', 'specimen_collected', 'in_transit', 'resulted', 'reviewed', 'cancelled'];

/** The full list the dashboard's own top-5 preview links out to - same relationship as MyAppointments.jsx. GET /api/my-lab-orders. */
export default function MyLabOrders() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [statusFilter, setStatusFilter] = useState('');
  const { data, isLoading, isError, error, refetch } = useMyLabOrders(true);

  const visibleOrders = (data || []).filter((row) => !statusFilter || row.order.status === statusFilter);

  const columns = [
    { key: 'orderRef', header: t('appointmentDetail.reference'), accessor: (row) => row.order.orderRef, sortable: true, className: 'font-mono text-xs' },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (row) => row.order.status,
      sortable: true,
      render: (row) => <StatusPill status={row.order.status} />,
    },
    { key: 'tests', header: t('requestLabTest.tests'), accessor: (row) => row.tests.length, sortable: true, render: (row) => t('myLabOrders.testCount', { count: row.tests.length }) },
    {
      key: 'orderedAt',
      header: t('common.bookedAt'),
      accessor: (row) => row.order.orderedAt,
      sortAccessor: (row) => new Date(row.order.orderedAt),
      sortable: true,
      render: (row) => formatDateTime(row.order.orderedAt),
    },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader
        title={t('myLabOrders.title')}
        actions={
          <>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              aria-label={t('common.filterByStatus')}
              className={`${inputClass} w-44`}
            >
              <option value="">{t('common.all')}</option>
              {LAB_ORDER_STATUSES.map((s) => (
                <option key={s} value={s}>{t(`status.${s}`)}</option>
              ))}
            </select>
            <Button as={Link} to="/my-lab-orders/request" variant="accent">
              {t('myLabOrders.requestTest')}
            </Button>
          </>
        }
      />

      <DataTable
        columns={columns}
        rows={visibleOrders}
        rowKey={(row) => row.order.id}
        searchAccessors={[(row) => row.order.orderRef]}
        onRowClick={(row) => navigate(`/my-lab-orders/${row.order.id}`)}
        defaultSortKey="orderedAt"
        defaultSortDir="desc"
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('myLabOrders.emptyTitle')}
        emptyDescription={t('myLabOrders.emptyDescription')}
      />
    </PageContainer>
  );
}
