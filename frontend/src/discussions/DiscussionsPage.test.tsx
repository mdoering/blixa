import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import DiscussionsPage from './DiscussionsPage';

const project = { id: 3, title: 'P', role: 'owner' };

const discussion = {
  id: 1,
  projectId: 3,
  title: 'Wrong parent for Panthera',
  body: 'The genus placement looks off.',
  status: 'OPEN',
  visibility: 'INTERNAL',
  authorId: 9,
  authorOrcid: null,
  authorName: 'alice',
  createdVia: 'UI',
  createdAt: '2026-07-20T00:00:00Z',
  updatedAt: '2026-07-20T00:00:00Z',
  version: 0,
};

function mockBasics() {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
    http.get('/api/projects/3/members', () => HttpResponse.json([])),
  );
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/discussions" element={<DiscussionsPage />} />
    </Routes>,
    { route: '/projects/3/discussions' },
  );
}

test('renders discussion rows from the list', async () => {
  mockBasics();
  server.use(
    http.get('/api/projects/3/discussions', () =>
      HttpResponse.json({ items: [discussion], total: 1 }),
    ),
  );
  renderPage();

  expect(await screen.findByRole('heading', { name: 'Discussions', level: 3 })).toBeInTheDocument();
  const titleCell = await screen.findByText('Wrong parent for Panthera');
  const row = titleCell.closest('tr') as HTMLElement;
  // status badge + author live in the same row ("Open" also appears in the closed status filter)
  expect(within(row).getByText('Open')).toBeInTheDocument();
  expect(within(row).getByText('alice')).toBeInTheDocument();
});

test('typing a search re-queries with q', async () => {
  mockBasics();
  const seen: string[] = [];
  server.use(
    http.get('/api/projects/3/discussions', ({ request }) => {
      seen.push(new URL(request.url).search);
      return HttpResponse.json({ items: [], total: 0 });
    }),
  );
  renderPage();

  await userEvent.type(screen.getByPlaceholderText('Search discussions…'), 'Panthera');

  await waitFor(() => expect(seen.some((s) => s.includes('q=Panthera'))).toBe(true));
});

test('selecting a status filter re-queries with status', async () => {
  mockBasics();
  const seen: string[] = [];
  server.use(
    http.get('/api/projects/3/discussions', ({ request }) => {
      seen.push(new URL(request.url).search);
      return HttpResponse.json({ items: [], total: 0 });
    }),
  );
  renderPage();

  await userEvent.click(screen.getByPlaceholderText('Status'));
  await userEvent.click(await screen.findByRole('option', { name: 'Resolved' }));

  await waitFor(() => expect(seen.some((s) => s.includes('status=RESOLVED'))).toBe(true));
});

test('creating a discussion posts title + body', async () => {
  mockBasics();
  let posted: unknown = null;
  server.use(
    http.get('/api/projects/3/discussions', () => HttpResponse.json({ items: [], total: 0 })),
    http.post('/api/projects/3/discussions', async ({ request }) => {
      posted = await request.json();
      return HttpResponse.json({ ...discussion, title: 'New topic', body: null }, { status: 201 });
    }),
  );
  renderPage();

  await userEvent.click(screen.getByRole('button', { name: 'New discussion' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText(/Title/), 'New topic');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }));

  await waitFor(() => expect(posted).toEqual({ title: 'New topic', body: null }));
});
