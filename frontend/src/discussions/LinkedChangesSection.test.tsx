import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import LinkedChangesSection from './LinkedChangesSection';

const change = {
  id: 5,
  userId: 9,
  username: 'alice',
  at: '2026-07-20T00:00:00Z',
  entityType: 'name_usage',
  entityId: 7,
  operation: 'CREATE',
  diff: '{}',
  taskId: null,
};

test('lists linked changes and links a recent one', async () => {
  let linkedId: number | null = null;
  server.use(
    http.get('/api/projects/3/discussions/1/changes', () => HttpResponse.json([])),
    http.get('/api/projects/3/changes', () => HttpResponse.json([change])),
    http.post('/api/projects/3/discussions/1/changes', async ({ request }) => {
      linkedId = ((await request.json()) as { changeId: number }).changeId;
      return new HttpResponse(null, { status: 200 });
    }),
  );
  renderWithProviders(<LinkedChangesSection pid={3} did={1} canEdit />);

  expect(await screen.findByText('No linked changes.')).toBeInTheDocument();

  await userEvent.click(screen.getByPlaceholderText('Link a recent change…'));
  await userEvent.click(await screen.findByRole('option', { name: /CREATE name_usage #7/ }));
  await userEvent.click(screen.getByRole('button', { name: 'Link' }));

  await waitFor(() => expect(linkedId).toBe(5));
});
