import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ProjectMetadataPage from './ProjectMetadataPage';

const project = {
  id: 3, title: 'Mammals', alias: null, description: null, nomCode: 'zoological',
  license: null, geographicScope: null, taxonomicScope: null, role: 'owner',
  gbifOccurrenceLayer: true, public: false,
};

// "Match all identifiers" is disabled unless the persisted project has a matchable identifier
// scope (a non-blank datasetKey) -- most tests in this file need the button enabled to exercise
// its click behavior, so this fixture layers a matchable 'col' scope onto the base project.
const projectWithMatchableScope = {
  ...project,
  identifierScopes: [{ scope: 'col', datasetKey: '3LXR' }],
};

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/metadata" element={<ProjectMetadataPage />} />
    </Routes>,
    { route: '/projects/3/metadata' },
  );
}

// Shared default: no COL match run has ever been started for this project. Individual tests that
// care about the latest-run view override this with server.use(...) (MSW's last-registered handler
// for a given route wins).
const noLatestMatchRun = http.get('/api/projects/3/col-match/latest', () => new HttpResponse(null, { status: 204 }));
// Shared default: no export run has ever been started. Unlike noLatestMatchRun (only needed by
// canEdit tests, since that query is gated on canEdit), the export latest query is unconditional --
// ANY member sees the Export ColDP section -- so every test in this file needs this mocked, viewer
// role included.
const noLatestExportRun = http.get('/api/projects/3/export/latest', () => new HttpResponse(null, { status: 204 }));

test('prefills the form and saves updated metadata', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
    http.put('/api/projects/3/metadata', async ({ request }) => {
      const body = (await request.json()) as { title: string };
      return HttpResponse.json({ ...project, title: body.title });
    }),
    noLatestMatchRun,
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));
  await userEvent.clear(title);
  await userEvent.type(title, 'Mammalia');
  await userEvent.click(screen.getAllByRole('button', { name: /save/i })[0]);
  await waitFor(() => expect(screen.getByText('Saved')).toBeInTheDocument());
});

test('a second Save button below Settings submits the same metadata form', async () => {
  let puts = 0;
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
    http.put('/api/projects/3/metadata', async () => {
      puts += 1;
      return HttpResponse.json(project);
    }),
    noLatestMatchRun,
    noLatestExportRun,
  );
  renderPage();
  await waitFor(() => expect(screen.getByLabelText('Title')).toHaveValue('Mammals'));

  // Two identical "Save" buttons now: the original at the top of the form and a second one after the
  // Settings section. The lower one targets the same form (via the form= attribute), so clicking it
  // submits the same metadata without scrolling back to the top.
  const saves = screen.getAllByRole('button', { name: /save/i });
  expect(saves).toHaveLength(2);
  await userEvent.click(saves[1]);
  await waitFor(() => expect(puts).toBe(1));
});

test('citation style: seeds the Select from the project and saves a changed value', async () => {
  let savedBody: { cslStyle?: string } = {};
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json({ ...project, cslStyle: 'harvard' })),
    http.put('/api/projects/3/metadata', async ({ request }) => {
      savedBody = (await request.json()) as typeof savedBody;
      return HttpResponse.json({ ...project, cslStyle: savedBody.cslStyle });
    }),
    noLatestMatchRun,
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));

  const styleSelect = screen.getByRole('textbox', { name: 'Citation style' });
  await waitFor(() => expect(styleSelect).toHaveValue('Harvard'));

  await userEvent.click(styleSelect);
  await userEvent.click(await screen.findByRole('option', { name: 'IEEE' }));

  await userEvent.click(screen.getAllByRole('button', { name: /save/i })[0]);
  await waitFor(() => expect(savedBody.cslStyle).toBe('ieee'));
});

