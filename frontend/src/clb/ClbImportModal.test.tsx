import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ClbImportModal from './ClbImportModal';

const focalUsage = { id: 42, scientificName: 'Panthera' };

function renderModal() {
  return renderWithProviders(
    <ClbImportModal projectId={7} focalUsage={focalUsage} opened onClose={() => {}} />,
  );
}

test('pasting a valid CLB taxon URL resolves and shows the taxon name', async () => {
  server.use(
    http.get('/api/clb/3LXR/resolve/6W3C4', () =>
      HttpResponse.json({
        datasetKey: '3LXR',
        taxonId: '6W3C4',
        scientificName: 'Panthera leo',
        rank: 'species',
      }),
    ),
  );
  renderModal();

  await userEvent.type(
    screen.getByLabelText('ChecklistBank or catalogueoflife.org URL'),
    'https://www.checklistbank.org/dataset/3LXR/taxon/6W3C4',
  );

  expect(await screen.findByText(/Panthera leo/)).toBeInTheDocument();
  expect(screen.getByText(/\(species\)/)).toBeInTheDocument();
  expect(screen.getByText(/3LXR/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Import' })).toBeEnabled();
});

test('an unrecognized pasted URL shows an inline error and Import stays disabled', async () => {
  renderModal();

  await userEvent.type(
    screen.getByLabelText('ChecklistBank or catalogueoflife.org URL'),
    'https://example.com/not-clb',
  );

  expect(await screen.findByText('Not a recognized ChecklistBank URL')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled();
});

test('search path: picking a dataset, then a taxon (with a rank filter), posts the picked IDs -- ' +
  'not the display labels -- even when two taxa share the exact same label', async () => {
  server.use(
    http.get('/api/clb/datasets', () =>
      HttpResponse.json([{ key: '3LXR', title: 'Catalogue of Life', alias: 'COL' }]),
    ),
    http.get('/api/clb/3LXR/usages', () =>
      HttpResponse.json([
        { id: '6W3C4', scientificName: 'Panthera leo', rank: 'species', status: 'accepted' },
        // A homonym sharing the exact "name (rank)" label of the hit above. The pickers used to
        // be Mantine Autocomplete, whose options are plain label strings mapped back to a hit via
        // a Map<label, hit> -- two hits with the same label collapsed to one map entry, so
        // selecting either one silently posted whichever hit happened to win the collision. Now
        // that they're Select with id-keyed {value, label} options, the two stay distinguishable.
        { id: '9ZZZ9', scientificName: 'Panthera leo', rank: 'species', status: 'accepted' },
      ]),
    ),
  );
  let postedBody: unknown = null;
  server.use(
    http.post('/api/projects/7/usages/42/clb-import', async ({ request }) => {
      postedBody = await request.json();
      return HttpResponse.json({
        nameUsages: 1,
        synonyms: 0,
        references: 0,
        children: {
          vernacular: 0,
          distribution: 0,
          typeMaterial: 0,
          media: 0,
          estimate: 0,
          property: 0,
          nameRelation: 0,
        },
        issues: [],
      });
    }),
  );
  renderModal();

  await userEvent.click(screen.getByRole('radio', { name: 'Search' }));

  await userEvent.type(screen.getByRole('textbox', { name: 'Dataset' }), 'Catalogue');
  await userEvent.click(await screen.findByRole('option', { name: 'Catalogue of Life (COL)' }));

  const taxonField = screen.getByRole('textbox', { name: 'Taxon' });
  expect(taxonField).toBeEnabled();
  await userEvent.type(taxonField, 'leo');
  const leoOptions = await screen.findAllByRole('option', { name: 'Panthera leo (species)' });
  expect(leoOptions).toHaveLength(2);
  // Pick the SECOND homonym: if selection were still keyed by label, it would be
  // indistinguishable from the first, and there'd be no way to prove which id got posted.
  await userEvent.click(leoOptions[1]);

  await userEvent.click(screen.getByRole('textbox', { name: 'Rank' }));
  await userEvent.click(await screen.findByRole('option', { name: 'species' }));

  expect(screen.getByRole('button', { name: 'Import' })).toBeEnabled();
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  await waitFor(() => expect(postedBody).not.toBeNull());
  expect(postedBody).toEqual({ datasetKey: '3LXR', sourceTaxonId: '9ZZZ9', mode: 'TAXON_SUBTREE' });
});

test('UPDATE_FOCAL reveals the entity-type checkboxes, all checked by default; unchecking one ' +
  'is reflected in the posted entityTypes', async () => {
  server.use(
    http.get('/api/clb/3LXR/resolve/6W3C4', () =>
      HttpResponse.json({
        datasetKey: '3LXR',
        taxonId: '6W3C4',
        scientificName: 'Panthera leo',
        rank: 'species',
      }),
    ),
  );
  let postedBody: unknown = null;
  server.use(
    http.post('/api/projects/7/usages/42/clb-import', async ({ request }) => {
      postedBody = await request.json();
      return HttpResponse.json({
        nameUsages: 0,
        synonyms: 3,
        references: 1,
        children: {
          vernacular: 0,
          distribution: 0,
          typeMaterial: 0,
          media: 0,
          estimate: 0,
          property: 0,
          nameRelation: 0,
        },
        issues: [],
      });
    }),
  );
  renderModal();

  await userEvent.type(
    screen.getByLabelText('ChecklistBank or catalogueoflife.org URL'),
    'https://www.checklistbank.org/dataset/3LXR/taxon/6W3C4',
  );
  await screen.findByText(/Panthera leo/);

  expect(screen.queryByRole('checkbox', { name: 'Vernacular names' })).not.toBeInTheDocument();

  await userEvent.click(screen.getByRole('radio', { name: /Update/ }));

  const vernacular = await screen.findByRole('checkbox', { name: 'Vernacular names' });
  expect(vernacular).toBeChecked();
  expect(screen.getByRole('checkbox', { name: 'Synonyms' })).toBeChecked();
  expect(screen.getByRole('checkbox', { name: 'Name relations' })).toBeChecked();

  await userEvent.click(vernacular);
  expect(vernacular).not.toBeChecked();

  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  await waitFor(() => expect(postedBody).not.toBeNull());
  const body = postedBody as { datasetKey: string; sourceTaxonId: string; mode: string; entityTypes: string[] };
  expect(body.datasetKey).toBe('3LXR');
  expect(body.sourceTaxonId).toBe('6W3C4');
  expect(body.mode).toBe('UPDATE_FOCAL');
  expect(body.entityTypes).not.toContain('vernacular');
  expect(body.entityTypes).toEqual(
    expect.arrayContaining(['synonyms', 'distribution', 'typeMaterial', 'media', 'estimate', 'property', 'nameRelation']),
  );
  expect(body.entityTypes).toHaveLength(7);
});

test('a successful import posts {datasetKey, sourceTaxonId, mode} (no entityTypes for TAXON_SUBTREE) ' +
  'and renders the summary', async () => {
  server.use(
    http.get('/api/clb/3LXR/resolve/6W3C4', () =>
      HttpResponse.json({
        datasetKey: '3LXR',
        taxonId: '6W3C4',
        scientificName: 'Panthera leo',
        rank: 'species',
      }),
    ),
  );
  let postedBody: unknown = null;
  server.use(
    http.post('/api/projects/7/usages/42/clb-import', async ({ request }) => {
      postedBody = await request.json();
      return HttpResponse.json({
        nameUsages: 5,
        synonyms: 2,
        references: 1,
        children: {
          vernacular: 1,
          distribution: 0,
          typeMaterial: 0,
          media: 0,
          estimate: 0,
          property: 0,
          nameRelation: 0,
        },
        issues: [],
      });
    }),
  );
  renderModal();

  await userEvent.type(
    screen.getByLabelText('ChecklistBank or catalogueoflife.org URL'),
    'https://www.checklistbank.org/dataset/3LXR/taxon/6W3C4',
  );
  await screen.findByText(/Panthera leo/);

  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  await waitFor(() => expect(postedBody).not.toBeNull());
  expect(postedBody).toEqual({ datasetKey: '3LXR', sourceTaxonId: '6W3C4', mode: 'TAXON_SUBTREE' });

  expect(await screen.findByText(/5 name usage\(s\), 2 synonym\(s\), 1 reference\(s\)/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
});

test('a failed import shows the error message', async () => {
  server.use(
    http.get('/api/clb/3LXR/resolve/6W3C4', () =>
      HttpResponse.json({
        datasetKey: '3LXR',
        taxonId: '6W3C4',
        scientificName: 'Panthera leo',
        rank: 'species',
      }),
    ),
    http.post('/api/projects/7/usages/42/clb-import', () =>
      HttpResponse.json({ error: 'subtree too large' }, { status: 400 }),
    ),
  );
  renderModal();

  await userEvent.type(
    screen.getByLabelText('ChecklistBank or catalogueoflife.org URL'),
    'https://www.checklistbank.org/dataset/3LXR/taxon/6W3C4',
  );
  await screen.findByText(/Panthera leo/);

  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  expect(await screen.findByText('subtree too large')).toBeInTheDocument();
});
