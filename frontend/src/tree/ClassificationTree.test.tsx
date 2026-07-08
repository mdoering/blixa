import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ClassificationTree from './ClassificationTree';

const animalia = {
  id: 1,
  scientificName: 'Animalia',
  authorship: null,
  rank: 'KINGDOM',
  status: 'ACCEPTED',
  ordinal: 1,
  childCount: 1,
};
const plantae = {
  id: 2,
  scientificName: 'Plantae',
  authorship: null,
  rank: 'KINGDOM',
  status: 'ACCEPTED',
  ordinal: 2,
  childCount: 0,
};
const chordata = {
  id: 3,
  scientificName: 'Chordata',
  authorship: 'Bateson, 1885',
  rank: 'PHYLUM',
  status: 'ACCEPTED',
  ordinal: 1,
  childCount: 0,
};

function mockRootsAndChildren() {
  server.use(
    http.get('/api/projects/7/tree/roots', () => HttpResponse.json([animalia, plantae])),
    http.get('/api/projects/7/tree/children/1', () => HttpResponse.json([chordata])),
  );
}

test('renders roots and only shows a chevron for nodes with children', async () => {
  mockRootsAndChildren();
  renderWithProviders(<ClassificationTree pid={7} selectedId={null} onSelect={() => {}} />);

  expect(await screen.findByText('Animalia')).toBeInTheDocument();
  expect(screen.getByText('Plantae')).toBeInTheDocument();
  // Animalia (childCount 1) gets exactly one expand chevron; Plantae (childCount 0) gets none.
  expect(screen.getAllByRole('button', { name: /expand/i })).toHaveLength(1);
});

test('expanding a node lazily loads and renders its children', async () => {
  mockRootsAndChildren();
  renderWithProviders(<ClassificationTree pid={7} selectedId={null} onSelect={() => {}} />);

  await screen.findByText('Animalia');
  expect(screen.queryByText('Chordata')).not.toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: /expand/i }));
  expect(await screen.findByText('Chordata')).toBeInTheDocument();
});

test('clicking a label fires onSelect with the node id', async () => {
  mockRootsAndChildren();
  const onSelect = vi.fn();
  renderWithProviders(<ClassificationTree pid={7} selectedId={null} onSelect={onSelect} />);

  await userEvent.click(await screen.findByText('Animalia'));
  expect(onSelect).toHaveBeenCalledWith(1);
});
