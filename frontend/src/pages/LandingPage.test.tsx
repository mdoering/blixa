import { describe, it, expect } from 'vitest';
import { renderWithProviders, screen } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import LandingPage from './LandingPage';

describe('LandingPage', () => {
  it('lists public projects and shows the ORCID button when enabled and anonymous', async () => {
    server.use(
      http.get('/api/me', () => new HttpResponse(null, { status: 401 })),
      http.get('/api/config', () => HttpResponse.json({ orcidEnabled: true })),
      http.get('/api/public/projects', () => HttpResponse.json([
        { id: 5, title: 'World Ferns', alias: 'ferns', description: 'A checklist',
          latestVersion: '1.0', latestReleasedAt: '2026-07-01T00:00:00Z', nameUsageCount: 42 },
      ])),
    );
    renderWithProviders(<LandingPage />);
    expect(await screen.findByRole('link', { name: /world ferns/i })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: /sign in with orcid/i })).toBeInTheDocument();
  });

  it('shows the local login form when ORCID is not configured', async () => {
    server.use(
      http.get('/api/me', () => new HttpResponse(null, { status: 401 })),
      http.get('/api/config', () => HttpResponse.json({ orcidEnabled: false })),
      http.get('/api/public/projects', () => HttpResponse.json([])),
    );
    renderWithProviders(<LandingPage />);
    expect(await screen.findByLabelText(/username/i)).toBeInTheDocument();
  });
});
