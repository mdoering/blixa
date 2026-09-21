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
    http.get('/api/clb/dataset-labels', () => HttpResponse.json({ '3LXR': 'COL' })),
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

test('global hits show each dataset by its alias (one batched label lookup), never the raw key', async () => {
  const labelCalls: string[][] = [];
  server.use(
    http.get('/api/projects/3/usages/5', () => HttpResponse.json(usage)),
    http.get('/api/projects/3', () => HttpResponse.json({ id: 3, role: 'editor', favoriteClbDatasets: [] })),
    http.get('/api/projects/3/tree/path/5', () => HttpResponse.json([])),
    http.get('/api/projects/3/usages/5/synonyms', () => HttpResponse.json([])),
    http.get('/api/clb/usages', () =>
      HttpResponse.json(
        ['2144', '2207'].map((k) => ({
          datasetKey: k,
          datasetTitle: null,
          id: `t${k}`,
          scientificName: 'Panthera leo',
          authorship: null,
          rank: 'species',
          status: 'accepted',
        })),
      ),
    ),
    http.get('/api/clb/dataset-labels', ({ request }) => {
      labelCalls.push(new URL(request.url).searchParams.getAll('key'));
      return HttpResponse.json({ '2144': 'MSW3', '2207': 'ITIS' });
    }),
  );

  renderWithProviders(<CompareClbModal pid={3} usageId={5} opened onClose={() => {}} />);

  expect(await screen.findByText('MSW3')).toBeInTheDocument();
  expect(screen.getByText('ITIS')).toBeInTheDocument();
  expect(screen.queryByText(/dataset 2144/)).not.toBeInTheDocument();
  expect(labelCalls).toEqual([['2144', '2207']]);
});

test('by dataset: pick a dataset, then click a name hit to compare; no hits shows a hint', async () => {
  server.use(
    http.get('/api/projects/3/usages/5', () => HttpResponse.json(usage)),
    http.get('/api/projects/3', () => HttpResponse.json({ id: 3, role: 'editor', favoriteClbDatasets: [] })),
    http.get('/api/projects/3/tree/path/5', () => HttpResponse.json([])),
    http.get('/api/projects/3/usages/5/synonyms', () => HttpResponse.json([])),
    http.get('/api/clb/datasets', () =>
      HttpResponse.json([{ key: '3LXR', title: 'Catalogue of Life', alias: 'COL' }]),
    ),
    http.get('/api/clb/3LXR/usages', ({ request }) => {
      const q = new URL(request.url).searchParams.get('q');
      return HttpResponse.json(
        q === 'Panthera leo'
          ? [{ id: '6W3C4', scientificName: 'Panthera leo', rank: 'species', status: 'accepted' }]
          : [],
      );
    }),
    http.get('/api/clb/3LXR/compare/6W3C4', () => HttpResponse.json(clbComparison)),
    http.get('/api/clb/dataset-labels', () => HttpResponse.json({ '3LXR': 'COL' })),
  );
  renderWithProviders(<CompareClbModal pid={3} usageId={5} opened onClose={() => {}} />);

  await userEvent.click(await screen.findByText('By dataset'));
  await userEvent.type(screen.getByLabelText('Search datasets'), 'catalogue');
  await userEvent.click(await screen.findByText('Catalogue of Life'));

  // the focal name is prefilled and its hit is listed with a hint
  expect(await screen.findByText(/click a name to compare/i)).toBeInTheDocument();
  const nameInput = screen.getByLabelText('Search a name');
  await userEvent.clear(nameInput);
  await userEvent.type(nameInput, 'Nothing here');
  expect(await screen.findByText(/no matching names in this dataset/i)).toBeInTheDocument();

  await userEvent.clear(nameInput);
  await userEvent.type(nameInput, 'Panthera leo');
  await userEvent.click(await screen.findByText('Panthera leo'));
  expect(await screen.findByText('Mill.')).toBeInTheDocument();
});
