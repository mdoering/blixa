import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../../test/utils';
import { server, http, HttpResponse } from '../../test/server';
import CompareClbModal from './CompareClbModal';

const usage = {
  id: 5,
  scientificName: 'Panthera leo',
  authorship: 'Mill.',
  rank: 'species',
  status: 'ACCEPTED',
  version: 0,
};

const clbComparison = {
  datasetKey: '3LXR',
  datasetTitle: 'Catalogue of Life',
  taxonId: '6W3C4',
  link: 'https://www.checklistbank.org/dataset/3LXR/taxon/6W3C4',
  scientificName: 'Panthera leo',
  authorship: '(Linnaeus, 1758)',
  rank: 'species',
  status: 'ACCEPTED',
  classification: [{ rank: 'family', name: 'Felidae' }],
  synonyms: [],
};

test('picks a global CLB hit and shows the side-by-side comparison', async () => {
  server.use(
    http.get('/api/projects/3', () =>
      HttpResponse.json({ id: 3, title: 'P', role: 'owner', favoriteClbDatasets: [] }),
    ),
    http.get('/api/projects/3/usages/5', () => HttpResponse.json(usage)),
    http.get('/api/projects/3/tree/path/5', () =>
      HttpResponse.json([
        { id: 1, scientificName: 'Felidae', rank: 'family' },
        { id: 5, scientificName: 'Panthera leo', rank: 'species' },
      ]),
    ),
    http.get('/api/projects/3/usages/5/synonyms', () => HttpResponse.json([])),
    http.get('/api/clb/usages', () =>
      HttpResponse.json([
        {
          datasetKey: '3LXR',
          datasetTitle: 'Catalogue of Life',
          id: '6W3C4',
          scientificName: 'Panthera leo',
          authorship: '(Linnaeus, 1758)',
          rank: 'species',
          status: 'accepted',
        },
      ]),
    ),
    http.get('/api/clb/3LXR/compare/6W3C4', () => HttpResponse.json(clbComparison)),
  );

  renderWithProviders(<CompareClbModal pid={3} usageId={5} opened onClose={() => {}} />);

  // the focal name prefills the all-datasets search; its hit appears -> click it
  await userEvent.click(await screen.findByText(/Panthera leo \(Linnaeus, 1758\)/));

  // the comparison shows both authorships (they differ), so the diff is visible
  expect(await screen.findByText('Mill.')).toBeInTheDocument();
  expect(screen.getByText('(Linnaeus, 1758)')).toBeInTheDocument();
  // classification from both sides
  expect(screen.getAllByText(/Felidae/).length).toBeGreaterThan(0);
});
