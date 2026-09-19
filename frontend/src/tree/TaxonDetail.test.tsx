import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { notifications } from '@mantine/notifications';
import { afterEach, expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';

// Mantine's notification store is a module-level singleton, not reset when the Notifications
// provider unmounts between tests -- stale toasts accumulate and, once past the default limit, new
// ones queue instead of rendering. Clear it after each test so notification assertions are reliable.
afterEach(() => notifications.clean());
import { server, http, HttpResponse } from '../test/server';
import TaxonDetail from './TaxonDetail';

function fakeLock(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 1,
    entityType: 'name_usage',
    entityId: 10,
    userId: 99,
    username: 'alice',
    acquiredAt: '2026-07-12T00:00:00Z',
    expiresAt: '2026-07-12T00:05:00Z',
    heldByMe: false,
    discussionId: null,
    discussionTitle: null,
    ...overrides,
  };
}

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
    // The nomenclatural-genus picker searches the project's genera (rank=genus).
    http.get('/api/projects/4/usages', () => HttpResponse.json({ items: [], total: 0 })),
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
    // The Rank / Nomenclatural-status dropdowns load their enum vocabularies on mount.
    http.get('/api/coldp/vocab', () =>
      HttpResponse.json({
        ranks: ['genus', 'species', 'subspecies'],
        nomStatus: [
          { value: 'ESTABLISHED', botanical: 'nomen validum', zoological: 'available' },
          { value: 'REJECTED', botanical: 'nomen rejiciendum', zoological: 'rejected' },
        ],
        gender: ['MASCULINE', 'FEMININE'],
        environment: ['MARINE', 'TERRESTRIAL'],
      }),
    ),
  );
}

test('loads a usage and prefills the form fields', async () => {
  mockCommon();
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await waitFor(() =>
    expect(screen.getByLabelText('Scientific name')).toHaveValue('Panthera leo'),
  );
  expect(screen.getByLabelText('Authorship')).toHaveValue('Linnaeus, 1758');
  // Rank is a searchable Select combobox now; its input carries the selected value's label.
  // getByRole('textbox'), not getByLabelText — the combobox's hidden listbox shares the input's
  // accessible name (see the reference-select assertion below), so getByLabelText is ambiguous.
  await waitFor(() =>
    expect(screen.getByRole('textbox', { name: 'Rank' })).toHaveValue('species'),
  );
  expect(screen.getByLabelText('Published in year')).toHaveValue('1758');
});

