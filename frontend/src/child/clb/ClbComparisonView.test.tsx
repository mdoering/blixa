import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../../test/utils';
import { server, http, HttpResponse } from '../../test/server';
import type { ClbComparison } from '../../api/clb';
import ClbComparisonView, { type OursSide } from './ClbComparisonView';

const clb: ClbComparison = {
  datasetKey: '3LXR',
  datasetTitle: 'Catalogue of Life',
  taxonId: 'T1',
  link: 'https://www.checklistbank.org/dataset/3LXR/taxon/T1',
  scientificName: 'Panthera leo',
  authorship: '(Linnaeus, 1758)',
  rank: 'species',
  status: 'ACCEPTED',
  acceptedName: null,
  classification: [],
  synonyms: [
    { scientificName: 'Felis leo', authorship: 'Linnaeus, 1758', status: 'SYNONYM', id: 'S1' },
    { scientificName: 'Leo africanus', authorship: null, status: 'SYNONYM', id: 'S2' },
    { scientificName: 'Leo nobilis', authorship: null, status: 'SYNONYM', id: 'S3' },
  ],
  vernacularNames: [{ id: '7', name: 'Lion', language: 'eng', country: null }],
  etymology: null,
  gender: null,
  publishedIn: 'Syst. Nat. 1758',
  publishedInPage: null,
  typeMaterial: [],
  nameRelations: [],
};

const ours: OursSide = {
  scientificName: 'Panthera leo',
  authorship: 'Mill.',
  rank: 'species',
  status: 'ACCEPTED',
  classification: [],
  synonyms: [{ scientificName: 'Felis leo', authorship: 'L.', status: 'SYNONYM' }],
  vernacularNames: [{ name: 'Lion', language: 'eng' }],
};

function setup() {
  server.use(http.get('/api/clb/dataset-labels', () => HttpResponse.json({ '3LXR': 'COL' })));
}

test('rows empty on both sides are hidden; filled ones are shown', () => {
  setup();
  renderWithProviders(<ClbComparisonView ours={ours} clb={clb} />);
  expect(screen.getByText('Authorship')).toBeInTheDocument();
  expect(screen.getByText('Published in')).toBeInTheDocument();
  expect(screen.getByText('Vernacular names')).toBeInTheDocument();
  for (const hidden of ['Etymology', 'Gender', 'Page', 'Type material', 'Name relations', 'Classification']) {
    expect(screen.queryByText(hidden)).not.toBeInTheDocument();
  }
});

test('no copy buttons without handlers (read-only viewer)', () => {
  setup();
  renderWithProviders(<ClbComparisonView ours={ours} clb={clb} />);
  expect(screen.queryByRole('button', { name: /copy/i })).not.toBeInTheDocument();
});

test('« copies a differing value, a single missing record, or all missing records', async () => {
  setup();
  const field = vi.fn();
  const synonyms = vi.fn();
  const vernaculars = vi.fn();
  renderWithProviders(
    <ClbComparisonView ours={ours} clb={clb} copy={{ field, synonyms, vernaculars }} />,
  );

  // Authorship differs -> offered; the name is identical -> not offered.
  await userEvent.click(screen.getByRole('button', { name: 'Copy authorship from CLB' }));
  expect(field).toHaveBeenCalledWith('authorship', '(Linnaeus, 1758)');
  expect(screen.queryByRole('button', { name: 'Copy name from CLB' })).not.toBeInTheDocument();

  // Synonyms: Felis leo is already ours; the other two are CLB-only -> one « each plus "« all".
  const synRow = screen.getByText('Synonyms').closest('tr') as HTMLElement;
  const perRecord = within(synRow).getAllByRole('button', { name: 'Copy to ours' });
  expect(perRecord).toHaveLength(2);
  await userEvent.click(perRecord[0]);
  expect(synonyms).toHaveBeenCalledWith(['S2']);
  await userEvent.click(within(synRow).getByRole('button', { name: '« all' }));
  expect(synonyms).toHaveBeenLastCalledWith(['S2', 'S3']);

  // Vernaculars: the only CLB one is already ours -> nothing to copy.
  const vnRow = screen.getByText('Vernacular names').closest('tr') as HTMLElement;
  expect(within(vnRow).queryByRole('button')).not.toBeInTheDocument();
  expect(vernaculars).not.toHaveBeenCalled();
});

test('name relations match on type + scientific name (authorship ignored); CLB-only ones get «', async () => {
  setup();
  const nameRelations = vi.fn();
  const classification = vi.fn();
  renderWithProviders(
    <ClbComparisonView
      ours={{ ...ours, nameRelations: [{ type: 'basionym', relatedName: 'Felis leo' }] }}
      clb={{
        ...clb,
        classification: [{ rank: 'genus', name: 'Panthera' }],
        nameRelations: [
          { id: 'B|basionym', type: 'basionym', relatedName: 'Felis leo Linnaeus, 1758', relatedScientificName: 'Felis leo' },
          { id: 'X|spelling correction', type: 'spelling correction', relatedName: 'Panthera leoo', relatedScientificName: 'Panthera leoo' },
        ],
      }}
      copy={{ nameRelations, classification }}
    />,
  );
  const relRow = screen.getByText('Name relations').closest('tr') as HTMLElement;
  const buttons = within(relRow).getAllByRole('button', { name: 'Copy to ours' });
  expect(buttons).toHaveLength(1); // the basionym is already ours
  await userEvent.click(buttons[0]);
  expect(nameRelations).toHaveBeenCalledWith(['X|spelling correction']);

  await userEvent.click(screen.getByRole('button', { name: 'Wire into tree' }));
  expect(classification).toHaveBeenCalled();
});
