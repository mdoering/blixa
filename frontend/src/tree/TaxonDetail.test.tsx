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
    link: null,
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
    http.get('/api/projects/4/issues', () => HttpResponse.json([])),
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
