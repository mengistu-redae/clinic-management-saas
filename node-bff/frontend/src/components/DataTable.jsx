import { Fragment, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SearchIcon, SortIcon, SortAscIcon, SortDescIcon, ChevronDownIcon, ChevronRightIcon } from './icons.jsx';
import Skeleton from './Skeleton.jsx';
import ErrorBanner from './ErrorBanner.jsx';
import EmptyState from './EmptyState.jsx';
import { matchesQuery, compareValues } from '../lib/tableUtils.js';

/**
 * The one generic table every converted list page in this redesign adopts -
 * see the "modern UI redesign" plan. Client-side search + column sort only
 * (no list-fetching hook in api/queries.js supports server-side sort/
 * pagination, and every list in this app is small - seed data ships single
 * digits of most resources). Loading/error/empty states reuse
 * Skeleton/ErrorBanner/EmptyState unchanged.
 *
 * `columns`: [{ key, header, accessor(row), sortAccessor?(row), sortable?,
 * render?(row), className?, headerClassName? }]. `accessor` backs both the
 * default cell render and (unless `sortAccessor` is given) sorting; `render`
 * overrides just the cell's own JSX (e.g. a StatusPill) while `accessor`
 * still feeds search/sort.
 *
 * `renderExpanded(row)` - when given, each row gets a leading
 * chevron-toggle cell instead of `onRowClick` navigation, re-hosting this
 * app's existing "expand a row into an inline edit form" pages (Referrals,
 * Providers) under one shared toggle mechanism instead of each page's own.
 */
export default function DataTable({
  columns,
  rows,
  rowKey,
  searchAccessors,
  searchPlaceholder,
  onRowClick,
  renderExpanded,
  isExpandedByDefault,
  defaultSortKey,
  defaultSortDir = 'asc',
  isLoading = false,
  error = null,
  onRetry,
  emptyTitle,
  emptyDescription,
}) {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [sortKey, setSortKey] = useState(defaultSortKey ?? null);
  const [sortDir, setSortDir] = useState(defaultSortDir);
  const [expandedId, setExpandedId] = useState(null);

  const getKey = typeof rowKey === 'function' ? rowKey : (row) => row[rowKey];

  const filtered = useMemo(() => {
    if (!searchAccessors || !searchAccessors.length) return rows;
    return rows.filter((row) => matchesQuery(row, query, searchAccessors));
  }, [rows, query, searchAccessors]);

  const sorted = useMemo(() => {
    if (!sortKey) return filtered;
    const column = columns.find((c) => c.key === sortKey);
    if (!column) return filtered;
    const accessor = column.sortAccessor || column.accessor;
    if (!accessor) return filtered;
    const copy = [...filtered];
    copy.sort((a, b) => {
      const cmp = compareValues(accessor(a), accessor(b));
      return sortDir === 'desc' ? -cmp : cmp;
    });
    return copy;
  }, [filtered, sortKey, sortDir, columns]);

  function toggleSort(column) {
    if (!column.sortable) return;
    if (sortKey === column.key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(column.key);
      setSortDir('asc');
    }
  }

  function toggleExpanded(id) {
    setExpandedId((current) => (current === id ? null : id));
  }

  if (isLoading) {
    return (
      <div className="flex flex-col gap-2">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    );
  }
  if (error) {
    return <ErrorBanner message={error?.message} onRetry={onRetry} />;
  }

  return (
    <div className="flex flex-col gap-3">
      {searchAccessors && searchAccessors.length > 0 && (
        <div className="relative w-full max-w-sm">
          <SearchIcon className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-muted" />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={searchPlaceholder || t('common.search')}
            className="w-full rounded-lg border border-slate-300 bg-surface py-2 pl-9 pr-3 text-sm text-ink focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
          />
        </div>
      )}

      {rows.length === 0 ? (
        <EmptyState title={emptyTitle || t('common.noResults')} description={emptyDescription} />
      ) : sorted.length === 0 ? (
        <EmptyState title={t('common.noResults')} description={t('common.noResultsHint')} />
      ) : (
        <div className="overflow-x-auto rounded-xl border border-slate-200 bg-surface">
          <table className="w-full min-w-max border-collapse text-left text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-ink-muted">
                {renderExpanded && <th className="w-8 px-3 py-2.5" />}
                {columns.map((column) => (
                  <th key={column.key} className={`px-3 py-2.5 font-semibold ${column.headerClassName || ''}`}>
                    {column.sortable ? (
                      <button
                        type="button"
                        onClick={() => toggleSort(column)}
                        className="inline-flex items-center gap-1 hover:text-ink"
                        aria-sort={sortKey === column.key ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}
                      >
                        {column.header}
                        {sortKey === column.key ? (
                          sortDir === 'asc' ? <SortAscIcon className="h-3.5 w-3.5" /> : <SortDescIcon className="h-3.5 w-3.5" />
                        ) : (
                          <SortIcon className="h-3.5 w-3.5 opacity-40" />
                        )}
                      </button>
                    ) : (
                      column.header
                    )}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sorted.map((row) => {
                const key = getKey(row);
                const expanded = renderExpanded ? expandedId === key || isExpandedByDefault?.(row) : false;
                const activate = onRowClick ? () => onRowClick(row) : renderExpanded ? () => toggleExpanded(key) : undefined;
                return (
                  <Fragment key={key}>
                    <tr
                      className={`border-b border-slate-100 last:border-0 ${
                        onRowClick ? 'cursor-pointer hover:bg-slate-50' : renderExpanded ? 'hover:bg-slate-50' : ''
                      } ${activate ? 'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand/50' : ''}`}
                      onClick={activate}
                      // A row that navigates or expands is otherwise a bare <tr> -
                      // not natively focusable or operable from a keyboard, and
                      // invisible to a screen reader as an interactive element.
                      // tabIndex + role + Enter/Space make it behave like a real
                      // button/link for the same gesture the onClick already uses.
                      tabIndex={activate ? 0 : undefined}
                      role={activate ? 'button' : undefined}
                      aria-expanded={renderExpanded ? expanded : undefined}
                      onKeyDown={
                        activate
                          ? (e) => {
                              if (e.key === 'Enter' || e.key === ' ') {
                                e.preventDefault();
                                activate();
                              }
                            }
                          : undefined
                      }
                    >
                      {renderExpanded && (
                        <td className="px-3 py-2.5 text-ink-muted">
                          {expanded ? <ChevronDownIcon className="h-4 w-4" /> : <ChevronRightIcon className="h-4 w-4" />}
                        </td>
                      )}
                      {columns.map((column) => (
                        <td key={column.key} className={`px-3 py-2.5 text-ink ${column.className || ''}`}>
                          {column.render ? column.render(row) : String(column.accessor ? column.accessor(row) ?? '—' : '—')}
                        </td>
                      ))}
                    </tr>
                    {renderExpanded && expanded && (
                      <tr className="border-b border-slate-100 bg-slate-50 last:border-0">
                        <td colSpan={columns.length + 1} className="px-4 py-4">
                          {renderExpanded(row)}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
