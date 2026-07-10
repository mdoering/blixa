import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import TaxonDetail from './TaxonDetail';

const project = {
  id: 4, title: 'Mammals', alias: null, description: null, nomCode: null,
  license: null, geographicScope: null, taxonomicScope: null, role: 'owner',
};

function baseUsage(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 10,
    parentId: 1,
    status: 'ACCEPTED',
    namePhrase: null,
    extinct: null,
    environment: null,
    temporalRangeStart: null,
    temporalRangeEnd: null,
    scientificName: 'Panthera leo',
    authorship: 'Linnaeus, 1758',
    rank: 'species',
    uninomial: null,
    genus: 'Panthera',
    infragenericEpithet: null,
    specificEpithet: 'leo',
    infraspecificEpithet: null,
    cultivarEpithet: null,
    notho: null,
    combinationAuthorship: null,
    combinationExAuthorship: null,
    combinationAuthorshipYear: null,
    basionymAuthorship: null,
    basionymExAuthorship: null,
    basionymAuthorshipYear: null,
    sanctioningAuthor: null,
    nomStatus: null,
    publishedInReferenceId: null,
    publishedInYear: 1758,
    publishedInPage: null,
    publishedInPageLink: null,
    gender: null,
    etymology: null,
    nameType: 'SCIENTIFIC',
    parseState: 'COMPLETE',
    remarks: null,
    formattedName: 'Panthera leo Linnaeus, 1758',
    acceptedParentIds: [],
    synonymIds: [],
    version: 1,
    ...overrides,
  };
}

function mockCommon(usage = baseUsage(), role = 'owner') {
  server.use(
    http.get('/api/projects/4', () => HttpResponse.json({ ...project, role })),
    http.get('/api/projects/4/usages/10', () => HttpResponse.json(usage)),
    http.get('/api/projects/4/usages/10/synonyms', () => HttpResponse.json([])),
    http.get('/api/projects/4/usages/10/accepted', () => HttpResponse.json([])),
    http.get('/api/projects/4/usages/10/relations', () => HttpResponse.json([])),
    http.get('/api/projects/4/usages/10/type-material', () => HttpResponse.json([])),
    http.get('/api/projects/4/usages/10/vernaculars', () => HttpResponse.json([])),
    http.get('/api/projects/4/usages/10/distributions', () => HttpResponse.json([])),
    http.get('/api/projects/4/usages/10/media', () => HttpResponse.json([])),
    http.get('/api/projects/4/usages/10/estimates', () => HttpResponse.json([])),
    http.get('/api/projects/4/usages/10/properties', () => HttpResponse.json([])),
    http.get('/api/projects/4/issues', () => HttpResponse.json([])),
    // The Details tab's published-in EntitySelect loads this unconditionally on mount.
    http.get('/api/projects/4/references', () => HttpResponse.json([])),
  );
}

test('loads a usage and prefills the form fields', async () => {
  mockCommon();
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await waitFor(() =>
    expect(screen.getByLabelText('Scientific name')).toHaveValue('Panthera leo'),
  );
  expect(screen.getByLabelText('Authorship')).toHaveValue('Linnaeus, 1758');
  expect(screen.getByLabelText('Rank')).toHaveValue('species');
  expect(screen.getByLabelText('Published in year')).toHaveValue('1758');
});

test('editing authorship and saving PUTs the update with the loaded version', async () => {
  mockCommon();
  let putBody: Record<string, unknown> | undefined;
  server.use(
    http.put('/api/projects/4/usages/10', async ({ request }) => {
      putBody = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(baseUsage({ authorship: 'L., 1758', version: 2 }));
    }),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  const authorship = await screen.findByLabelText('Authorship');
  await waitFor(() => expect(authorship).toHaveValue('Linnaeus, 1758'));
  await userEvent.clear(authorship);
  await userEvent.type(authorship, 'L., 1758');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));

  await waitFor(() => expect(screen.getByText('Saved')).toBeInTheDocument());
  expect(putBody).toBeDefined();
  expect(putBody?.authorship).toBe('L., 1758');
  expect(putBody?.version).toBe(1);
  expect(putBody?.scientificName).toBe('Panthera leo');
});

