import { afterEach, expect, test, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/utils';
import * as discussionsApi from '../api/discussions';
import { readActiveObjectiveId, writeActiveObjectiveId } from '../api/activeObjective';
import ActiveObjectiveSelector from './ActiveObjectiveSelector';

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

function mockOpenObjectives(items: { id: number; title: string }[]) {
  return vi.spyOn(discussionsApi, 'listDiscussions').mockResolvedValue({
    items: items.map((i) => ({ id: i.id, title: i.title, status: 'OPEN' })),
    total: items.length,
  } as unknown as discussionsApi.DiscussionPage);
}

test('defaults to "No objective" and lets you pick one (which activates it)', async () => {
  mockOpenObjectives([
    { id: 5, title: 'Revise Felidae' },
    { id: 6, title: 'Check synonyms' },
  ]);
  renderWithProviders(<ActiveObjectiveSelector pid={3} />);

  const trigger = await screen.findByRole('button', { name: 'Active objective' });
  expect(trigger).toHaveTextContent('No objective');

  await userEvent.click(trigger);
  await userEvent.click(await screen.findByText('Revise Felidae'));

  await waitFor(() => expect(readActiveObjectiveId(3)).toBe(5));
  expect(trigger).toHaveTextContent('Revise Felidae');
});

test('creating a new objective activates it', async () => {
  // Dynamic mock mirroring the backend: a created objective appears in the OPEN list's refetch.
  const open: { id: number; title: string; status: string }[] = [];
  vi.spyOn(discussionsApi, 'listDiscussions').mockImplementation(
    async () => ({ items: [...open], total: open.length }) as unknown as discussionsApi.DiscussionPage,
  );
  const create = vi.spyOn(discussionsApi, 'createDiscussion').mockImplementation(async (_pid, payload) => {
    const created = { id: 9, title: payload.title, status: 'OPEN' };
    open.unshift(created);
    return created as unknown as discussionsApi.Discussion;
  });

  renderWithProviders(<ActiveObjectiveSelector pid={3} />);
  await userEvent.click(await screen.findByRole('button', { name: 'Active objective' }));
  await userEvent.click(await screen.findByText(/New objective/));

  await userEvent.type(await screen.findByLabelText('Title'), 'New work');
  await userEvent.click(screen.getByRole('button', { name: /create/i }));

  await waitFor(() => expect(create).toHaveBeenCalledWith(3, { title: 'New work', body: null }));
  await waitFor(() => expect(readActiveObjectiveId(3)).toBe(9));
});

test('reconciles a stale active id (not among OPEN objectives) to None', async () => {
  writeActiveObjectiveId(3, 999); // stale: not in the OPEN list below
  mockOpenObjectives([{ id: 5, title: 'Revise Felidae' }]);

  renderWithProviders(<ActiveObjectiveSelector pid={3} />);
  await screen.findByRole('button', { name: 'Active objective' });

  await waitFor(() => expect(readActiveObjectiveId(3)).toBeNull());
  expect(screen.getByRole('button', { name: 'Active objective' })).toHaveTextContent('No objective');
});