test('toggles the GBIF occurrence layer switch and saves it', async () => {
  let savedBody: { gbifOccurrenceLayer?: boolean } = {};
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
    http.put('/api/projects/3/metadata', async ({ request }) => {
      savedBody = (await request.json()) as { gbifOccurrenceLayer?: boolean };
      return HttpResponse.json({ ...project, gbifOccurrenceLayer: savedBody.gbifOccurrenceLayer });
    }),
    noLatestMatchRun,
    noLatestExportRun,
  );
  renderPage();
  const toggle = await screen.findByLabelText('Show GBIF occurrence layer on maps');
  await waitFor(() => expect(toggle).toBeChecked());
  await userEvent.click(toggle);
  expect(toggle).not.toBeChecked();
  await userEvent.click(screen.getAllByRole('button', { name: /save/i })[0]);
  // Assert on the captured request body rather than the "Saved" notification text: the
  // notifications store is global and the prior test in this file may leave a same-text
  // notification mounted, making `getByText('Saved')` ambiguous.
  await waitFor(() => expect(savedBody.gbifOccurrenceLayer).toBe(false));
});

test('identifier scopes: prefills configured rows, defaults the COL dataset key, and saves added rows', async () => {
  let savedBody: { identifierScopes?: { scope: string; datasetKey?: string }[] } = {};
  server.use(
    http.get('/api/projects/3', () =>
      HttpResponse.json({ ...project, identifierScopes: [{ scope: 'ipni', datasetKey: null }] }),
    ),
    http.put('/api/projects/3/metadata', async ({ request }) => {
      savedBody = (await request.json()) as typeof savedBody;
      return HttpResponse.json({ ...project, identifierScopes: savedBody.identifierScopes });
    }),
    noLatestMatchRun,
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));

  // Prefilled from the loaded project: row 1 is 'ipni' with an empty (null -> '') dataset key.
  const scopeRow1 = await screen.findByRole('textbox', { name: 'Scope 1' });
  await waitFor(() => expect(scopeRow1).toHaveValue('ipni'));
  expect(screen.getByRole('textbox', { name: 'Dataset key 1' })).toHaveValue('');

  // Add a row and type the "col" scope (case-insensitive) -- its dataset key auto-defaults to the
  // CLB alias (3LXR) plus a hint, without the user having to look it up.
  await userEvent.click(screen.getByRole('button', { name: 'Add scope' }));
  const scopeRow2 = screen.getByRole('textbox', { name: 'Scope 2' });
  await userEvent.type(scopeRow2, 'col');
  const datasetKeyRow2 = screen.getByRole('textbox', { name: 'Dataset key 2' });
  await waitFor(() => expect(datasetKeyRow2).toHaveValue('3LXR'));
  expect(screen.getByText(/COL is a CLB project alias for dataset 3LXR/)).toBeInTheDocument();

  await userEvent.click(screen.getAllByRole('button', { name: /save/i })[0]);
  await waitFor(() =>
    expect(savedBody.identifierScopes).toEqual([
      { scope: 'ipni' },
      { scope: 'col', datasetKey: '3LXR' },
    ]),
  );
});

test('identifier scopes: removing a row drops it from the saved payload', async () => {
  let savedBody: { identifierScopes?: { scope: string; datasetKey?: string }[] } = {};
  server.use(
    http.get('/api/projects/3', () =>
      HttpResponse.json({
        ...project,
        identifierScopes: [
          { scope: 'ipni', datasetKey: null },
          { scope: 'gbif', datasetKey: null },
        ],
      }),
    ),
    http.put('/api/projects/3/metadata', async ({ request }) => {
      savedBody = (await request.json()) as typeof savedBody;
      return HttpResponse.json({ ...project, identifierScopes: savedBody.identifierScopes });
    }),
    noLatestMatchRun,
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));

  await waitFor(() =>
    expect(screen.getByRole('textbox', { name: 'Scope 2' })).toHaveValue('gbif'),
  );
  await userEvent.click(screen.getByRole('button', { name: 'Remove scope 1' }));

  await userEvent.click(screen.getAllByRole('button', { name: /save/i })[0]);
  await waitFor(() => expect(savedBody.identifierScopes).toEqual([{ scope: 'gbif' }]));
});