test('the published-in reference picker and remarks field render, seeded from the usage', async () => {
  mockCommon(baseUsage({ publishedInReferenceId: 7, remarks: 'Needs review' }));
  server.use(
    http.get('/api/projects/4/references', () =>
      HttpResponse.json([
        { id: 7, citation: 'Mill. 1768', title: 'Abies alba desc', version: 0 },
      ]),
    ),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await waitFor(() =>
    expect(screen.getByLabelText('Scientific name')).toHaveValue('Panthera leo'),
  );
  // The reference select resolves the loaded reference's title as its label, not a bare '#7'.
  // getByRole('textbox', ...), not getByLabelText: Mantine's Select combobox keeps its (hidden)
  // options listbox in the DOM with the same aria-labelledby as the input, so getByLabelText
  // matches both and errors on ambiguity.
  await waitFor(() =>
    expect(screen.getByRole('textbox', { name: 'Published in reference' })).toHaveValue(
      'Abies alba desc',
    ),
  );
  expect(screen.getByLabelText('Remarks')).toHaveValue('Needs review');
});

test('editing remarks and saving PUTs the update with the new remarks', async () => {
  mockCommon();
  let putBody: Record<string, unknown> | undefined;
  server.use(
    http.put('/api/projects/4/usages/10', async ({ request }) => {
      putBody = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(baseUsage({ remarks: 'Reviewed', version: 2 }));
    }),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  const remarks = await screen.findByLabelText('Remarks');
  await waitFor(() => expect(screen.getByLabelText('Scientific name')).toHaveValue('Panthera leo'));
  await userEvent.type(remarks, 'Reviewed');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));

  // Asserting on putBody (rather than the "Saved" notification text, which the earlier
  // authorship-save test also produces and which Mantine's notification store doesn't clear
  // between tests) keeps this independent of notification ordering across the file.
  await waitFor(() => expect(putBody).toBeDefined());
  expect(putBody?.remarks).toBe('Reviewed');
  expect(putBody?.scientificName).toBe('Panthera leo');
});

test('a 409 conflict shows a notice and refetches the usage', async () => {
  mockCommon();
  let putCalls = 0;
  server.use(
    http.put('/api/projects/4/usages/10', () => {
      putCalls += 1;
      return new HttpResponse(JSON.stringify({ error: 'conflict: stale version' }), {
        status: 409,
      });
    }),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  const authorship = await screen.findByLabelText('Authorship');
  await waitFor(() => expect(authorship).toHaveValue('Linnaeus, 1758'));
  await userEvent.clear(authorship);
  await userEvent.type(authorship, 'someone else edited this');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));

  await screen.findByText('Changed by someone else — reloading');
  await waitFor(() => expect(putCalls).toBe(1));
  // The usage GET is refetched after the conflict, reseeding the form back to server state.
  await waitFor(() => expect(authorship).toHaveValue('Linnaeus, 1758'));
});

test('a viewer role sees a disabled Save button', async () => {
  mockCommon(baseUsage(), 'viewer');
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await waitFor(() =>
    expect(screen.getByLabelText('Scientific name')).toHaveValue('Panthera leo'),
  );
  expect(screen.getByRole('button', { name: /save/i })).toBeDisabled();
});

