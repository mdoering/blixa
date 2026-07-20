import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import { Routes, Route } from 'react-router-dom';
import HomotypicConflictsPage from './HomotypicConflictsPage';

const CONFLICTS = [
  {
    accepted: [
      { id: 1, formattedName: 'Poa annua L.', descendantCount: 3 },
      { id: 2, formattedName: 'Ochlopoa annua (L.) H.Scholz', descendantCount: 0 },
    ],
    members: [
      { id: 1, formattedName: 'Poa annua L.', status: 'ACCEPTED', acceptedTargetIds: [1], proParte: false, dualStatus: false, version: 0 },
      { id: 2, formattedName: 'Ochlopoa annua (L.) H.Scholz', status: 'ACCEPTED', acceptedTargetIds: [2], proParte: false, dualStatus: false, version: 0 },
    ],
    suggestedSurvivorId: 1,
    hasExceptions: false,
    relations: [{ usageId: 2, relatedUsageId: 1, type: 'basionym', alreadyExists: false }],
  },
];

// Shape 2: the survivor candidate `Festuca foo` (id 3) is a synonym *target* -- it appears in
// `accepted` (a distinct accepted name reachable from the cluster) but is NOT itself a cluster
// member. Member id 1 is the sole ACCEPTED member (also the suggested survivor); member id 5 is
// a SYNONYM member pointing at id 3 and must be re-pointed to the survivor, not demoted.
const SYNONYM_TARGET_CONFLICTS = [
  {
    accepted: [
      { id: 1, formattedName: 'Poa annua L.', descendantCount: 3 },
      { id: 3, formattedName: 'Festuca foo Rchb.', descendantCount: 1 },
    ],
    members: [
      { id: 1, formattedName: 'Poa annua L.', status: 'ACCEPTED', acceptedTargetIds: [1], proParte: false, dualStatus: false, version: 0 },
      { id: 5, formattedName: 'Festuca annua (L.) Rchb.', status: 'SYNONYM', acceptedTargetIds: [3], proParte: false, dualStatus: false, version: 0 },
    ],
    suggestedSurvivorId: 1,
    hasExceptions: false,
    relations: [{ usageId: 5, relatedUsageId: 1, type: 'basionym', alreadyExists: false }],
  },
];

function renderPage() {
  return render(
    <Routes>
      <Route path="/projects/:projectId/homotypic-conflicts/:rootId" element={<HomotypicConflictsPage />} />
    </Routes>,
    { route: '/projects/7/homotypic-conflicts/100' },
  );
}

describe('HomotypicConflictsPage', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/projects/7/usages/100/homotypic/conflicts', () => HttpResponse.json(CONFLICTS)),
      http.get('/api/projects/7', () => HttpResponse.json({ id: 7, title: 'P', role: 'owner' })),
    );
  });

  it('lists a conflict with its accepted candidates', async () => {
    renderPage();
    // The name appears twice by design (once in the Members list, once as a Radio.Group
    // label) whenever a member is itself one of the conflict's accepted candidates -- the
    // primary real-world case this page targets (two ACCEPTED usages sharing a basionym).
    expect((await screen.findAllByText(/Poa annua/)).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Ochlopoa annua/).length).toBeGreaterThan(0);
  });

  it('consolidates: demotes the non-survivor accepted names', async () => {
    let posted: unknown = null;
    server.use(
      http.post('/api/projects/7/usages/1/homotypic/consolidate', async ({ request }) => {
        posted = await request.json();
        return HttpResponse.json({ homotypic: [], heterotypicGroups: [], misapplied: [] });
      }),
    );
    renderPage();
    await screen.findAllByText(/Poa annua/);
    // survivor defaults to id 1 (Poa annua); Consolidate sends Ochlopoa (id 2) as the loser
    await userEvent.click(screen.getByRole('button', { name: /consolidate/i }));
    await waitFor(() =>
      expect(posted).toEqual({
        losers: [{ acceptedId: 2, version: 0 }],
        repoint: [],
        relations: [{ usageId: 2, relatedUsageId: 1, type: 'basionym' }],
      }),
    );
  });

  it('consolidates: a synonym-target accepted (e.g. Festuca foo) is never demoted, its synonym member is re-pointed', async () => {
    let posted: unknown = null;
    server.use(
      http.get('/api/projects/7/usages/100/homotypic/conflicts', () =>
        HttpResponse.json(SYNONYM_TARGET_CONFLICTS)),
      http.post('/api/projects/7/usages/1/homotypic/consolidate', async ({ request }) => {
        posted = await request.json();
        return HttpResponse.json({ homotypic: [], heterotypicGroups: [], misapplied: [] });
      }),
    );
    renderPage();
    await screen.findAllByText(/Poa annua/);
    // survivor defaults to id 1 (Poa annua); id 3 (Festuca foo) is a candidate, not a member,
    // so it must never appear in `losers`. The synonym member (id 5) must be re-pointed.
    await userEvent.click(screen.getByRole('button', { name: /consolidate/i }));
    await waitFor(() =>
      expect(posted).toEqual({
        losers: [],
        repoint: [5],
        relations: [{ usageId: 5, relatedUsageId: 1, type: 'basionym' }],
      }),
    );
  });

  it('shows the empty state when the scan finds no conflicts', async () => {
    server.use(
      http.get('/api/projects/7/usages/100/homotypic/conflicts', () => HttpResponse.json([])),
      http.get('/api/projects/7', () => HttpResponse.json({ id: 7, title: 'P', role: 'owner' })),
    );
    renderPage();
    expect(await screen.findByText('No homotypic conflicts found in this subtree.')).toBeInTheDocument();
  });

  it('shows an error state when the scan fails', async () => {
    server.use(
      http.get('/api/projects/7/usages/100/homotypic/conflicts', () => new HttpResponse(null, { status: 500 })),
      http.get('/api/projects/7', () => HttpResponse.json({ id: 7, title: 'P', role: 'owner' })),
    );
    renderPage();
    expect(await screen.findByText(/could not load conflicts/i)).toBeInTheDocument();
  });
});
