import { describe, it, expect } from 'vitest';
import { renderWithProviders, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import DashboardPage from './DashboardPage';

const FULL = {
  pendingUsers: 3,
  pings: {
    count: 2,
    items: [
      { projectId: 5, projectTitle: 'World Ferns', discussionId: 9, title: 'Check Dryopteris', snippet: 'x', createdAt: '2026-08-01T00:00:00Z' },
    ],
  },
  reviewSubmissions: [{ projectId: 5, projectTitle: 'World Ferns', count: 1 }],
  missingMetadata: [{ projectId: 5, projectTitle: 'World Ferns', missing: ['license'] }],
  openErrors: [{ projectId: 5, projectTitle: 'World Ferns', count: 4 }],
  myLocks: [{ projectId: 5, projectTitle: 'World Ferns', usageId: 22, scientificName: 'Dryopteris', acquiredAt: '2026-08-01T00:00:00Z' }],
  recentTaxa: [{ projectId: 5, projectTitle: 'World Ferns', usageId: 22, scientificName: 'Dryopteris filix-mas', editedAt: '2026-08-02T00:00:00Z' }],
  projects: [{ id: 5, title: 'World Ferns', alias: 'ferns', role: 'owner', accepted: 12431, synonyms: 3102, openIssues: 4 }],
};

describe('DashboardPage', () => {
  it('renders inbox cards, recent taxa and project cards, and marks itself seen', async () => {
    let seenCalled = false;
    server.use(
      http.get('/api/me/dashboard', () => HttpResponse.json(FULL)),
      http.post('/api/me/dashboard/seen', () => {
        seenCalled = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithProviders(<DashboardPage />);

    // project card with headline counts
    expect(await screen.findByRole('link', { name: 'World Ferns' })).toBeInTheDocument();
    expect(screen.getByText(/12,431 accepted/)).toBeInTheDocument();

    // inbox items
    expect(screen.getByText(/3 people awaiting approval/i)).toBeInTheDocument();
    expect(screen.getByText(/2 new pings/i)).toBeInTheDocument();
    expect(screen.getByText(/missing license/i)).toBeInTheDocument();

    // recently edited
    expect(screen.getByRole('link', { name: /Dryopteris filix-mas/ })).toBeInTheDocument();

    // fired the seen marker
    await waitFor(() => expect(seenCalled).toBe(true));
  });

  it('hides the approvals card when there are no pending users', async () => {
    server.use(
      http.get('/api/me/dashboard', () =>
        HttpResponse.json({ ...FULL, pendingUsers: null, pings: { count: 0, items: [] } }),
      ),
      http.post('/api/me/dashboard/seen', () => new HttpResponse(null, { status: 204 })),
    );
    renderWithProviders(<DashboardPage />);
    await screen.findByRole('link', { name: 'World Ferns' });
    expect(screen.queryByText(/awaiting approval/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/new pings/i)).not.toBeInTheDocument();
  });
});