test('an accepted usage with two synonyms renders both', async () => {
  mockCommon();
  server.use(
    http.get('/api/projects/4/usages/10/synonyms', () =>
      HttpResponse.json([
        baseUsage({ id: 11, scientificName: 'Felis leo', authorship: 'Linnaeus, 1758' }),
        baseUsage({ id: 12, scientificName: 'Panthera leo persica', authorship: null }),
      ]),
    ),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  await userEvent.click(screen.getByRole('tab', { name: /synonyms/i }));
  await screen.findByText('Felis leo');
  expect(screen.getByText('Panthera leo persica')).toBeInTheDocument();
});

test('the Names tab lists a basionym relation with the joined related name', async () => {
  mockCommon();
  server.use(
    http.get('/api/projects/4/usages/10/relations', () =>
      HttpResponse.json([
        {
          id: 5,
          usageId: 10,
          relatedUsageId: 12,
          relatedName: 'Felis leo Linnaeus, 1758',
          type: 'basionym',
          referenceId: null,
          page: '42',
          remarks: null,
          version: 0,
        },
      ]),
    ),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  await userEvent.click(screen.getByRole('tab', { name: /names/i }));
  await screen.findByText('basionym');
  expect(screen.getByText('Felis leo Linnaeus, 1758')).toBeInTheDocument();
  expect(screen.getByText('42')).toBeInTheDocument();
});

test('the Types tab lists a holotype with its institution and occurrenceID', async () => {
  mockCommon();
  server.use(
    http.get('/api/projects/4/usages/10/type-material', () =>
      HttpResponse.json([
        {
          id: 7,
          usageId: 10,
          citation: 'BMNH 1901.1.1',
          status: 'holotype',
          institutionCode: 'BMNH',
          catalogNumber: '1901.1.1',
          occurrenceId: 'gbif:12345',
          locality: null,
          country: 'GB',
          collector: null,
          date: null,
          sex: null,
          referenceId: null,
          link: null,
          remarks: null,
          version: 0,
        },
      ]),
    ),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  await userEvent.click(screen.getByRole('tab', { name: /types/i }));
  await screen.findByText('holotype');
  // Citation column + Institution column ("BMNH 1901.1.1") both render this text.
  expect(screen.getAllByText('BMNH 1901.1.1').length).toBeGreaterThanOrEqual(1);
  expect(screen.getByText('GB')).toBeInTheDocument();
});

test('the Vernaculars tab (accepted only) lists a vernacular name', async () => {
  mockCommon();
  server.use(
    http.get('/api/projects/4/usages/10/vernaculars', () =>
      HttpResponse.json([
        {
          id: 3,
          usageId: 10,
          name: 'Lion',
          language: 'eng',
          country: null,
          sex: null,
          preferred: true,
          referenceId: null,
          remarks: null,
          version: 0,
        },
      ]),
    ),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  await userEvent.click(screen.getByRole('tab', { name: /vernaculars/i }));
  await screen.findByText('Lion');
  expect(screen.getByText('eng')).toBeInTheDocument();
});

test('a synonym usage hides the taxon-level tabs', async () => {
  mockCommon(baseUsage({ status: 'SYNONYM' }));
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  expect(screen.queryByRole('tab', { name: /vernaculars/i })).not.toBeInTheDocument();
  expect(screen.queryByRole('tab', { name: /distribution/i })).not.toBeInTheDocument();
  expect(screen.queryByRole('tab', { name: /estimates/i })).not.toBeInTheDocument();
  // Names + Types still apply to any usage.
  expect(screen.getByRole('tab', { name: /names/i })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: /types/i })).toBeInTheDocument();
});

test('a warning issue shows its badge and message', async () => {
  mockCommon();
  server.use(
    http.get('/api/projects/4/issues', () =>
      HttpResponse.json([
        {
          id: 100,
          entityType: 'name_usage',
          entityId: 10,
          rule: 'missing_published_in',
          severity: 'warning',
          message: 'Missing published-in reference',
          status: 'open',
        },
      ]),
    ),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  await userEvent.click(screen.getByRole('tab', { name: /issues/i }));
  await screen.findByText('Missing published-in reference');
  expect(screen.getByText('warning')).toBeInTheDocument();
});