test(
  'starts a COL match run, polls it to completion, renders the summary, and invalidates issues',
  async () => {
    let getCalls = 0;
    server.use(
      http.get('/api/projects/3', () => HttpResponse.json(projectWithMatchableScope)),
      noLatestMatchRun,
      noLatestExportRun,
      http.post('/api/projects/3/col-match', () =>
        HttpResponse.json(
          {
            id: 42,
            projectId: 3,
            status: 'RUNNING',
            total: 2,
            processed: 0,
            verified: 0,
            added: 0,
            updated: 0,
            unmatched: 0,
            startedAt: '2026-07-09T00:00:00Z',
            finishedAt: null,
            error: null,
          },
          { status: 202 },
        ),
      ),
      // First poll still RUNNING (1/2 processed); second poll DONE with the final tallies -- this is
      // what drives the button's react-query refetchInterval loop under test.
      http.get('/api/projects/3/col-match/42', () => {
        getCalls += 1;
        const done = getCalls > 1;
        return HttpResponse.json({
          id: 42,
          projectId: 3,
          status: done ? 'DONE' : 'RUNNING',
          total: 2,
          processed: done ? 2 : 1,
          verified: 0,
          added: done ? 1 : 0,
          updated: 0,
          unmatched: done ? 1 : 0,
          startedAt: '2026-07-09T00:00:00Z',
          finishedAt: done ? '2026-07-09T00:00:05Z' : null,
          error: null,
        });
      }),
    );

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    renderWithProviders(
      <Routes>
        <Route path="/projects/:projectId/metadata" element={<ProjectMetadataPage />} />
      </Routes>,
      { route: '/projects/3/metadata', queryClient },
    );

    // Wait for the project fetch to resolve (canEdit=true, owner) before the button is rendered --
    // same discipline as the other tests in this file (checking the prefilled Title value, not just
    // that the always-present form field exists).
    const title = await screen.findByLabelText('Title');
    await waitFor(() => expect(title).toHaveValue('Mammals'));

    await userEvent.click(screen.getByRole('button', { name: 'Match all identifiers' }));

    // RUNNING: a progress indicator over the mocked 1-of-2 poll response.
    await waitFor(() => expect(screen.getByText(/Matched 1 of 2/)).toBeInTheDocument());

    // DONE: the final per-outcome summary + the Issues-view pointer. Generous timeout: the mocked
    // RUNNING -> DONE transition above only surfaces after one COL_MATCH_POLL_MS refetchInterval
    // tick (1500ms), longer than @testing-library's default 1000ms waitFor timeout.
    await waitFor(() => expect(screen.getByText('added 1')).toBeInTheDocument(), { timeout: 5000 });
    expect(screen.getByText('unmatched 1')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Issues' })).toHaveAttribute(
      'href',
      '/projects/3/issues',
    );

    // The issues list/summary queries are invalidated once the run leaves RUNNING, so a user who
    // then opens the Issues view sees the fresh col_* flags without a manual reload.
    await waitFor(() =>
      expect(invalidateSpy).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['issues', 3] })),
    );
  },
  10000,
);

test('a project with no matchable identifier scope disables "Match all identifiers" and shows a hint', async () => {
  server.use(
    http.get('/api/projects/3', () =>
      HttpResponse.json({ ...project, identifierScopes: [{ scope: 'ipni', datasetKey: null }] }),
    ),
    noLatestMatchRun,
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));

  // No entry in identifierScopes has a non-blank datasetKey -- a run would be a no-op (total 0),
  // so the button stays disabled and a hint points the user at the fix (below, in the row editor).
  expect(screen.getByRole('button', { name: 'Match all identifiers' })).toBeDisabled();
  expect(
    screen.getByText('Configure an identifier scope with a dataset key below to enable matching.'),
  ).toBeInTheDocument();
});

test('a project with a matchable identifier scope enables "Match all identifiers"', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(projectWithMatchableScope)),
    noLatestMatchRun,
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));

  expect(screen.getByRole('button', { name: 'Match all identifiers' })).not.toBeDisabled();
  expect(
    screen.queryByText('Configure an identifier scope with a dataset key below to enable matching.'),
  ).not.toBeInTheDocument();
});

