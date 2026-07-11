import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import MergeModal from './MergeModal';

const targetProject = {
  id: 5, title: 'Target Proj', alias: null, description: null, nomCode: null,
  license: null, geographicScope: null, taxonomicScope: null, role: 'owner',
  gbifOccurrenceLayer: true, identifierScopes: null,
};
const sourceProject = {
  id: 7, title: 'Source Proj', alias: null, description: null, nomCode: null,
  license: null, geographicScope: null, taxonomicScope: null, role: 'owner',
  gbifOccurrenceLayer: true, identifierScopes: null,
};

// A mixed plan: some MATCHED/POSSIBLE_* candidates alongside NEW ones -- not a full import, so the
// transaction Switch should default ON, and possibleHomonym+possibleFuzzy+possible (2+1+1=4) > 0
// should surface the unreviewed-possibles warning.
const mixedMetrics = {
  names: { new: 10, matched: 5, possibleHomonym: 2, possibleFuzzy: 1 },
  references: { new: 4, matched: 3, possible: 1 },
  newAccepted: 8,
  newSynonyms: 2,
  unanchored: 0,
};

// An all-NEW plan (matched==0 && possible*==0 for both halves) -- MergeApplyService.isFullImport's
// frontend mirror should lock the transaction Switch off with the "full import" hint.
const fullImportMetrics = {
  names: { new: 20, matched: 0, possibleHomonym: 0, possibleFuzzy: 0 },
  references: { new: 5, matched: 0, possible: 0 },
  newAccepted: 18,
  newSynonyms: 2,
  unanchored: 0,
};

function baseRun(overrides: Record<string, unknown>) {
  return {
    id: 100,
    sourceProjectId: 7,
    targetProjectId: 5,
    status: 'RUNNING',
    mode: null,
    transactional: null,
    metrics: null,
    issues: null,
    startedAt: '2026-07-11T00:00:00Z',
    plannedAt: null,
    finishedAt: null,
    error: null,
    ...overrides,
  };
}

// Shared default: no merge run has ever been started for this target -- individual tests override
// with server.use(...) (MSW's last-registered handler for a given route wins) when they care about
// resuming a latest run instead.
const noLatestMerge = http.get('/api/projects/5/merge/latest', () => new HttpResponse(null, { status: 204 }));
const projectsList = http.get('/api/projects', () => HttpResponse.json([targetProject, sourceProject]));

// Mantine's Combobox-family widgets (Select included) also expose their (hidden) options listbox
// via aria-labelledby pointing at the field's label, so getByRole('textbox', ...) rather than
// getByLabelText is used to pin the match to the actual input -- same discipline as
// ImportProjectModal.test.tsx's Identifier-scope Autocomplete.
async function pickSourceAndStart() {
  await userEvent.click(screen.getByRole('textbox', { name: 'Source project' }));
  await userEvent.click(await screen.findByRole('option', { name: 'Source Proj' }));
  await userEvent.click(screen.getByRole('button', { name: 'Start merge' }));
}

test('the source Select excludes the target project itself', async () => {
  server.use(projectsList, noLatestMerge);
  renderWithProviders(<MergeModal opened onClose={() => {}} targetId={5} />);

  await userEvent.click(screen.getByRole('textbox', { name: 'Source project' }));
  expect(await screen.findByRole('option', { name: 'Source Proj' })).toBeInTheDocument();
  expect(screen.queryByRole('option', { name: 'Target Proj' })).not.toBeInTheDocument();
});

