import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import MembersPage from './MembersPage';

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/members" element={<MembersPage />} />
    </Routes>,
    { route: '/projects/5/members' },
  );
}

test('owner can see the add form and members list', async () => {
  server.use(
    http.get('/api/projects/5', () =>
      HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'owner' }),
    ),
    http.get('/api/projects/5/members', () =>
      HttpResponse.json([{ userId: 1, username: 'boss', role: 'owner' }]),
    ),
  );
  renderPage();
  expect(await screen.findByText('boss')).toBeInTheDocument();
  expect(screen.getByPlaceholderText('username')).toBeInTheDocument();
});

test('non-owner sees a read-only list (no add form)', async () => {
  server.use(
    http.get('/api/projects/5', () =>
      HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'editor' }),
    ),
    http.get('/api/projects/5/members', () =>
      HttpResponse.json([{ userId: 1, username: 'boss', role: 'owner' }]),
    ),
  );
  renderPage();
  expect(await screen.findByText('boss')).toBeInTheDocument();
  expect(screen.queryByPlaceholderText('username')).not.toBeInTheDocument();
});

test('owner can add a member', async () => {
  let putBody: unknown = null;
  server.use(
    http.get('/api/projects/5', () =>
      HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'owner' }),
    ),
    http.get('/api/projects/5/members', () => HttpResponse.json([])),
    http.put('/api/projects/5/members', async ({ request }) => {
      putBody = await request.json();
      return new HttpResponse(null, { status: 200 });
    }),
  );
  renderPage();
  await screen.findByPlaceholderText('username');
  await userEvent.type(screen.getByPlaceholderText('username'), 'helper');
  await userEvent.click(screen.getByRole('button', { name: /add \/ update/i }));
  await waitFor(() => expect(putBody).toEqual({ username: 'helper', role: 'editor' }));
});
