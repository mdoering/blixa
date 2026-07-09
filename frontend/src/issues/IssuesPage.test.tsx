import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import IssuesPage from './IssuesPage';

function mockProject(role = 'owner') {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json({ id: 3, title: 'P', role })),
    http.get('/api/projects/3/issues/summary', () =>
      HttpResponse.json({ total: 2, byStatus: { open: 2 }, bySeverity: { warning: 1, info: 1 } }),
    ),
    http.get('/api/projects/3/issues', () =>
      HttpResponse.json([
        {
          id: 5,
          entityType: 'name_usage',
          entityId: 9,
          rule: 'genus_mismatch',
          severity: 'warning',
          message: 'genus differs',
          status: 'open',
        },
      ]),
    ),
  );
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/issues" element={<IssuesPage />} />
    </Routes>,
    { route: '/projects/3/issues' },
  );
}

test('renders the summary rollup and the issue rows', async () => {
  mockProject();
  renderPage();
  expect(await screen.findByText('genus_mismatch')).toBeInTheDocument();
  expect(screen.getByText(/total 2/)).toBeInTheDocument();
  expect(screen.getByText(/warning 1/)).toBeInTheDocument();
});

test('the entity cell deep-links to the usage on the Names page', async () => {
  mockProject();
  renderPage();
  await screen.findByText('genus_mismatch');
  const link = screen.getByRole('link', { name: 'name_usage #9' });
  expect(link).toHaveAttribute('href', '/projects/3/names?usage=9');
});

test('accepting an issue POSTs the review action', async () => {
  mockProject();
  let reviewed: unknown = null;
  server.use(
    http.post('/api/projects/3/issues/5/review', async ({ request }) => {
      reviewed = await request.json();
      return HttpResponse.json({ id: 5, status: 'accepted' });
    }),
  );
  renderPage();
  await screen.findByText('genus_mismatch');
  await userEvent.click(screen.getByLabelText('Review genus_mismatch'));
  await userEvent.click(await screen.findByText('Accept'));
  await waitFor(() => expect(reviewed).toEqual({ action: 'accept' }));
});

test('a viewer sees no Revalidate button or review menu', async () => {
  mockProject('viewer');
  renderPage();
  await screen.findByText('genus_mismatch');
  expect(screen.queryByRole('button', { name: 'Revalidate' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Review genus_mismatch')).not.toBeInTheDocument();
});
