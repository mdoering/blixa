import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import NameSearchPage from './NameSearchPage';

const abiesAlba = {
  id: 1,
  parentId: null,
  status: 'ACCEPTED',
  scientificName: 'Abies alba',
  authorship: 'Mill.',
  rank: 'species',
  version: 0,
};

const abiesNigra = {
  id: 2,
  parentId: null,
  status: 'SYNONYM',
  scientificName: 'Abies nigra',
  authorship: null,
  rank: 'species',
  version: 0,
};

// Fuller detail shape TaxonDetail's own GET returns (used by the deep-link test below).
const abiesAlbaDetail = {
  ...abiesAlba,
  namePhrase: null,
  extinct: null,
  environment: null,
  temporalRangeStart: null,
  temporalRangeEnd: null,
  uninomial: null,
  genus: 'Abies',
  infragenericEpithet: null,
  specificEpithet: 'alba',
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
  publishedInYear: null,
  publishedInPage: null,
  publishedInPageLink: null,
  gender: null,
  etymology: null,
  nameType: 'SCIENTIFIC',
  parseState: 'COMPLETE',
  remarks: null,
  formattedName: 'Abies alba Mill.',
  acceptedParentIds: [],
  synonymIds: [],
};

// Both rows satisfy the (much larger) NameUsage shape structurally-enough for the table's
// columns (scientificName/authorship/rank/status) and for NameActionMenu's minimal usage shape;
// TaxonDetail's own request is mocked separately below with the fuller shape it needs.
const project = {
  id: 9,
  title: 'Life',
  alias: null,
  description: null,
  nomCode: null,
  license: null,
  geographicScope: null,
  taxonomicScope: null,
  role: 'owner',
};

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/names" element={<NameSearchPage />} />
    </Routes>,
    { route: '/projects/9/names' },
  );
}

test('rows render from the search results', async () => {
  server.use(
    http.get('/api/projects/9', () => HttpResponse.json(project)),
    http.get('/api/projects/9/usages', () =>
      HttpResponse.json({ items: [abiesAlba, abiesNigra], total: 2 }),
    ),
  );
  renderPage();

  expect(await screen.findByText('Abies alba')).toBeInTheDocument();
  expect(screen.getByText('Abies nigra')).toBeInTheDocument();
  expect(screen.getByText('Mill.')).toBeInTheDocument();
});

test('setting the status filter re-queries with status= and shows the filtered set', async () => {
  const seenStatuses: (string | null)[] = [];
  server.use(
    http.get('/api/projects/9', () => HttpResponse.json(project)),
    http.get('/api/projects/9/usages', ({ request }) => {
      const url = new URL(request.url);
      const status = url.searchParams.get('status');
      seenStatuses.push(status);
      if (status === 'SYNONYM') {
        return HttpResponse.json({ items: [abiesNigra], total: 1 });
      }
      return HttpResponse.json({ items: [abiesAlba, abiesNigra], total: 2 });
    }),
  );
  renderPage();

  await screen.findByText('Abies alba');

  await userEvent.click(screen.getByPlaceholderText('Status'));
  await userEvent.click(await screen.findByRole('option', { name: 'Synonym' }));

  await waitFor(() => expect(seenStatuses).toContain('SYNONYM'));
  await waitFor(() => expect(screen.queryByText('Abies alba')).not.toBeInTheDocument());
  expect(screen.getByText('Abies nigra')).toBeInTheDocument();
});

test('total drives the pager row count', async () => {
  server.use(
    http.get('/api/projects/9', () => HttpResponse.json(project)),
    http.get('/api/projects/9/usages', () =>
      HttpResponse.json({ items: [abiesAlba, abiesNigra], total: 37 }),
    ),
  );
  renderPage();

  await screen.findByText('Abies alba');
  expect(await screen.findByText(/of 37/)).toBeInTheDocument();
});

