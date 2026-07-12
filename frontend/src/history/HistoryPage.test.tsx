import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import HistoryPage from './HistoryPage';

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/history" element={<HistoryPage />} />
    </Routes>,
    { route: '/projects/3/history' },
  );
}

test('renders changes with operation, entity, and author', async () => {
  server.use(
    http.get('/api/projects/3/tasks', () => HttpResponse.json([])),
    http.get('/api/projects/3/changes', () =>
      HttpResponse.json([
        {
          id: 1,
          userId: 1,
          username: 'admin',
          at: '2026-07-09T10:00:00Z',
          entityType: 'name_usage',
          entityId: 9,
          operation: 'UPDATE',
          diff: '{"status":{"from":"ACCEPTED","to":"SYNONYM"}}',
          taskId: null,
        },
      ]),
    ),
  );
  renderPage();
  expect(await screen.findByText('name_usage #9')).toBeInTheDocument();
  expect(screen.getByText('update')).toBeInTheDocument(); // operation badge (lower-cased)
  expect(screen.getByText('admin')).toBeInTheDocument();
});

test('shows an empty state when there are no changes', async () => {
  server.use(
    http.get('/api/projects/3/tasks', () => HttpResponse.json([])),
    http.get('/api/projects/3/changes', () => HttpResponse.json([])),
  );
  renderPage();
  expect(await screen.findByText('No changes')).toBeInTheDocument();
});

test('links a name_usage change to the Names page, a reference change to References, and no link for a deletion', async () => {
  server.use(
    http.get('/api/projects/3/tasks', () => HttpResponse.json([])),
    http.get('/api/projects/3/changes', () =>
      HttpResponse.json([
        {
          id: 1,
          userId: 1,
          username: 'admin',
          at: '2026-07-09T10:00:00Z',
          entityType: 'name_usage',
          entityId: 9,
          operation: 'UPDATE',
          diff: '{}',
          taskId: null,
        },
        {
          id: 2,
          userId: 1,
          username: 'admin',
          at: '2026-07-09T10:00:00Z',
          entityType: 'reference',
          entityId: 42,
          operation: 'CREATE',
          diff: '{}',
          taskId: null,
        },
        {
          id: 3,
          userId: 1,
          username: 'admin',
          at: '2026-07-09T10:00:00Z',
          entityType: 'name_usage',
          entityId: 7,
          operation: 'DELETE',
          diff: '{}',
          taskId: null,
        },
      ]),
    ),
  );
  renderPage();

  const usageLink = await screen.findByRole('link', { name: 'name_usage #9' });
  expect(usageLink).toHaveAttribute('href', '/projects/3/names?usage=9');

  const refLink = screen.getByRole('link', { name: 'reference #42' });
  expect(refLink).toHaveAttribute('href', '/projects/3/references?ref=42');

  // Deleted entities are gone -- plain text, no link.
  expect(screen.getByText('name_usage #7')).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'name_usage #7' })).not.toBeInTheDocument();
});
