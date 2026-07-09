import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import PromoteModal from './PromoteModal';

const bus = {
  id: 6,
  scientificName: 'Bus',
  authorship: null,
  rank: 'genus',
  status: 'ACCEPTED',
  ordinal: 1,
  childCount: 0,
};

function mockNode() {
  server.use(
    http.get('/api/projects/7/usages/9', () =>
      HttpResponse.json({ id: 9, version: 3, status: 'SYNONYM', scientificName: 'Xus' }),
    ),
    http.get('/api/projects/7/tree/roots', () => HttpResponse.json([bus])),
  );
}

test('promotes under a picked parent', async () => {
  let body: unknown = null;
  mockNode();
  server.use(
    http.post('/api/projects/7/usages/9/promote', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ id: 9 });
    }),
  );
  const onClose = vi.fn();
  renderWithProviders(
    <PromoteModal pid={7} usage={{ id: 9, scientificName: 'Xus' }} opened onClose={onClose} />,
  );
  expect(screen.getByRole('button', { name: 'Promote' })).toBeDisabled();
  await userEvent.click(await screen.findByText('Bus'));
  const btn = screen.getByRole('button', { name: 'Promote' });
  await waitFor(() => expect(btn).toBeEnabled());
  await userEvent.click(btn);
  await waitFor(() => expect(onClose).toHaveBeenCalled());
  expect(body).toEqual({ parentId: 6, version: 3 });
});

test('"Make it a root" promotes to parentId null', async () => {
  let body: unknown = null;
  mockNode();
  server.use(
    http.post('/api/projects/7/usages/9/promote', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ id: 9 });
    }),
  );
  renderWithProviders(
    <PromoteModal pid={7} usage={{ id: 9, scientificName: 'Xus' }} opened onClose={() => {}} />,
  );
  await userEvent.click(await screen.findByText('Make it a root'));
  await userEvent.click(screen.getByRole('button', { name: 'Promote' }));
  await waitFor(() => expect(body).toEqual({ parentId: null, version: 3 }));
});