test('a DONE latest run renders its summary on mount without the user clicking', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(projectWithMatchableScope)),
    http.get('/api/projects/3/col-match/latest', () =>
      HttpResponse.json({
        id: 7,
        projectId: 3,
        status: 'DONE',
        total: 2,
        processed: 2,
        verified: 1,
        added: 1,
        updated: 0,
        unmatched: 0,
        startedAt: '2026-07-09T00:00:00Z',
        finishedAt: '2026-07-09T00:00:05Z',
        error: null,
      }),
    ),
    // The latest-run id (7) is seeded into matchRunId, which then drives the same poll query the
    // "starts a COL match run" test above exercises via POST -- GET .../col-match/7 must be mocked
    // for that query to resolve.
    http.get('/api/projects/3/col-match/7', () =>
      HttpResponse.json({
        id: 7,
        projectId: 3,
        status: 'DONE',
        total: 2,
        processed: 2,
        verified: 1,
        added: 1,
        updated: 0,
        unmatched: 0,
        startedAt: '2026-07-09T00:00:00Z',
        finishedAt: '2026-07-09T00:00:05Z',
        error: null,
      }),
    ),
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));

  // No click on "Match all identifiers" anywhere in this test -- the summary must appear purely
  // from the load-on-mount latest-run lookup seeding matchRunId.
  await waitFor(() => expect(screen.getByText('added 1')).toBeInTheDocument());
  expect(screen.getByText('verified 1')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Match all identifiers' })).not.toBeDisabled();
});

test('a RUNNING latest run disables the button and resumes the progress display on mount', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(projectWithMatchableScope)),
    http.get('/api/projects/3/col-match/latest', () =>
      HttpResponse.json({
        id: 9,
        projectId: 3,
        status: 'RUNNING',
        total: 4,
        processed: 1,
        verified: 0,
        added: 0,
        updated: 0,
        unmatched: 0,
        startedAt: '2026-07-09T00:00:00Z',
        finishedAt: null,
        error: null,
      }),
    ),
    http.get('/api/projects/3/col-match/9', () =>
      HttpResponse.json({
        id: 9,
        projectId: 3,
        status: 'RUNNING',
        total: 4,
        processed: 1,
        verified: 0,
        added: 0,
        updated: 0,
        unmatched: 0,
        startedAt: '2026-07-09T00:00:00Z',
        finishedAt: null,
        error: null,
      }),
    ),
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));

  await waitFor(() => expect(screen.getByText(/Matched 1 of 4/)).toBeInTheDocument());
  expect(screen.getByRole('button', { name: 'Match all identifiers' })).toBeDisabled();
});

test(
  'starting a match run while one is already in progress shows a friendly 409 notification',
  async () => {
    server.use(
      http.get('/api/projects/3', () => HttpResponse.json(projectWithMatchableScope)),
      noLatestMatchRun,
      noLatestExportRun,
      http.post('/api/projects/3/col-match', () =>
        HttpResponse.json(
          { error: 'a COL match run is already in progress for this project' },
          { status: 409 },
        ),
      ),
    );
    renderPage();
    const title = await screen.findByLabelText('Title');
    await waitFor(() => expect(title).toHaveValue('Mammals'));

    await userEvent.click(screen.getByRole('button', { name: 'Match all identifiers' }));

    // Generous timeout (like the poll-driven assertions elsewhere in this file): this file's form
    // now mounts the identifier-scopes row editor on every render, and jsdom under a full suite
    // run can occasionally take the notification mount past the default 1000ms waitFor budget.
    await waitFor(
      () =>
        expect(
          screen.getByText('a COL match run is already in progress for this project'),
        ).toBeInTheDocument(),
      { timeout: 5000 },
    );
  },
  10000,
);

test('viewer role sees a disabled Save button', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json({ ...project, role: 'viewer' })),
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  // Wait until the fetched role=viewer project has populated the form, so the
  // assertion reflects the loaded role (canEdit=false because viewer ∉ owner/editor),
  // not the pre-fetch `data === undefined` default.
  await waitFor(() => expect(title).toHaveValue('Mammals'));
  expect(screen.getAllByRole('button', { name: /save/i })[0]).toBeDisabled();
});

