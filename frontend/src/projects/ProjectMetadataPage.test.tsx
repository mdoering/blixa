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
  gbifOccurrenceLayer: true,
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
  await userEvent.click(screen.getByRole('button', { name: /save/i }));
  await waitFor(() => expect(screen.getByText('Saved')).toBeInTheDocument());
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
  await userEvent.click(screen.getByRole('button', { name: /save/i }));
  // Assert on the captured request body rather than the "Saved" notification text: the
  // notifications store is global and the prior test in this file may leave a same-text
  // notification mounted, making `getByText('Saved')` ambiguous.
  await waitFor(() => expect(savedBody.gbifOccurrenceLayer).toBe(false));
});

test('identifier scopes: prefills the configured scopes and saves an added custom scope', async () => {
  let savedBody: { identifierScopes?: string[] } = {};
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json({ ...project, identifierScopes: ['ipni'] })),
    http.put('/api/projects/3/metadata', async ({ request }) => {
      savedBody = (await request.json()) as { identifierScopes?: string[] };
      return HttpResponse.json({ ...project, identifierScopes: savedBody.identifierScopes });
    }),
    noLatestMatchRun,
    noLatestExportRun,
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));

  // Prefilled from the loaded project as a chip/pill.
  expect(screen.getByText('ipni')).toBeInTheDocument();

  // Free custom entries are allowed on top of the seeded vocab (mocked in test/server.ts as
  // ['col', 'gbif', 'ipni', 'tsn']) -- 'worms' isn't in that list. getByRole('textbox', ...), not
  // getByLabelText: Mantine's TagsInput combobox keeps its (hidden) options listbox in the DOM
  // with the same aria-labelledby as the input, so getByLabelText matches both and errors on
  // ambiguity (same discipline as TaxonDetail.test.tsx's reference picker).
  const scopesInput = screen.getByRole('textbox', { name: 'Identifier scopes (form fields)' });
  await userEvent.type(scopesInput, 'worms{Enter}');
  expect(screen.getByText('worms')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: /save/i }));
  await waitFor(() => expect(savedBody.identifierScopes).toEqual(['ipni', 'worms']));
});

test(
  'starts a COL match run, polls it to completion, renders the summary, and invalidates issues',
  async () => {
    let getCalls = 0;
    server.use(
      http.get('/api/projects/3', () => HttpResponse.json(project)),
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

    await userEvent.click(screen.getByRole('button', { name: 'Match all to COL' }));

    // RUNNING: a progress indicator over the mocked 1-of-2 poll response.
    await waitFor(() => expect(screen.getByText(/Matching usage 1 of 2/)).toBeInTheDocument());

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

test('a DONE latest run renders its summary on mount without the user clicking', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
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

  // No click on "Match all to COL" anywhere in this test -- the summary must appear purely from the
  // load-on-mount latest-run lookup seeding matchRunId.
  await waitFor(() => expect(screen.getByText('added 1')).toBeInTheDocument());
  expect(screen.getByText('verified 1')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Match all to COL' })).not.toBeDisabled();
});

test('a RUNNING latest run disables the button and resumes the progress display on mount', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
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

  await waitFor(() => expect(screen.getByText(/Matching usage 1 of 4/)).toBeInTheDocument());
  expect(screen.getByRole('button', { name: 'Match all to COL' })).toBeDisabled();
});

test('starting a match run while one is already in progress shows a friendly 409 notification', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
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

  await userEvent.click(screen.getByRole('button', { name: 'Match all to COL' }));

  await waitFor(() =>
    expect(
      screen.getByText('a COL match run is already in progress for this project'),
    ).toBeInTheDocument(),
  );
});

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
  expect(screen.getByRole('button', { name: /save/i })).toBeDisabled();
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

test('starting an export while one is already in progress shows a friendly 409 notification', async () => {
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

  await waitFor(() =>
    expect(screen.getByText('an export is already in progress for this project')).toBeInTheDocument(),
  );
});
