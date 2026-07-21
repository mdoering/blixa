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

test('shows a lock indicator on a row with an active foreign lock', async () => {
  mockRootsAndChildren();
  server.use(
    http.get('/api/projects/7/locks', () =>
      HttpResponse.json([
        {
          id: 1,
          entityType: 'name_usage',
          entityId: 1,
          userId: 9,
          username: 'alice',
          acquiredAt: '2026-07-12T00:00:00Z',
          expiresAt: '2026-07-12T00:05:00Z',
          heldByMe: false,
          taskId: null,
          taskTitle: null,
        },
      ]),
    ),
  );
  renderWithProviders(<ClassificationTree pid={7} selectedId={null} onSelect={() => {}} />);

  await screen.findByText('Animalia');
  expect(await screen.findByLabelText('alice is editing')).toBeInTheDocument();
  // Plantae (id 2) has no lock -- only one indicator renders.
  expect(screen.getAllByLabelText(/is editing/)).toHaveLength(1);
});

test('shows no lock indicator when the lock list is empty', async () => {
  mockRootsAndChildren();
  renderWithProviders(<ClassificationTree pid={7} selectedId={null} onSelect={() => {}} />);

  await screen.findByText('Animalia');
  expect(screen.queryByLabelText(/is editing/)).not.toBeInTheDocument();
});

test('with includeUnassessed, requests the unassessed layer and visually marks those nodes', async () => {
  let rootsUrl = '';
  const provisional = {
    id: 5,
    scientificName: 'Provisia nova',
    authorship: null,
    rank: 'SPECIES',
    status: 'UNASSESSED',
    ordinal: 3,
    childCount: 0,
  };
  server.use(
    http.get('/api/projects/7/tree/roots', ({ request }) => {
      rootsUrl = request.url;
      return HttpResponse.json([animalia, provisional]);
    }),
    http.get('/api/projects/7/tree/children/1', () => HttpResponse.json([chordata])),
  );
  renderWithProviders(
    <ClassificationTree pid={7} selectedId={null} onSelect={() => {}} includeUnassessed />,
  );

  expect(await screen.findByText('Provisia nova')).toBeInTheDocument();
  // the tree asked the backend to include the unassessed layer...
  expect(rootsUrl).toContain('unassessed=true');
  // ...and only the unassessed node carries the marker (the accepted root does not).
  expect(screen.getAllByText('Unassessed')).toHaveLength(1);
});
