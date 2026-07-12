import { screen, waitFor } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from './test/utils';
import { server, http, HttpResponse } from './test/server';
import App from './App';

test('anonymous visitor sees the public landing page', async () => {
  // default /api/me handler returns 401
  renderWithProviders(<App />, { route: '/' });
  // Exact-name match: PublicLayout renders a plain "Sign in" link for anonymous visitors, which
  // LoginPage's "Sign in with ORCID" anchor does not match exactly. This discriminates the public
  // landing page from a login-redirect (both of which would satisfy a looser /sign in/i regex).
  expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument();
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
  renderWithProviders(<App />, { route: '/projects' });
  // On the project-list route there is no active project, so the project title appears only as
  // the visible project-list link (the header shows a read-only project name only inside a project).
  expect(await screen.findByRole('link', { name: 'Lepidoptera' })).toBeInTheDocument();
  await waitFor(() => expect(screen.getByText('Alice')).toBeInTheDocument());
});