test('clicking a row shows the taxon detail on the right', async () => {
  server.use(
    http.get('/api/projects/9', () => HttpResponse.json(project)),
    http.get('/api/projects/9/usages', () =>
      HttpResponse.json({ items: [abiesAlba, abiesNigra], total: 2 }),
    ),
    http.get('/api/projects/9/usages/1', () =>
      HttpResponse.json({
        id: 1,
        parentId: null,
        status: 'ACCEPTED',
        namePhrase: null,
        extinct: null,
        environment: null,
        temporalRangeStart: null,
        temporalRangeEnd: null,
        scientificName: 'Abies alba',
        authorship: 'Mill.',
        rank: 'species',
        uninomial: null,
        genus: 'Abies',
        infragenericEpithet: null,
        specificEpithet: 'alba',
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
        publishedInYear: null,
        publishedInPage: null,
        publishedInPageLink: null,
        gender: null,
        etymology: null,
        nameType: 'SCIENTIFIC',
        parseState: 'COMPLETE',
        remarks: null,
        formattedName: 'Abies alba Mill.',
        acceptedParentIds: [],
        synonymIds: [],
        version: 0,
      }),
    ),
    http.get('/api/projects/9/usages/1/synonyms', () => HttpResponse.json([])),
    http.get('/api/projects/9/issues', () => HttpResponse.json([])),
  );
  renderPage();

  expect(screen.getByText('Select a name to see its details.')).toBeInTheDocument();

  await userEvent.click(await screen.findByText('Abies alba'));

  await screen.findByLabelText('Scientific name');
  expect(screen.getByLabelText('Scientific name')).toHaveValue('Abies alba');
});

test('deleting the currently-selected row clears the detail pane', async () => {
  server.use(
    http.get('/api/projects/9', () => HttpResponse.json(project)),
    http.get('/api/projects/9/usages', () =>
      HttpResponse.json({ items: [abiesAlba, abiesNigra], total: 2 }),
    ),
    http.get('/api/projects/9/usages/1', () =>
      HttpResponse.json({
        id: 1,
        parentId: null,
        status: 'ACCEPTED',
        namePhrase: null,
        extinct: null,
        environment: null,
        temporalRangeStart: null,
        temporalRangeEnd: null,
        scientificName: 'Abies alba',
        authorship: 'Mill.',
        rank: 'species',
        uninomial: null,
        genus: 'Abies',
        infragenericEpithet: null,
        specificEpithet: 'alba',
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
        publishedInYear: null,
        publishedInPage: null,
        publishedInPageLink: null,
        gender: null,
        etymology: null,
        nameType: 'SCIENTIFIC',
        parseState: 'COMPLETE',
        remarks: null,
        formattedName: 'Abies alba Mill.',
        acceptedParentIds: [],
        synonymIds: [],
        version: 0,
      }),
    ),
    http.get('/api/projects/9/usages/1/synonyms', () => HttpResponse.json([])),
    http.get('/api/projects/9/issues', () => HttpResponse.json([])),
    http.delete('/api/projects/9/usages/1', () => new HttpResponse(null, { status: 204 })),
  );
  renderPage();

  // Select the row -- the detail pane shows it.
  await userEvent.click(await screen.findByText('Abies alba'));
  await screen.findByLabelText('Scientific name');

  // Delete that same (selected) row via its action menu.
  const actionButtons = await screen.findAllByLabelText('Actions');
  await userEvent.click(actionButtons[0]);
  await userEvent.click(await screen.findByText('Delete'));
  const dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

  // The selection is cleared -- the detail pane falls back to the placeholder instead of
  // continuing to render the now-deleted usage.
  await waitFor(() =>
    expect(screen.getByText('Select a name to see its details.')).toBeInTheDocument(),
  );
});

test('?usage= deep-link preselects that usage in the detail pane (no click)', async () => {
  server.use(
    http.get('/api/projects/9', () => HttpResponse.json(project)),
    http.get('/api/projects/9/usages', () =>
      HttpResponse.json({ items: [abiesAlba, abiesNigra], total: 2 }),
    ),
    http.get('/api/projects/9/usages/1', () => HttpResponse.json(abiesAlbaDetail)),
    http.get('/api/projects/9/usages/1/synonyms', () => HttpResponse.json([])),
    http.get('/api/projects/9/issues', () => HttpResponse.json([])),
  );
  renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/names" element={<NameSearchPage />} />
    </Routes>,
    { route: '/projects/9/names?usage=1' },
  );

  // The detail form appears without any interaction, seeded from the ?usage= param.
  expect(await screen.findByLabelText('Scientific name')).toHaveValue('Abies alba');
});

test('the row action menu opens', async () => {
  server.use(
    http.get('/api/projects/9', () => HttpResponse.json(project)),
    http.get('/api/projects/9/usages', () =>
      HttpResponse.json({ items: [abiesAlba, abiesNigra], total: 2 }),
    ),
  );
  renderPage();

  await screen.findByText('Abies alba');

  const actionButtons = await screen.findAllByLabelText('Actions');
  await userEvent.click(actionButtons[0]);

  expect(await screen.findByText('Add child')).toBeInTheDocument();
  expect(screen.getByText('Add synonym')).toBeInTheDocument();
  expect(screen.getByText('Delete')).toBeInTheDocument();
});
