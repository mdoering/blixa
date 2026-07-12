import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import CurrentWorkPage from './CurrentWorkPage';

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/activity" element={<CurrentWorkPage />} />
    </Routes>,
    { route: '/projects/3/activity' },
  );
}

test('renders active locks with entity link, holder, and expiry', async () => {
  server.use(
    http.get('/api/projects/3/locks', () =>
      HttpResponse.json([
        {
          id: 1,
          entityType: 'name_usage',
          entityId: 9,
          userId: 1,
          username: 'alice',
          acquiredAt: '2026-07-09T10:00:00Z',
          expiresAt: '2026-07-09T10:05:00Z',
          heldByMe: true,
          taskId: null,
          taskTitle: null,
        },
        {
          id: 2,
          entityType: 'name_usage',
          entityId: 42,
          userId: 2,
          username: 'bob',
          acquiredAt: '2026-07-09T09:00:00Z',
          expiresAt: '2026-07-09T09:05:00Z',
          heldByMe: false,
          taskId: null,
          taskTitle: null,
        },
      ]),
    ),
  );
  renderPage();

  const link1 = await screen.findByRole('link', { name: 'name_usage #9' });
  expect(link1).toHaveAttribute('href', '/projects/3/names?usage=9');
  const link2 = screen.getByRole('link', { name: 'name_usage #42' });
  expect(link2).toHaveAttribute('href', '/projects/3/names?usage=42');

  expect(screen.getByText(/alice/)).toBeInTheDocument();
  expect(screen.getByText(/\(you\)/)).toBeInTheDocument();
  expect(screen.getByText('bob')).toBeInTheDocument();

  // Only the heldByMe row shows a Release button.
  expect(screen.getAllByRole('button', { name: 'Release' })).toHaveLength(1);
});

test('shows an empty state when there are no active locks', async () => {
  server.use(http.get('/api/projects/3/locks', () => HttpResponse.json([])));
  renderPage();
  expect(await screen.findByText('No one is editing anything right now.')).toBeInTheDocument();
});

test('clicking Release calls releaseLock and refetches the list', async () => {
  let released: string | undefined;
  let getCount = 0;
  server.use(
    http.get('/api/projects/3/locks', () => {
      getCount += 1;
      return HttpResponse.json([
        {
          id: 1,
          entityType: 'name_usage',
          entityId: 9,
          userId: 1,
          username: 'alice',
          acquiredAt: '2026-07-09T10:00:00Z',
          expiresAt: '2026-07-09T10:05:00Z',
          heldByMe: true,
          taskId: null,
          taskTitle: null,
        },
      ]);
    }),
    http.delete('/api/projects/3/locks/:id', ({ params }) => {
      released = params.id as string;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderPage();

  await screen.findByRole('link', { name: 'name_usage #9' });
  const countAfterInitial = getCount;
  await userEvent.click(screen.getByRole('button', { name: 'Release' }));

  await waitFor(() => expect(released).toBe('1'));
  await waitFor(() => expect(getCount).toBeGreaterThan(countAfterInitial));
});
