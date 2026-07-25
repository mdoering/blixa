import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ChangeAcceptedModal from './ChangeAcceptedModal';

const bus = {
  id: 6,
  scientificName: 'Bus',
  authorship: null,
  rank: 'genus',
  status: 'ACCEPTED',
  ordinal: 1,
  childCount: 0,
};

test('changing the accepted name links the new target and unlinks the current one', async () => {
  const calls: string[] = [];
  server.use(
    http.get('/api/projects/7/tree/roots', () => HttpResponse.json([bus])),
    http.put('/api/projects/7/usages/11/synonym-of/6', () => {
      calls.push('link:6');
      return new HttpResponse(null, { status: 204 });
    }),
    http.delete('/api/projects/7/usages/11/synonym-of/3', () => {
      calls.push('unlink:3');
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const onClose = vi.fn();
  renderWithProviders(
    <ChangeAcceptedModal
      pid={7}
      usage={{ id: 11, scientificName: 'Xus' }}
      currentAcceptedId={3}
      opened
      onClose={onClose}
    />,
  );
  expect(screen.getByRole('button', { name: 'Change' })).toBeDisabled();
  await userEvent.click(await screen.findByText('Bus'));
  const btn = screen.getByRole('button', { name: 'Change' });
  await waitFor(() => expect(btn).toBeEnabled());
  await userEvent.click(btn);
  // link new first, then unlink old
  await waitFor(() => expect(calls).toEqual(['link:6', 'unlink:3']));
  await waitFor(() => expect(onClose).toHaveBeenCalled());
});

test('picking the current accepted name is a no-op (Change stays disabled)', async () => {
  server.use(
    http.get('/api/projects/7/tree/roots', () =>
      HttpResponse.json([{ ...bus, id: 3, scientificName: 'Cus' }]),
    ),
  );
  renderWithProviders(
    <ChangeAcceptedModal
      pid={7}
      usage={{ id: 11, scientificName: 'Xus' }}
      currentAcceptedId={3}
      opened
      onClose={() => {}}
    />,
  );
  await userEvent.click(await screen.findByText('Cus'));
  expect(screen.getByRole('button', { name: 'Change' })).toBeDisabled();
});
