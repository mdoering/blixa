import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

// The objective filter is fed by the OPEN discussions list.
function mockObjectives(items: { id: number; title: string }[] = []) {
  server.use(
    http.get('/api/projects/3/discussions', () =>
      HttpResponse.json({ items: items.map((i) => ({ ...i, status: 'OPEN' })), total: items.length }),
    ),
  );
}

const change = (over: Record<string, unknown>) => ({
  id: 1,
  userId: 1,
  username: 'admin',
  at: '2026-07-09T10:00:00Z',
  entityType: 'name_usage',
  entityId: 9,
  operation: 'UPDATE',
  diff: '{}',
  discussionId: null,
  discussionTitle: null,
  entityLabel: null,
  ...over,
});

test('renders changes with operation, entity, and author', async () => {
  mockObjectives();
  server.use(
    http.get('/api/projects/3/changes', () =>
      HttpResponse.json([change({ diff: '{"status":{"from":"ACCEPTED","to":"SYNONYM"}}' })]),
    ),
  );
  renderPage();
  expect(await screen.findByText('name_usage #9')).toBeInTheDocument();
  expect(screen.getByText('update')).toBeInTheDocument(); // operation badge (lower-cased)
  expect(screen.getByText('admin')).toBeInTheDocument();
});

test('shows an empty state when there are no changes', async () => {
  mockObjectives();
  server.use(http.get('/api/projects/3/changes', () => HttpResponse.json([])));
  renderPage();
  expect(await screen.findByText('No changes')).toBeInTheDocument();
});

test('shows the objective on a change row and filters by objective', async () => {
  mockObjectives([{ id: 5, title: 'Revise Felidae' }]);
  let lastUrl = '';
  server.use(
    http.get('/api/projects/3/changes', ({ request }) => {
      lastUrl = request.url;
      return HttpResponse.json([change({ discussionId: 5, discussionTitle: 'Revise Felidae' })]);
    }),
  );
  renderPage();

  // the objective title shows on the change row (show-on-rows)
  await waitFor(() => expect(screen.getAllByText('Revise Felidae').length).toBeGreaterThan(0));

  // picking the objective in the filter re-queries with discussionId
  await userEvent.click(screen.getByPlaceholderText('All objectives'));
  await userEvent.click(await screen.findByRole('option', { name: 'Revise Felidae' }));
  await waitFor(() => expect(lastUrl).toContain('discussionId=5'));
});

test('links a name_usage change to Names, a reference change to References, and no link for a deletion', async () => {
  mockObjectives();
  server.use(
    http.get('/api/projects/3/changes', () =>
      HttpResponse.json([
        change({ id: 1, entityType: 'name_usage', entityId: 9, operation: 'UPDATE' }),
        change({ id: 2, entityType: 'reference', entityId: 42, operation: 'CREATE' }),
        change({ id: 3, entityType: 'name_usage', entityId: 7, operation: 'DELETE' }),
      ]),
    ),
  );
  renderPage();

  const usageLink = await screen.findByRole('link', { name: 'name_usage #9' });
  expect(usageLink).toHaveAttribute('href', '/projects/3/names?usage=9');
  const refLink = screen.getByRole('link', { name: 'reference #42' });
  expect(refLink).toHaveAttribute('href', '/projects/3/references?ref=42');
  expect(screen.getByText('name_usage #7')).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'name_usage #7' })).not.toBeInTheDocument();
});

test('shows the resolved entity label as the (linked) title when the backend provides one', async () => {
  mockObjectives();
  server.use(
    http.get('/api/projects/3/changes', () =>
      HttpResponse.json([
        change({ id: 1, entityType: 'name_usage', entityId: 9, entityLabel: 'Panthera leo Linnaeus, 1758' }),
        change({ id: 2, entityType: 'reference', entityId: 42, entityLabel: 'Mill. 1768' }),
      ]),
    ),
  );
  renderPage();

  const usageLink = await screen.findByRole('link', { name: 'Panthera leo Linnaeus, 1758' });
  expect(usageLink).toHaveAttribute('href', '/projects/3/names?usage=9');
  expect(screen.getByRole('link', { name: 'Mill. 1768' })).toHaveAttribute(
    'href',
    '/projects/3/references?ref=42',
  );
  // the raw fallback is no longer shown for these.
  expect(screen.queryByText('name_usage #9')).not.toBeInTheDocument();
});
