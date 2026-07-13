import { describe, it, expect } from 'vitest';
import { renderWithProviders, screen } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import LandingPage from './LandingPage';

describe('LandingPage', () => {
  it('lists public projects; anonymous visitors get no login button (the header Sign-in is enough)', async () => {
    server.use(
      http.get('/api/me', () => new HttpResponse(null, { status: 401 })),
      http.get('/api/public/projects', () => HttpResponse.json([
        { id: 5, title: 'World Ferns', alias: 'ferns', description: 'A checklist',
          latestVersion: '1.0', latestReleasedAt: '2026-07-01T00:00:00Z', nameUsageCount: 42 },
      ])),
    );
    renderWithProviders(<LandingPage />);
    expect(await screen.findByRole('link', { name: /world ferns/i })).toBeInTheDocument();
    // No inline login on the landing anymore -- anonymous visitors use the header's "Sign in".
    expect(screen.queryByRole('link', { name: /^log in$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^my projects$/i })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/username/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /sign in with orcid/i })).not.toBeInTheDocument();
  });

  it('shows a My projects link for signed-in visitors', async () => {
    server.use(
      http.get('/api/me', () =>
        HttpResponse.json({ id: 1, username: 'alice', orcid: '', displayName: 'Alice' }),
      ),
      http.get('/api/public/projects', () => HttpResponse.json([])),
    );
    renderWithProviders(<LandingPage />);
    expect(await screen.findByRole('link', { name: /my projects/i })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^log in$/i })).not.toBeInTheDocument();
  });
});
