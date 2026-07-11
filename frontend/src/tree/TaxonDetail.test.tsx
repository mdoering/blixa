import { screen, waitFor, within } from '@testing-library/react';
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
    referenceId: null,
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

test('renders a per-scope identifier field from project.identifierScopes, prefilled from alternativeId, and saving folds the edit back in while preserving col:', async () => {
  mockCommon(baseUsage({ alternativeId: ['col:XYZ', 'ipni:123'] }));
  server.use(
    http.get('/api/projects/4', () => HttpResponse.json({ ...project, role: 'owner', identifierScopes: [{ scope: 'ipni' }] })),
  );
  let putBody: Record<string, unknown> | undefined;
  server.use(
    http.put('/api/projects/4/usages/10', async ({ request }) => {
      putBody = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(baseUsage({ alternativeId: ['col:XYZ', 'ipni:456'], version: 2 }));
    }),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  const ipni = await screen.findByLabelText('IPNI');
  await waitFor(() => expect(ipni).toHaveValue('123'));

  await userEvent.clear(ipni);
  await userEvent.type(ipni, '456');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));

  await waitFor(() => expect(putBody).toBeDefined());
  expect(putBody?.alternativeId).toEqual(['col:XYZ', 'ipni:456']);
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

test('the Relations tab lists a basionym relation with the joined related name', async () => {
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
  await userEvent.click(screen.getByRole('tab', { name: /relations/i }));
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

test('the References tab lists the usage\'s references, a citation and a webpage title with a web badge/link', async () => {
  mockCommon(baseUsage({ referenceId: [7, 9] }));
  server.use(
    http.get('/api/projects/4/references', () =>
      HttpResponse.json([
        { id: 7, citation: 'Mill. 1768, Gardeners Dictionary', title: null, type: null, link: null, version: 0 },
        { id: 9, citation: null, title: 'Example Page', type: 'webpage', link: 'https://example.org/page', version: 0 },
      ]),
    ),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  await userEvent.click(screen.getByRole('tab', { name: /references/i }));
  // getAllByText, not getByText: the "Add existing reference" EntitySelect's hidden options
  // listbox renders the same reference labels elsewhere in the DOM (see the published-in
  // reference picker test's note on this same Mantine Select behavior).
  await waitFor(() =>
    expect(screen.getAllByText('Mill. 1768, Gardeners Dictionary').length).toBeGreaterThanOrEqual(1),
  );
  expect(screen.getAllByText('Example Page').length).toBeGreaterThanOrEqual(1);
  expect(screen.getByText('web')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /Example Page/ })).toHaveAttribute(
    'href',
    'https://example.org/page',
  );
});

test('adding an existing reference on the References tab PUTs the updated id list', async () => {
  mockCommon(baseUsage({ referenceId: [7], version: 3 }));
  server.use(
    http.get('/api/projects/4/references', () =>
      HttpResponse.json([
        { id: 7, citation: 'Mill. 1768', title: null, type: null, link: null, version: 0 },
        { id: 11, citation: null, title: 'New Reference', type: null, link: null, version: 0 },
      ]),
    ),
  );
  let putBody: Record<string, unknown> | undefined;
  server.use(
    http.put('/api/projects/4/usages/10/references', async ({ request }) => {
      putBody = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(baseUsage({ referenceId: [7, 11], version: 4 }));
    }),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  await userEvent.click(screen.getByRole('tab', { name: /references/i }));
  await waitFor(() => expect(screen.getAllByText('Mill. 1768').length).toBeGreaterThanOrEqual(1));

  const picker = await screen.findByRole('textbox', { name: 'Add existing reference' });
  await userEvent.click(picker);
  await userEvent.click(await screen.findByText('New Reference'));
  // Two "Add" buttons on this tab (existing-reference / web-URL) -- the first is this one's.
  await userEvent.click(screen.getAllByRole('button', { name: 'Add' })[0]);

  await waitFor(() => expect(putBody).toBeDefined());
  expect(putBody?.referenceIds).toEqual([7, 11]);
  expect(putBody?.version).toBe(3);
});

test('adding a web URL on the References tab POSTs to web-reference', async () => {
  mockCommon(baseUsage({ referenceId: [] }));
  let postBody: Record<string, unknown> | undefined;
  let posted = false;
  server.use(
    http.post('/api/projects/4/usages/10/web-reference', async ({ request }) => {
      postBody = (await request.json()) as Record<string, unknown>;
      posted = true;
      return HttpResponse.json(baseUsage({ referenceId: [20], version: 2 }));
    }),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  await userEvent.click(screen.getByRole('tab', { name: /references/i }));

  const urlInput = await screen.findByLabelText('Add web URL');
  await userEvent.type(urlInput, 'https://example.org/new-page');
  // Two "Add" buttons on this tab (existing-reference / web-URL) -- the second is this one's.
  await userEvent.click(screen.getAllByRole('button', { name: 'Add' })[1]);

  await waitFor(() => expect(posted).toBe(true));
  expect(postBody?.url).toBe('https://example.org/new-page');
});

test('adding a web URL re-fetches the reference list so the new row shows its title, not a bare id', async () => {
  mockCommon(baseUsage({ referenceId: [] }));
  let referencesGetCalls = 0;
  server.use(
    http.get('/api/projects/4/references', () => {
      referencesGetCalls += 1;
      // Only the newly-created reference (id 20) shows up after the web-reference POST -- the
      // initial (pre-add) list is empty, matching referenceId: [] above.
      return HttpResponse.json(
        referencesGetCalls === 1
          ? []
          : [{ id: 20, citation: null, title: 'Example Page', type: 'webpage', link: 'https://example.org/new-page', version: 0 }],
      );
    }),
    http.post('/api/projects/4/usages/10/web-reference', () =>
      HttpResponse.json(baseUsage({ referenceId: [20], version: 2 })),
    ),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  await userEvent.click(screen.getByRole('tab', { name: /references/i }));

  const urlInput = await screen.findByLabelText('Add web URL');
  await userEvent.type(urlInput, 'https://example.org/new-page');
  await userEvent.click(screen.getAllByRole('button', { name: 'Add' })[1]);

  // The row resolves to the webpage's title (and the "web" badge), not the '#20' id fallback --
  // this only happens if adding a web reference also invalidates/refetches ['references', pid],
  // not just ['usage', pid, usageId].
  await screen.findByText('Example Page');
  expect(screen.queryByText('#20')).not.toBeInTheDocument();
});

test('removing a reference on the References tab PUTs the id list without it', async () => {
  mockCommon(baseUsage({ referenceId: [7, 9], version: 5 }));
  server.use(
    http.get('/api/projects/4/references', () =>
      HttpResponse.json([
        { id: 7, citation: 'Mill. 1768', title: null, type: null, link: null, version: 0 },
        { id: 9, citation: null, title: 'Example Page', type: 'webpage', link: 'https://example.org/page', version: 0 },
      ]),
    ),
  );
  let putBody: Record<string, unknown> | undefined;
  server.use(
    http.put('/api/projects/4/usages/10/references', async ({ request }) => {
      putBody = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(baseUsage({ referenceId: [9], version: 6 }));
    }),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  await userEvent.click(screen.getByRole('tab', { name: /references/i }));
  await waitFor(() => expect(screen.getAllByText('Mill. 1768').length).toBeGreaterThanOrEqual(1));

  await userEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]);
  const dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));

  await waitFor(() => expect(putBody).toBeDefined());
  expect(putBody?.referenceIds).toEqual([9]);
  expect(putBody?.version).toBe(5);
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
  // Relations + Types still apply to any usage.
  expect(screen.getByRole('tab', { name: /relations/i })).toBeInTheDocument();
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
