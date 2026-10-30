import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import { Routes, Route } from 'react-router-dom';
import RelationConflictsPage from './RelationConflictsPage';

const rel = (over: Record<string, unknown>) => ({
  version: 0, type: 'basionym', usageId: 1, relatedUsageId: 2, referenceId: null, page: null,
  remarks: null, issues: [], remove: false, duplicate: false, preselected: false, ...over,
});

const PAIRS = [
  {
    n1: { id: 1, label: 'Aus bus L.', status: 'ACCEPTED' },
    n2: { id: 2, label: 'Cus bus (L.) Mill.', status: 'SYNONYM' },
    relations: [
      rel({ id: 10, version: 3, issues: ['NAME_RELATION_CONFLICT'], remove: true, preselected: true }),
      rel({ id: 11, version: 4, type: 'spelling-correction', issues: ['SPELLING_CORRECTION_IMPLAUSIBLE'] }),
      rel({ id: 12, version: 5, issues: ['DUPLICATE'], duplicate: true, remarks: 'only here' }),
      rel({ id: 13, version: 6, issues: ['DUPLICATE'], duplicate: true, preselected: true }),
      rel({ id: 14, version: 7, type: 'REPLACEMENT_NAME', issues: [] }),
    ],
  },
];

function renderPage(role = 'owner') {
  server.use(http.get('/api/projects/7', () => HttpResponse.json({ id: 7, title: 'P', role })));
  return render(
    <Routes>
      <Route path="/projects/:projectId/relation-conflicts" element={<RelationConflictsPage />} />
    </Routes>,
    { route: '/projects/7/relation-conflicts' },
  );
}

const box = (name: RegExp) => screen.findByRole('checkbox', { name });

describe('RelationConflictsPage', () => {
  let gets = 0;
  beforeEach(() => {
    gets = 0;
    server.use(
      http.get('/api/projects/7/name-relations/conflicts', () => {
        gets++;
        return HttpResponse.json(PAIRS);
      }),
    );
  });

  it('preselectsRemovalsAndPlainDuplicates', async () => {
    renderPage();
    expect(await box(/relation 10/)).toBeChecked();
    expect(await box(/relation 13/)).toBeChecked();
    expect(await box(/relation 11/)).not.toBeChecked();
    expect(await box(/relation 12/)).not.toBeChecked();
    expect(screen.queryByRole('checkbox', { name: /relation 14/ })).toBeNull();
    expect(screen.getByText('Contradicts another relation of these names')).toBeInTheDocument();
    expect(screen.getByText('The names differ in more than their spelling')).toBeInTheDocument();
    expect(screen.getByText('spelling correction')).toBeInTheDocument();
    expect(screen.getByText('replacement name')).toBeInTheDocument();
  });

  it('deleteSendsTheTickedRelationsWithVersions', async () => {
    let posted: unknown = null;
    server.use(
      http.post('/api/projects/7/name-relations/cleanup', async ({ request }) => {
        posted = await request.json();
        return HttpResponse.json({ deleted: 2 });
      }),
    );
    renderPage();
    await userEvent.click(await box(/relation 11/));
    await userEvent.click(await box(/relation 10/));
    await userEvent.click(screen.getByRole('button', { name: 'Delete selected (2)' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/2 name relations/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted).toEqual({ relations: [{ id: 11, version: 4 }, { id: 13, version: 6 }] });
    await waitFor(() => expect(gets).toBe(2));
  });

  it('conflictShowsMessageAndRefetches', async () => {
    server.use(
      http.post('/api/projects/7/name-relations/cleanup', () =>
        HttpResponse.json({ error: 'Relation changed' }, { status: 409 })),
    );
    renderPage();
    await box(/relation 10/);
    await userEvent.click(screen.getByRole('button', { name: /Delete selected/ }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText(/Changed by someone else/)).toBeInTheDocument();
    await waitFor(() => expect(gets).toBe(2));
  });

  it('emptyState', async () => {
    server.use(http.get('/api/projects/7/name-relations/conflicts', () => HttpResponse.json([])));
    renderPage();
    expect(await screen.findByText('No conflicting name relations')).toBeInTheDocument();
  });

  it('viewerSeesNoCheckboxes', async () => {
    renderPage('viewer');
    expect((await screen.findAllByText(/Aus bus L\./)).length).toBeGreaterThan(0);
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    expect(screen.queryByRole('button', { name: /Delete selected/ })).toBeNull();
  });
});
