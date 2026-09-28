import { describe, it, expect } from 'vitest';
import { matchesQuery, compareValues } from './tableUtils.js';

describe('matchesQuery', () => {
  const row = { name: 'Demo FrontDesk', email: 'demo-front-desk@example.test' };
  const accessors = [(r) => r.name, (r) => r.email];

  it('matches everything when the query is empty', () => {
    expect(matchesQuery(row, '', accessors)).toBe(true);
    expect(matchesQuery(row, '   ', accessors)).toBe(true);
  });

  it('matches case-insensitively against any accessor', () => {
    expect(matchesQuery(row, 'frontdesk', accessors)).toBe(true);
    expect(matchesQuery(row, 'DEMO-FRONT-DESK@EXAMPLE.TEST', accessors)).toBe(true);
  });

  it('matches a substring, not just a whole-word or prefix', () => {
    expect(matchesQuery(row, 'ont', accessors)).toBe(true);
  });

  it('does not match when no accessor contains the query', () => {
    expect(matchesQuery(row, 'nonexistent', accessors)).toBe(false);
  });

  it('skips a null/undefined accessor value instead of throwing', () => {
    const rowWithGap = { name: 'Guest', email: null };
    expect(matchesQuery(rowWithGap, 'guest', accessors)).toBe(true);
    expect(matchesQuery(rowWithGap, 'anything', accessors)).toBe(false);
  });
});

describe('compareValues', () => {
  it('sorts numbers numerically', () => {
    expect(compareValues(1, 2)).toBeLessThan(0);
    expect(compareValues(10, 2)).toBeGreaterThan(0);
    expect(compareValues(5, 5)).toBe(0);
  });

  it('sorts strings case- and diacritic-insensitively, numeric-aware', () => {
    expect(compareValues('apple', 'Banana')).toBeLessThan(0);
    expect(compareValues('item2', 'item10')).toBeLessThan(0); // numeric-aware, not lexicographic
  });

  it('sorts booleans false-before-true', () => {
    expect(compareValues(false, true)).toBeLessThan(0);
    expect(compareValues(true, false)).toBeGreaterThan(0);
  });

  it('sorts Dates chronologically', () => {
    const earlier = new Date('2026-01-01');
    const later = new Date('2026-06-01');
    expect(compareValues(earlier, later)).toBeLessThan(0);
  });

  it('always sorts null/undefined/empty-string last, regardless of the other value', () => {
    expect(compareValues(null, 5)).toBeGreaterThan(0);
    expect(compareValues(5, null)).toBeLessThan(0);
    expect(compareValues(undefined, 'x')).toBeGreaterThan(0);
    expect(compareValues('', 'x')).toBeGreaterThan(0);
    expect(compareValues(null, undefined)).toBe(0);
  });
});
