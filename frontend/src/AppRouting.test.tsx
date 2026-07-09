import { screen, waitFor } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from './test/utils';
import { server, http, HttpResponse } from './test/server';
import App from './App';

test('unauthenticated user is redirected to the login page', async () => {
  // default /api/me handler returns 401
  renderWithProviders(<App />, { route: '/' });
  expect(await screen.findByText(/sign in to coldp editor/i)).toBeInTheDocument();
});

test('authenticated user sees the project list inside the app layout', async () => {
  server.use(
    http.get('/api/me', () =>
      HttpResponse.json({ id: 1, username: 'alice', orcid: '', displayName: 'Alice' }),
    ),
    http.get('/api/projects', () =>
      HttpResponse.json([{ id: 7, title: 'Lepidoptera', role: 'editor' }]),
    ),
  );
  renderWithProviders(<App />, { route: '/' });
  // On the home route there is no active project, so the project title appears only as the
  // visible project-list link (the header shows a read-only project name only inside a project).
  expect(await screen.findByRole('link', { name: 'Lepidoptera' })).toBeInTheDocument();
  await waitFor(() => expect(screen.getByText('Alice')).toBeInTheDocument());
});
