import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import TreePage from './TreePage';

const animalia = {
  id: 1,
  scientificName: 'Animalia',
  authorship: null,
  rank: 'KINGDOM',
  status: 'ACCEPTED',
  ordinal: 1,
  childCount: 0,
};

const animaliaUsage = {
  id: 1,
  parentId: null,
  status: 'ACCEPTED',
  namePhrase: null,
  extinct: null,
  environment: null,
  temporalRangeStart: null,
  temporalRangeEnd: null,
  scientificName: 'Animalia',
  authorship: null,
  rank: 'KINGDOM',
  uninomial: 'Animalia',
  genus: null,
  infragenericEpithet: null,
  specificEpithet: null,
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
  link: null,
  remarks: null,
  formattedName: 'Animalia',
  acceptedParentIds: [],
  synonymIds: [],
  version: 0,
};

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/tree" element={<TreePage />} />
    </Routes>,
    { route: '/projects/9/tree' },
  );
}

test('selecting a node shows its breadcrumb path and the taxon detail panel', async () => {
  server.use(
    http.get('/api/projects/9/tree/roots', () => HttpResponse.json([animalia])),
    http.get('/api/projects/9/tree/path/1', () =>
      HttpResponse.json([{ id: 1, scientificName: 'Animalia', rank: 'KINGDOM' }]),
    ),
    http.get('/api/projects/9', () =>
      HttpResponse.json({
        id: 9, title: 'Life', alias: null, description: null, nomCode: null,
        license: null, geographicScope: null, taxonomicScope: null, role: 'owner',
      }),
    ),
    http.get('/api/projects/9/usages/1', () => HttpResponse.json(animaliaUsage)),
    http.get('/api/projects/9/usages/1/synonyms', () => HttpResponse.json([])),
    http.get('/api/projects/9/issues', () => HttpResponse.json([])),
  );
  renderPage();

  expect(
    screen.getByText('Select a taxon in the tree to see its details.'),
  ).toBeInTheDocument();

  await userEvent.click(await screen.findByText('Animalia'));

  // Breadcrumb (path) and the detail panel (a prefilled form field) both render.
  await screen.findByLabelText('Scientific name');
  expect(screen.getByLabelText('Scientific name')).toHaveValue('Animalia');
  const breadcrumbEntries = await screen.findAllByText('Animalia');
  expect(breadcrumbEntries.length).toBeGreaterThan(0);
});
