/**
 * Client-side search/sort helpers backing components/DataTable.jsx. Every
 * list-fetching hook in api/queries.js returns the full tenant/owner-scoped
 * list already (no server-side sort/pagination exists anywhere in this app),
 * and every list this app has is small (seed data is single digits; nothing
 * is bulk-generated) - so search/sort run entirely over the already-fetched
 * array, no new backend endpoint needed.
 */

/** Case-insensitive substring match across a list of (row) => string|number|null accessors. */
export function matchesQuery(row, query, accessors) {
  if (!query) return true;
  const needle = query.trim().toLowerCase();
  if (!needle) return true;
  return accessors.some((accessor) => {
    const value = accessor(row);
    if (value === null || value === undefined) return false;
    return String(value).toLowerCase().includes(needle);
  });
}

/**
 * Generic comparator for string/number/date/boolean values - null/undefined
 * always sorts last regardless of direction, so an unset optional field
 * (e.g. a provider with no room) never jumps to the top on a descending sort.
 */
export function compareValues(a, b) {
  const aNil = a === null || a === undefined || a === '';
  const bNil = b === null || b === undefined || b === '';
  if (aNil && bNil) return 0;
  if (aNil) return 1;
  if (bNil) return -1;

  if (a instanceof Date || b instanceof Date) {
    return new Date(a).getTime() - new Date(b).getTime();
  }
  if (typeof a === 'number' && typeof b === 'number') {
    return a - b;
  }
  if (typeof a === 'boolean' && typeof b === 'boolean') {
    return Number(a) - Number(b);
  }
  return String(a).localeCompare(String(b), undefined, { sensitivity: 'base', numeric: true });
}