test('shows the full name with authorship as a heading above the form', async () => {
  mockCommon();
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  expect(
    await screen.findByRole('heading', { name: 'Panthera leo Linnaeus, 1758' }),
  ).toBeInTheDocument();
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

test('view mode renders the alternativeId entries as linked CurieId chips, with an edit pencil for an editor', async () => {
  mockCommon(baseUsage({ alternativeId: ['col:XYZ', 'ipni:123'] }));
  server.use(
    http.get('/api/projects/4', () => HttpResponse.json({ ...project, role: 'owner', identifierScopes: [{ scope: 'ipni' }] })),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  expect(await screen.findByText('col:XYZ')).toBeInTheDocument();
  expect(screen.getByText('ipni:123')).toBeInTheDocument();
  // The per-scope edit form is behind the toggle -- not shown until the pencil is clicked.
  expect(screen.queryByLabelText('IPNI')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Edit identifiers' })).toBeInTheDocument();
});

test('a viewer sees the identifier chips but no edit pencil', async () => {
  mockCommon(baseUsage({ alternativeId: ['col:XYZ', 'ipni:123'] }), 'viewer');
  server.use(
    http.get('/api/projects/4', () => HttpResponse.json({ ...project, role: 'viewer', identifierScopes: [{ scope: 'ipni' }] })),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  // Wait for the base form to finish loading before asserting on the identifiers chips -- the
  // usage/project/idScopes queries all settle together, and asserting on the chips alone can
  // otherwise race the default findBy* timeout.
  await screen.findByLabelText('Scientific name');
  expect(await screen.findByText('col:XYZ')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Edit identifiers' })).not.toBeInTheDocument();
});

test('clicking the edit pencil reveals a per-scope identifier field from project.identifierScopes, prefilled from alternativeId, and saving folds the edit back in while preserving col:', async () => {
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

  await screen.findByText('ipni:123');
  await userEvent.click(screen.getByRole('button', { name: 'Edit identifiers' }));

  const ipni = await screen.findByLabelText('IPNI');
  await waitFor(() => expect(ipni).toHaveValue('123'));

  await userEvent.clear(ipni);
  await userEvent.type(ipni, '456');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));

  await waitFor(() => expect(putBody).toBeDefined());
  expect(putBody?.alternativeId).toEqual(['col:XYZ', 'ipni:456']);
  // The identifiers section collapses back to the read-only chip view after a successful save.
  await waitFor(() => expect(screen.queryByLabelText('IPNI')).not.toBeInTheDocument());
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
  // Accepted usages render the nested Synonymy view (off GET .../synonymy); the Synonyms tab is
  // shown only for accepted taxa (a synonym has none), see TaxonDetail's synonyms panel.
  mockCommon();
  server.use(
    http.get('/api/projects/4/usages/10/synonymy', () =>
      HttpResponse.json({
        homotypic: [
          { id: 11, scientificName: 'Felis leo', authorship: 'Linnaeus, 1758', rank: 'species', status: 'SYNONYM', formattedName: null },
          { id: 12, scientificName: 'Panthera leo persica', authorship: null, rank: 'species', status: 'SYNONYM', formattedName: null },
        ],
        heterotypicGroups: [],
        misapplied: [],
      }),
    ),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  await userEvent.click(screen.getByRole('tab', { name: /synonyms/i }));
  await screen.findByText('Felis leo');
  expect(screen.getByText('Panthera leo persica')).toBeInTheDocument();
});

test('a synonym shows no taxon-level tabs (Synonyms, Vernaculars, Distribution, Biology)', async () => {
  mockCommon(baseUsage({ status: 'SYNONYM' }));
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);
  await screen.findByLabelText('Scientific name');
  // nomenclature + name-level tabs remain
  expect(screen.getByRole('tab', { name: 'Details' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'Types' })).toBeInTheDocument();
  // taxon-level tabs are gone for a synonym
  expect(screen.queryByRole('tab', { name: 'Synonyms' })).not.toBeInTheDocument();
  expect(screen.queryByRole('tab', { name: 'Vernaculars' })).not.toBeInTheDocument();
  expect(screen.queryByRole('tab', { name: 'Distribution' })).not.toBeInTheDocument();
  expect(screen.queryByRole('tab', { name: 'Biology' })).not.toBeInTheDocument();
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

test('shows a non-blocking "locked by X" banner when another user holds the lock', async () => {
  mockCommon();
  server.use(
    http.get('/api/projects/4/locks', () => HttpResponse.json([fakeLock()])),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  expect(await screen.findByText(/alice is editing/i)).toBeInTheDocument();
});

test('shows no banner when no one else holds the lock (default empty /locks)', async () => {
  mockCommon();
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  expect(screen.queryByText(/is editing/i)).not.toBeInTheDocument();
});

test('claims the lock only on genuine user edit, not on programmatic form seeding', async () => {
  mockCommon();
  let acquireCalls = 0;
  server.use(
    http.post('/api/projects/4/locks', () => {
      acquireCalls += 1;
      return HttpResponse.json(fakeLock({ userId: 1, username: 'me', heldByMe: true }));
    }),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  const authorship = await screen.findByLabelText('Authorship');
  await waitFor(() => expect(authorship).toHaveValue('Linnaeus, 1758'));
  // Form seeding (setValues/setFieldValue effects) must not have triggered a claim.
  expect(acquireCalls).toBe(0);

  await userEvent.type(authorship, '!');
  await waitFor(() => expect(acquireCalls).toBeGreaterThan(0));
});

test('claims the lock on a Status-only edit (Select onChange, no native input event)', async () => {
  mockCommon();
  let acquired = false;
  server.use(
    http.post('/api/projects/4/locks', () => {
      acquired = true;
      return HttpResponse.json(fakeLock({ userId: 1, username: 'me', heldByMe: true }));
    }),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await waitFor(() => expect(screen.getByLabelText('Scientific name')).toHaveValue('Panthera leo'));
  // Form seeding (setValues) must not have triggered a claim.
  expect(acquired).toBe(false);

  // Status is a non-searchable, click-only Select: selecting an option dispatches no native DOM
  // input event, so it never bubbles to the fieldset's onInput -- only the Select's own onChange
  // (wired to claim() in TaxonDetail) can catch this edit.
  const status = screen.getByRole('textbox', { name: 'Status' });
  await userEvent.click(status);
  // Within-group option (an accepted taxon only offers Accepted/Unassessed; cross-group goes via
  // Demote/Promote) -- the edit still fires the Select's onChange -> claim().
  await userEvent.click(await screen.findByRole('option', { name: 'Unassessed' }));

  await waitFor(() => expect(acquired).toBe(true));
});

test('the Status select offers only within-group options for an accepted taxon', async () => {
  mockCommon(); // accepted
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);
  const status = await screen.findByRole('textbox', { name: 'Status' });
  await userEvent.click(status);
  expect(await screen.findByRole('option', { name: 'Unassessed' })).toBeInTheDocument();
  // cross-group statuses are not offered -- they need Demote/Promote
  expect(screen.queryByRole('option', { name: 'Synonym' })).not.toBeInTheDocument();
  expect(screen.queryByRole('option', { name: 'Misapplied' })).not.toBeInTheDocument();
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

test('gender is an editable Select on a genus, with no agreement checkbox', async () => {
  mockCommon(
    baseUsage({ rank: 'genus', scientificName: 'Panthera', specificEpithet: null, gender: 'FEMININE' }),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  // the editable gender Select shows the genus's own gender (Mantine renders a visible + hidden input)
  expect((await screen.findAllByDisplayValue('FEMININE')).length).toBeGreaterThan(0);
  expect(screen.queryByRole('checkbox', { name: 'Gender agreement' })).not.toBeInTheDocument();
});

test('an unlinked species shows the unconfirmed genus gender, a genus picker, and an agreement checkbox', async () => {
  mockCommon(baseUsage({ genusGender: 'FEMININE', genderAgreement: true }));
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  // Unlinked (no genusId): the gender field is read-only and flagged unconfirmed.
  const derived = await screen.findByLabelText('Gender (unconfirmed)');
  expect(derived).toHaveValue('FEMININE');
  expect(derived).toHaveAttribute('readonly');
  expect(screen.getByRole('checkbox', { name: 'Gender agreement' })).toBeChecked();
  // the nomenclatural-genus picker is offered.
  expect(screen.getByRole('textbox', { name: 'Nomenclatural genus' })).toBeInTheDocument();
});

test('a linked species labels the gender field confirmed and shows the linked genus', async () => {
  mockCommon(baseUsage({ genusGender: 'FEMININE', genusId: 2, genusName: 'Panthera' }));
  server.use(
    http.get('/api/projects/4/usages', () =>
      HttpResponse.json({ items: [{ id: 2, scientificName: 'Panthera' }], total: 1 }),
    ),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  const gender = await screen.findByLabelText('Gender');
  expect(gender).toHaveValue('FEMININE');
  expect(screen.getByRole('textbox', { name: 'Nomenclatural genus' })).toHaveValue('Panthera');
});

test('a suprageneric name shows neither gender field', async () => {
  mockCommon(baseUsage({ rank: 'family', scientificName: 'Felidae', specificEpithet: null }));
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  await screen.findByLabelText('Scientific name');
  expect(screen.queryByLabelText('Gender')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Gender (from parent genus)')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Gender agreement')).not.toBeInTheDocument();
});

test('a non-scientific name type (formula) is flagged prominently', async () => {
  mockCommon(baseUsage({ nameType: 'FORMULA', scientificName: 'Aus x Bus' }));
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);
  expect(await screen.findByText('Hybrid formula')).toBeInTheDocument();
  expect(screen.getByText(/won’t atomise|won't atomise/)).toBeInTheDocument();
});

test('a scientific name that only partially parsed shows a softer warning', async () => {
  mockCommon(baseUsage({ parseState: 'PARTIAL' }));
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);
  expect(await screen.findByText('Partially parsed')).toBeInTheDocument();
});

test('a clean scientific name shows no name-quality warning', async () => {
  mockCommon(); // default: SCIENTIFIC + COMPLETE
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);
  await screen.findByLabelText('Scientific name');
  expect(screen.queryByText('Hybrid formula')).not.toBeInTheDocument();
  expect(screen.queryByText('Partially parsed')).not.toBeInTheDocument();
});

test('an editor can revalidate the taxon subtree, POSTing and showing the scoped summary', async () => {
  mockCommon();
  let posted = false;
  server.use(
    http.post('/api/projects/4/usages/10/revalidate', () => {
      posted = true;
      return HttpResponse.json({ total: 2, byStatus: { open: 2 }, bySeverity: { warning: 1, info: 1 } });
    }),
  );
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);

  const action = await screen.findByRole('button', { name: 'Revalidate this group' });
  await userEvent.click(action);

  await waitFor(() => expect(posted).toBe(true));
  expect(
    await screen.findByText('Revalidated this group: 2 issues (0 errors, 1 warning)'),
  ).toBeInTheDocument();
});

test('a viewer sees no revalidate-group action', async () => {
  mockCommon(baseUsage(), 'viewer');
  renderWithProviders(<TaxonDetail pid={4} usageId={10} />);
  await screen.findByLabelText('Scientific name');
  expect(screen.queryByRole('button', { name: 'Revalidate this group' })).not.toBeInTheDocument();
});