test(
  'starts a merge, polls RUNNING -> PLANNED, renders impact metrics, keeps the transaction Switch ' +
    'ON for a mixed plan, and shows the unreviewed-possibles warning',
  async () => {
    let getCalls = 0;
    let postedSource: string | null = null;
    server.use(
      projectsList,
      noLatestMerge,
      http.post('/api/projects/5/merge', ({ request }) => {
        postedSource = new URL(request.url).searchParams.get('source');
        return HttpResponse.json(baseRun({ status: 'RUNNING' }), { status: 202 });
      }),
      http.get('/api/projects/5/merge/100', () => {
        getCalls += 1;
        const planned = getCalls > 1;
        return HttpResponse.json(
          baseRun({
            status: planned ? 'PLANNED' : 'RUNNING',
            metrics: planned ? mixedMetrics : null,
            plannedAt: planned ? '2026-07-11T00:00:05Z' : null,
          }),
        );
      }),
    );

    renderWithProviders(<MergeModal opened onClose={() => {}} targetId={5} />);
    await pickSourceAndStart();

    await waitFor(() => expect(postedSource).toBe('7'));
    expect(screen.getByText('Computing merge plan…')).toBeInTheDocument();

    // PLANNED: metrics render. Generous timeout -- the mocked RUNNING -> PLANNED transition only
    // surfaces after one MERGE_POLL_MS refetchInterval tick (1500ms), longer than
    // @testing-library's default 1000ms waitFor timeout (same discipline as the col-match/export
    // poll tests in ProjectMetadataPage.test.tsx).
    await waitFor(() => expect(screen.getByText('new 10')).toBeInTheDocument(), { timeout: 5000 });
    expect(screen.getByText('matched 5')).toBeInTheDocument();
    expect(screen.getByText('possible homonym 2')).toBeInTheDocument();
    expect(screen.getByText('possible fuzzy 1')).toBeInTheDocument();
    expect(screen.getByText('new 4')).toBeInTheDocument();
    expect(screen.getByText('matched 3')).toBeInTheDocument();
    expect(screen.getByText('possible 1')).toBeInTheDocument();
    expect(screen.getByText('new accepted 8')).toBeInTheDocument();
    expect(screen.getByText('new synonyms 2')).toBeInTheDocument();
    expect(screen.getByText('unanchored 0')).toBeInTheDocument();

    // Mixed plan (not full-import, not over the large-plan threshold) -- transaction stays on by
    // default, and neither auto-off hint renders.
    expect(screen.getByLabelText('Run in one transaction')).toBeChecked();
    expect(screen.queryByText('full import — no transaction needed')).not.toBeInTheDocument();
    expect(
      screen.queryByText(/large plan: applying without a single transaction/),
    ).not.toBeInTheDocument();

    // FILL_GAPS (never overwrites an existing curated value) is the safe default mode, not the
    // destructive OVERWRITE -- a curator applying without touching the control shouldn't clobber.
    expect(screen.getByRole('radio', { name: 'Fill gaps' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Overwrite' })).not.toBeChecked();

    // 2 possibleHomonym + 1 possibleFuzzy + 1 possible = 4 unreviewed possible matches.
    expect(
      screen.getByText(/4 possible matches are unreviewed — they will be added as NEW/),
    ).toBeInTheDocument();
  },
  10000,
);

// A single possibleHomonym (and nothing else possible) -- the unreviewed-possibles warning's copy
// must singularize both the noun ("match" not "matches") and the verb ("is" not "are").
const onePossibleMetrics = {
  names: { new: 3, matched: 2, possibleHomonym: 1, possibleFuzzy: 0 },
  references: { new: 1, matched: 1, possible: 0 },
  newAccepted: 3,
  newSynonyms: 0,
  unanchored: 0,
};

test('the unreviewed-possibles warning singularizes its copy for exactly one possible match', async () => {
  server.use(
    projectsList,
    noLatestMerge,
    http.post('/api/projects/5/merge', () =>
      HttpResponse.json(baseRun({ status: 'RUNNING' }), { status: 202 }),
    ),
    http.get('/api/projects/5/merge/100', () =>
      HttpResponse.json(
        baseRun({ status: 'PLANNED', metrics: onePossibleMetrics, plannedAt: '2026-07-11T00:00:05Z' }),
      ),
    ),
  );

  renderWithProviders(<MergeModal opened onClose={() => {}} targetId={5} />);
  await pickSourceAndStart();

  await waitFor(() =>
    expect(
      screen.getByText(/1 possible match is unreviewed — they will be added as NEW/),
    ).toBeInTheDocument(),
  );
});

test('the transaction Switch defaults OFF (locked) with the full-import hint for an all-NEW plan', async () => {
  server.use(
    projectsList,
    noLatestMerge,
    http.post('/api/projects/5/merge', () =>
      HttpResponse.json(baseRun({ status: 'RUNNING' }), { status: 202 }),
    ),
    http.get('/api/projects/5/merge/100', () =>
      HttpResponse.json(
        baseRun({ status: 'PLANNED', metrics: fullImportMetrics, plannedAt: '2026-07-11T00:00:05Z' }),
      ),
    ),
  );

  renderWithProviders(<MergeModal opened onClose={() => {}} targetId={5} />);
  await pickSourceAndStart();

  await waitFor(() => expect(screen.getByText('new 20')).toBeInTheDocument());
  const txSwitch = screen.getByLabelText('Run in one transaction');
  expect(txSwitch).not.toBeChecked();
  expect(txSwitch).toBeDisabled();
  expect(screen.getByText('full import — no transaction needed')).toBeInTheDocument();

  // A fully-NEW plan has no MATCHED/POSSIBLE_* candidates at all -- no unreviewed-possibles warning.
  expect(screen.queryByText(/unreviewed/)).not.toBeInTheDocument();
});

test('applies with a chosen mode, polls APPLYING -> DONE, and shows the summary + target link', async () => {
  let applyBody: { mode?: string; transactional?: boolean } = {};
  let getCallsAfterApply = 0;
  server.use(
    projectsList,
    noLatestMerge,
    http.post('/api/projects/5/merge', () =>
      HttpResponse.json(baseRun({ status: 'RUNNING' }), { status: 202 }),
    ),
    http.get('/api/projects/5/merge/100', () =>
      HttpResponse.json(
        baseRun({ status: 'PLANNED', metrics: mixedMetrics, plannedAt: '2026-07-11T00:00:05Z' }),
      ),
    ),
    http.post('/api/projects/5/merge/100/apply', async ({ request }) => {
      applyBody = (await request.json()) as typeof applyBody;
      return HttpResponse.json(
        baseRun({
          status: 'APPLYING',
          mode: applyBody.mode,
          transactional: applyBody.transactional,
          metrics: mixedMetrics,
          plannedAt: '2026-07-11T00:00:05Z',
        }),
        { status: 202 },
      );
    }),
  );

  renderWithProviders(<MergeModal opened onClose={() => {}} targetId={5} />);
  await pickSourceAndStart();
  await waitFor(() => expect(screen.getByText('new 10')).toBeInTheDocument(), { timeout: 5000 });

  await userEvent.click(screen.getByRole('radio', { name: 'Fill gaps' }));
  await userEvent.click(screen.getByRole('button', { name: 'Apply merge' }));

  await waitFor(() => expect(applyBody.mode).toBe('FILL_GAPS'));
  expect(applyBody.transactional).toBe(true);

  // The apply POST's response (APPLYING) is what seeds the poll query's cache -- the "Applying…"
  // indicator should appear without a further GET.
  expect(await screen.findByText('Applying…')).toBeInTheDocument();

  // Re-register the GET handler so the resumed poll (now enabled by the APPLYING status) reaches
  // DONE with an issue.
  server.use(
    http.get('/api/projects/5/merge/100', () => {
      getCallsAfterApply += 1;
      const done = getCallsAfterApply > 1;
      return HttpResponse.json(
        baseRun({
          status: done ? 'DONE' : 'APPLYING',
          mode: 'FILL_GAPS',
          transactional: true,
          metrics: mixedMetrics,
          plannedAt: '2026-07-11T00:00:05Z',
          finishedAt: done ? '2026-07-11T00:00:10Z' : null,
          issues: done ? [{ entity: 'name', sourceId: 'n-1', message: 'unanchored parent' }] : null,
        }),
      );
    }),
  );

  await waitFor(() => expect(screen.getByText('Merge complete.')).toBeInTheDocument(), {
    timeout: 5000,
  });
  expect(screen.getByText('1 issue')).toBeInTheDocument();
  const link = screen.getByRole('link', { name: 'Open target project' });
  expect(link).toHaveAttribute('href', '/projects/5');
});

test('opening the modal resumes an already-PLANNED run from getLatestMerge, skipping the source picker', async () => {
  server.use(
    projectsList,
    http.get('/api/projects/5/merge/latest', () =>
      HttpResponse.json(
        baseRun({ status: 'PLANNED', metrics: mixedMetrics, plannedAt: '2026-07-11T00:00:05Z' }),
      ),
    ),
    http.get('/api/projects/5/merge/100', () =>
      HttpResponse.json(
        baseRun({ status: 'PLANNED', metrics: mixedMetrics, plannedAt: '2026-07-11T00:00:05Z' }),
      ),
    ),
  );

  // No click on "Start merge" anywhere in this test -- the metrics panel must appear purely from
  // the load-on-open latest-run lookup seeding runId, same discipline as ProjectMetadataPage's "a
  // DONE latest run renders its summary on mount" test.
  renderWithProviders(<MergeModal opened onClose={() => {}} targetId={5} />);

  await waitFor(() => expect(screen.getByText('new 10')).toBeInTheDocument());
  expect(screen.queryByRole('textbox', { name: 'Source project' })).not.toBeInTheDocument();
});

test('a FAILED run renders the error alert', async () => {
  server.use(
    projectsList,
    noLatestMerge,
    http.post('/api/projects/5/merge', () =>
      HttpResponse.json(baseRun({ status: 'RUNNING' }), { status: 202 }),
    ),
    http.get('/api/projects/5/merge/100', () =>
      HttpResponse.json(baseRun({ status: 'FAILED', error: 'source project has no name usages' })),
    ),
  );

  renderWithProviders(<MergeModal opened onClose={() => {}} targetId={5} />);
  await pickSourceAndStart();

  expect(await screen.findByText('Merge failed')).toBeInTheDocument();
  expect(screen.getByText('source project has no name usages')).toBeInTheDocument();
});

test('"Review mapping" toggles the Names/References mapping tables (Task 10) into view', async () => {
  server.use(
    projectsList,
    noLatestMerge,
    http.post('/api/projects/5/merge', () =>
      HttpResponse.json(baseRun({ status: 'RUNNING' }), { status: 202 }),
    ),
    http.get('/api/projects/5/merge/100', () =>
      HttpResponse.json(
        baseRun({ status: 'PLANNED', metrics: mixedMetrics, plannedAt: '2026-07-11T00:00:05Z' }),
      ),
    ),
    http.get('/api/projects/5/merge/100/mapping', () => HttpResponse.json([])),
  );

  renderWithProviders(<MergeModal opened onClose={() => {}} targetId={5} />);
  await pickSourceAndStart();
  await waitFor(() => expect(screen.getByText('new 10')).toBeInTheDocument(), { timeout: 5000 });

  expect(screen.queryByRole('tab', { name: 'Names' })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Review mapping' }));
  expect(await screen.findByRole('tab', { name: 'Names' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'References' })).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Hide mapping' }));
  expect(screen.queryByRole('tab', { name: 'Names' })).not.toBeInTheDocument();
});

// Fix for Task 10 review: the brief requires "rejecting a MATCHED name posts an override AND the
// metrics update" -- MergeMappingTables.test.tsx only ever asserted the PUT payload, never that the
// Impact panel (which lives in this component, not MergeMappingTables) picks up the PUT response's
// recomputed metrics. This exercises the full save -> setQueryData(['mergeRun', ...]) wiring: if
// MergeMappingTables.tsx's saveMut.onSuccess regressed back to invalidateQueries (an extra GET that
// this test's handler doesn't special-case, so it would keep serving the pre-save mixedMetrics) or
// dropped the cache write entirely, the "matched 4"/"new 11" assertions below would fail.
test('rejecting a MATCHED name in the mapping table refreshes the Impact metrics from the override response', async () => {
  const matchedNameRow = {
    sourceId: 'n1',
    category: 'MATCHED',
    targetId: 't1',
    score: 1.0,
    sourceLabel: 'Aus bus Linnaeus, 1758 (species)',
    targetLabel: 'Aus bus (L.) (species)',
  };
  // matched -1, new +1 vs. mixedMetrics.names -- a different shape than the pre-save metrics so a
  // stale/unrefreshed panel is unambiguously distinguishable from a refreshed one.
  const postOverrideMetrics = {
    ...mixedMetrics,
    names: { ...mixedMetrics.names, matched: mixedMetrics.names.matched - 1, new: mixedMetrics.names.new + 1 },
  };

  server.use(
    projectsList,
    noLatestMerge,
    http.post('/api/projects/5/merge', () =>
      HttpResponse.json(baseRun({ status: 'RUNNING' }), { status: 202 }),
    ),
    http.get('/api/projects/5/merge/100', () =>
      HttpResponse.json(
        baseRun({ status: 'PLANNED', metrics: mixedMetrics, plannedAt: '2026-07-11T00:00:05Z' }),
      ),
    ),
    http.get('/api/projects/5/merge/100/mapping', ({ request }) => {
      const entity = new URL(request.url).searchParams.get('entity');
      return HttpResponse.json(entity === 'name' ? [matchedNameRow] : []);
    }),
    http.put('/api/projects/5/merge/100/overrides', () =>
      HttpResponse.json(
        baseRun({ status: 'PLANNED', metrics: postOverrideMetrics, plannedAt: '2026-07-11T00:00:05Z' }),
      ),
    ),
  );

  renderWithProviders(<MergeModal opened onClose={() => {}} targetId={5} />);
  await pickSourceAndStart();
  await waitFor(() => expect(screen.getByText('matched 5')).toBeInTheDocument(), { timeout: 5000 });

  await userEvent.click(screen.getByRole('button', { name: 'Review mapping' }));
  const matchedRow = (await screen.findByText('Aus bus Linnaeus, 1758 (species)')).closest('tr');
  expect(matchedRow).not.toBeNull();
  await userEvent.click(within(matchedRow as HTMLElement).getByRole('button', { name: 'Reject' }));
  await userEvent.click(screen.getByRole('button', { name: 'Save overrides' }));

  await waitFor(() => expect(screen.getByText('matched 4')).toBeInTheDocument());
  expect(screen.getByText('new 11')).toBeInTheDocument();
});
