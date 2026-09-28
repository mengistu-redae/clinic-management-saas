import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DataTable from './DataTable.jsx';

const rows = [
  { id: '1', name: 'Charlie', age: 40 },
  { id: '2', name: 'alice', age: 25 },
  { id: '3', name: 'Bob', age: 30 },
];

const columns = [
  { key: 'name', header: 'Name', accessor: (r) => r.name, sortable: true },
  { key: 'age', header: 'Age', accessor: (r) => r.age, sortable: true },
];

function nameCells() {
  // First column of each body row, in DOM order.
  return screen.getAllByRole('row').slice(1).map((row) => within(row).getAllByRole('cell')[0].textContent);
}

describe('DataTable', () => {
  it('renders one row per item using each column\'s accessor', () => {
    render(<DataTable columns={columns} rows={rows} rowKey="id" />);
    expect(screen.getByText('Charlie')).toBeInTheDocument();
    expect(screen.getByText('alice')).toBeInTheDocument();
    expect(screen.getByText('30')).toBeInTheDocument();
  });

  it('filters rows via the search box against the given searchAccessors', async () => {
    const user = userEvent.setup();
    render(<DataTable columns={columns} rows={rows} rowKey="id" searchAccessors={[(r) => r.name]} />);

    await user.type(screen.getByRole('searchbox'), 'ali');

    expect(screen.getByText('alice')).toBeInTheDocument();
    expect(screen.queryByText('Charlie')).not.toBeInTheDocument();
    expect(screen.queryByText('Bob')).not.toBeInTheDocument();
  });

  it('shows the empty state (not a table) when rows is empty from the start', () => {
    render(<DataTable columns={columns} rows={[]} rowKey="id" emptyTitle="Nothing here yet" />);
    expect(screen.getByText('Nothing here yet')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('shows a distinct no-results state when a search matches nothing, without wiping the real rows prop', async () => {
    const user = userEvent.setup();
    render(<DataTable columns={columns} rows={rows} rowKey="id" searchAccessors={[(r) => r.name]} />);

    await user.type(screen.getByRole('searchbox'), 'zzz-no-match');

    expect(screen.getByText('No results')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('sorts ascending on first header click and descending on a second click of the same column', async () => {
    const user = userEvent.setup();
    render(<DataTable columns={columns} rows={rows} rowKey="id" />);

    const nameHeader = screen.getByRole('button', { name: /Name/ });
    await user.click(nameHeader);
    expect(nameCells()).toEqual(['alice', 'Bob', 'Charlie']);

    await user.click(nameHeader);
    expect(nameCells()).toEqual(['Charlie', 'Bob', 'alice']);
  });

  it('switching sort to a different column resets to ascending on that column', async () => {
    const user = userEvent.setup();
    render(<DataTable columns={columns} rows={rows} rowKey="id" />);

    await user.click(screen.getByRole('button', { name: /Age/ }));
    const ageCells = () => screen.getAllByRole('row').slice(1).map((row) => within(row).getAllByRole('cell')[1].textContent);
    expect(ageCells()).toEqual(['25', '30', '40']);
  });

  it('shows loading skeletons instead of the table while isLoading', () => {
    const { container } = render(<DataTable columns={columns} rows={rows} rowKey="id" isLoading />);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(container.querySelectorAll('.animate-pulse')).toHaveLength(3);
  });

  it('shows the error banner with a retry action instead of the table on error', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    render(<DataTable columns={columns} rows={rows} rowKey="id" error={{ message: 'Boom' }} onRetry={onRetry} />);

    expect(screen.getByText('Boom')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('renderExpanded toggles an inline panel open/closed on row click without navigating', async () => {
    const user = userEvent.setup();
    render(
      <DataTable
        columns={columns}
        rows={rows}
        rowKey="id"
        renderExpanded={(row) => <div>Details for {row.name}</div>}
      />
    );

    expect(screen.queryByText('Details for Charlie')).not.toBeInTheDocument();

    await user.click(screen.getByText('Charlie'));
    expect(screen.getByText('Details for Charlie')).toBeInTheDocument();

    await user.click(screen.getByText('Charlie'));
    expect(screen.queryByText('Details for Charlie')).not.toBeInTheDocument();
  });
});
