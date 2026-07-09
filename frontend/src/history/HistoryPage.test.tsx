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
