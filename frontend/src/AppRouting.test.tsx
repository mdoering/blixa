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
      HttpResponse.json([{ id: 7, slug: 'lep', title: 'Lepidoptera', role: 'editor' }]),
    ),
  );
  renderWithProviders(<App />, { route: '/' });
  expect(await screen.findByText('Lepidoptera')).toBeInTheDocument();
  await waitFor(() => expect(screen.getByText('Alice')).toBeInTheDocument());
});
