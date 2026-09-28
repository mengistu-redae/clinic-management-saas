import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { apiPost } from './client.js';
import { useCreateBudget, useUpdateBudget, useDeleteBudget, useCancelSeries } from './queries.js';

vi.mock('./client.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}));

function wrapperFor(queryClient) {
  // eslint-disable-next-line react/display-name
  return ({ children }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe('budget mutations invalidate both the budgets list and the budget-vs-actual report', () => {
  // Real bug, found and fixed live 2026-09-28 (see CLAUDE.md, "Frontend
  // phase R"): these three mutations used to invalidate only
  // ['clinic', 'budgets'], leaving the Budget vs. Actual table stale after
  // a create/update/delete until an unrelated refetch happened to occur.
  let queryClient;
  let invalidateSpy;

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    apiPost.mockResolvedValue({ id: 'b1' });
  });

  it('useCreateBudget invalidates both query keys', async () => {
    const { result } = renderHook(() => useCreateBudget(), { wrapper: wrapperFor(queryClient) });
    result.current.mutate({ accountId: 'a1', year: 2026, month: 9, amount: 100 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['clinic', 'budgets'] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['clinic', 'budget-vs-actual'] });
  });

  it('useUpdateBudget invalidates both query keys', async () => {
    const { result } = renderHook(() => useUpdateBudget('b1'), { wrapper: wrapperFor(queryClient) });
    result.current.mutate({ amount: 200 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['clinic', 'budgets'] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['clinic', 'budget-vs-actual'] });
  });

  it('useDeleteBudget invalidates both query keys', async () => {
    const { result } = renderHook(() => useDeleteBudget('b1'), { wrapper: wrapperFor(queryClient) });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['clinic', 'budgets'] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['clinic', 'budget-vs-actual'] });
  });
});

describe('useCancelSeries', () => {
  it('invalidates the tenant-wide list plus every individual appointment/payments query named in the response', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    apiPost.mockResolvedValue({ cancelled: [{ id: 'appt-1' }, { id: 'appt-2' }, { id: 'appt-3' }] });

    const { result } = renderHook(() => useCancelSeries('appt-1'), { wrapper: wrapperFor(queryClient) });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['appointments'] });
    for (const id of ['appt-1', 'appt-2', 'appt-3']) {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['appointment', id] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['appointment-payments', id] });
    }
  });

  it('does not throw when the response has no cancelled list at all', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    apiPost.mockResolvedValue({});

    const { result } = renderHook(() => useCancelSeries('appt-1'), { wrapper: wrapperFor(queryClient) });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