test(
  'starts an export, polls it to completion, and shows a working Download link',
  async () => {
    let getCalls = 0;
    server.use(
      http.get('/api/projects/3', () => HttpResponse.json(project)),
      noLatestMatchRun,
      noLatestExportRun,
      http.post('/api/projects/3/export', () =>
        HttpResponse.json(
          {
            id: 55,
            projectId: 3,
            status: 'RUNNING',
            fileName: null,
            fileSize: null,
            nameUsageCount: null,
            referenceCount: null,
            startedAt: '2026-07-09T00:00:00Z',
            finishedAt: null,
            error: null,
          },
          { status: 202 },
        ),
      ),
      // First poll still RUNNING (no file yet); second poll DONE with the file link -- this is what
      // drives the Export button's refetchInterval loop under test, same discipline as the
      // "starts a COL match run" test above.
      http.get('/api/projects/3/export/55', () => {
        getCalls += 1;
        const done = getCalls > 1;
        return HttpResponse.json({
          id: 55,
          projectId: 3,
          status: done ? 'DONE' : 'RUNNING',
          fileName: done ? 'mammals-coldp.zip' : null,
          fileSize: done ? 2048 : null,
          nameUsageCount: done ? 12 : null,
          referenceCount: done ? 3 : null,
          startedAt: '2026-07-09T00:00:00Z',
          finishedAt: done ? '2026-07-09T00:00:05Z' : null,
          error: null,
        });
      }),
    );

    renderPage();
    const title = await screen.findByLabelText('Title');
    await waitFor(() => expect(title).toHaveValue('Mammals'));

    await userEvent.click(screen.getByRole('button', { name: 'Export ColDP' }));

    // RUNNING: a simple status line (the export job has no total/processed tally to show progress).
    await waitFor(() => expect(screen.getByText('Exporting…')).toBeInTheDocument());

    // DONE: the Download link plus a small summary. Generous timeout: the mocked RUNNING -> DONE
    // transition above only surfaces after one EXPORT_POLL_MS refetchInterval tick (1500ms), longer
    // than @testing-library's default 1000ms waitFor timeout.
    await waitFor(
      () => expect(screen.getByRole('link', { name: 'Download' })).toBeInTheDocument(),
      { timeout: 5000 },
    );
    expect(screen.getByRole('link', { name: 'Download' })).toHaveAttribute(
      'href',
      '/api/projects/3/export/55/file',
    );
    expect(screen.getByText(/mammals-coldp\.zip/)).toBeInTheDocument();
    expect(screen.getByText('usages 12')).toBeInTheDocument();
    expect(screen.getByText('references 3')).toBeInTheDocument();
  },
  10000,
);

test('a DONE latest export renders its Download link on mount without the user clicking', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
    noLatestMatchRun,
    http.get('/api/projects/3/export/latest', () =>
      HttpResponse.json({
        id: 66,
        projectId: 3,
        status: 'DONE',
        fileName: 'mammals-coldp.zip',
        fileSize: 4096,
        nameUsageCount: 20,
        referenceCount: 5,
        startedAt: '2026-07-09T00:00:00Z',
        finishedAt: '2026-07-09T00:00:05Z',
        error: null,
      }),
    ),
    // The latest-run id (66) is seeded into exportRunId, which then drives the same poll query the
    // export test above exercises via POST -- GET .../export/66 must be mocked for that query to
    // resolve.
    http.get('/api/projects/3/export/66', () =>
      HttpResponse.json({
        id: 66,
        projectId: 3,
        status: 'DONE',
        fileName: 'mammals-coldp.zip',
        fileSize: 4096,
        nameUsageCount: 20,
        referenceCount: 5,
        startedAt: '2026-07-09T00:00:00Z',
        finishedAt: '2026-07-09T00:00:05Z',
        error: null,
      }),
    ),
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));

  // No click on "Export ColDP" anywhere in this test -- the Download link must appear purely from
  // the load-on-mount latest-run lookup seeding exportRunId.
  await waitFor(() => expect(screen.getByRole('link', { name: 'Download' })).toBeInTheDocument());
  expect(screen.getByRole('link', { name: 'Download' })).toHaveAttribute(
    'href',
    '/api/projects/3/export/66/file',
  );
});

