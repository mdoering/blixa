import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import DemoteModal from './DemoteModal';

const bus = {
  id: 6,
  scientificName: 'Bus',
  authorship: null,
  rank: 'genus',
  status: 'ACCEPTED',
  ordinal: 1,
  childCount: 0,
};

function mockNode(opts: { synonymIds?: number[]; children?: unknown[] } = {}) {
  server.use(
    http.get('/api/projects/7/usages/9', () =>
      HttpResponse.json({ id: 9, version: 2, status: 'ACCEPTED', scientificName: 'Aus', synonymIds: opts.synonymIds ?? [] }),
    ),
    http.get('/api/projects/7/tree/children/9', () => HttpResponse.json(opts.children ?? [])),
    http.get('/api/projects/7/tree/roots', () => HttpResponse.json([bus])),
  );
}

test('picks a target and POSTs the demote with conditional children/synonyms choices', async () => {
  let body: unknown = null;
  mockNode({ synonymIds: [11], children: [{ id: 20, scientificName: 'Aus bus', authorship: null, rank: 'species', status: 'ACCEPTED', ordinal: 1, childCount: 0 }] });
  server.use(
    http.post('/api/projects/7/usages/9/demote', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ id: 9 });
    }),
  );
  const onClose = vi.fn();
  renderWithProviders(
    <DemoteModal pid={7} usage={{ id: 9, scientificName: 'Aus' }} initialStatus="SYNONYM" opened onClose={onClose} />,
  );

  // The conditional radio groups appear because the node has 1 child + 1 synonym.
  expect(await screen.findByText(/accepted child/)).toBeInTheDocument();
  expect(screen.getByText('Its former parent')).toBeInTheDocument(); // children radio option
  expect(screen.getByText('Set unassessed')).toBeInTheDocument(); // synonyms radio option

  // Demote is disabled until a target is picked.
  expect(screen.getByRole('button', { name: 'Demote' })).toBeDisabled();
  await userEvent.click(await screen.findByText('Bus'));
  const btn = screen.getByRole('button', { name: 'Demote' });
  await waitFor(() => expect(btn).toBeEnabled());
  await userEvent.click(btn);

  await waitFor(() => expect(onClose).toHaveBeenCalled());
  expect(body).toEqual({
    acceptedId: 6,
    status: 'SYNONYM',
    childrenTo: 'new-accepted',
    synonymsTo: 'new-accepted',
    version: 2,
  });
});

test('omits childrenTo/synonymsTo when the node has neither', async () => {
  let body: unknown = null;
  mockNode({ synonymIds: [], children: [] });
  server.use(
    http.post('/api/projects/7/usages/9/demote', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ id: 9 });
    }),
  );
  renderWithProviders(
    <DemoteModal pid={7} usage={{ id: 9, scientificName: 'Aus' }} initialStatus="MISAPPLIED" opened onClose={() => {}} />,
  );
  await userEvent.click(await screen.findByText('Bus'));
  await userEvent.click(screen.getByRole('button', { name: 'Demote' }));
  await waitFor(() => expect(body).toEqual({ acceptedId: 6, status: 'MISAPPLIED', version: 2 }));
});
