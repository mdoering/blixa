import { expect, test } from 'vitest';
import { render, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import PublicLayout from './PublicLayout';

// The default /api/me handler returns 401, so PublicLayout renders the anonymous "Sign in" link.

test('header "Sign in" goes straight to ORCID when ORCID is enabled', async () => {
  server.use(http.get('/api/config', () => HttpResponse.json({ orcidEnabled: true })));
  render(<PublicLayout />);
  await waitFor(() =>
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/orcid',
    ),
  );
});

test('header "Sign in" goes to the local /signin page when ORCID is disabled', async () => {
  server.use(http.get('/api/config', () => HttpResponse.json({ orcidEnabled: false })));
  render(<PublicLayout />);
  const link = await screen.findByRole('link', { name: 'Sign in' });
  await waitFor(() => expect(link).toHaveAttribute('href', '/signin'));
});