test(
  'starting an export while one is already in progress shows a friendly 409 notification',
  async () => {
    server.use(
      http.get('/api/projects/3', () => HttpResponse.json(project)),
      noLatestMatchRun,
      noLatestExportRun,
      http.post('/api/projects/3/export', () =>
        HttpResponse.json(
          { error: 'an export is already in progress for this project' },
          { status: 409 },
        ),
      ),
    );
    renderPage();
    const title = await screen.findByLabelText('Title');
    await waitFor(() => expect(title).toHaveValue('Mammals'));

    await userEvent.click(screen.getByRole('button', { name: 'Export ColDP' }));

    // Generous timeout -- see the analogous "starting a match run..." test above for why.
    await waitFor(
      () =>
        expect(
          screen.getByText('an export is already in progress for this project'),
        ).toBeInTheDocument(),
      { timeout: 5000 },
    );
  },
  10000,
);

test('owner can toggle public and publish a release', async () => {
  // `isPublic`/`published` are mutated by the PUT/POST handlers below and read back by the GET
  // handlers, so the switch flip and the release-history refresh are driven by the same
  // request/response round trip the component makes, not a canned static fixture.
  let isPublic = false;
  let published = false;
  server.use(
    // A license is required before the Public toggle/Publish release action are enabled (B2) --
    // this fixture has one so the flow under test (toggle + publish) is actually exercisable; the
    // license-less gating itself is covered separately below.
    http.get('/api/projects/3', () =>
      HttpResponse.json({ ...project, public: isPublic, license: 'CC0-1.0' }),
    ),
    http.put('/api/projects/3/public', async ({ request }) => {
      const body = (await request.json()) as { public: boolean };
      isPublic = body.public;
      return new HttpResponse(null, { status: 200 });
    }),
    http.get('/api/projects/3/releases', () =>
      HttpResponse.json(
        published
          ? [
              {
                id: 1,
                projectId: 3,
                version: '1.0',
                notes: null,
                status: 'READY',
                nameUsageCount: 3,
                metrics: {},
                fileName: 'x.zip',
                fileSize: 10,
                error: null,
                createdAt: '2026-07-12T00:00:00Z',
              },
            ]
          : [],
      ),
    ),
    http.post('/api/projects/3/releases', async () => {
      published = true;
      return new HttpResponse(
        JSON.stringify({
          id: 1,
          projectId: 3,
          version: '1.0',
          notes: null,
          status: 'BUILDING',
          nameUsageCount: null,
          metrics: null,
          fileName: null,
          fileSize: null,
          error: null,
          createdAt: '2026-07-12T00:00:00Z',
        }),
        { status: 202, headers: { 'content-type': 'application/json' } },
      );
    }),
    noLatestMatchRun,
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));

  const publicSwitch = screen.getByRole('switch', { name: 'Public' });
  expect(publicSwitch).not.toBeChecked();
  await userEvent.click(publicSwitch);
  // The switch reflects `data.public`, which only flips once the PUT resolves and the project
  // query is invalidated/refetched -- asserting on the checked state (rather than a spy) proves
  // both the request happened AND the page picked up the result.
  await waitFor(() => expect(publicSwitch).toBeChecked());

  await userEvent.type(screen.getByLabelText('Version'), '1.0');
  await userEvent.click(screen.getByRole('button', { name: 'Publish release' }));

  await waitFor(() => expect(screen.getByText('READY')).toBeInTheDocument());
  expect(screen.getByText('1.0')).toBeInTheDocument();
});

test('a license-less project disables the Public toggle and Publish release button with a hint', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json({ ...project, license: null })),
    http.get('/api/projects/3/releases', () => HttpResponse.json([])),
    noLatestMatchRun,
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));

  expect(screen.getByRole('switch', { name: 'Public' })).toBeDisabled();
  expect(screen.getByText('Set a license first to make this project public.')).toBeInTheDocument();

  await userEvent.type(screen.getByLabelText('Version'), '1.0');
  expect(screen.getByRole('button', { name: 'Publish release' })).toBeDisabled();
  expect(screen.getByText('Set a license first to publish a release.')).toBeInTheDocument();
});
