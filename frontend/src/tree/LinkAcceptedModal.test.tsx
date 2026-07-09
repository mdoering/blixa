import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import LinkAcceptedModal from './LinkAcceptedModal';

const bus = {
  id: 6,
  scientificName: 'Bus',
  authorship: null,
  rank: 'genus',
  status: 'ACCEPTED',
  ordinal: 1,
  childCount: 0,
};

test('links the synonym to a picked accepted name (pro parte add)', async () => {
  let linked = '';
  server.use(
    http.get('/api/projects/7/tree/roots', () => HttpResponse.json([bus])),
    http.put('/api/projects/7/usages/11/synonym-of/6', () => {
      linked = '11->6';
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const onClose = vi.fn();
  renderWithProviders(
    <LinkAcceptedModal pid={7} usage={{ id: 11, scientificName: 'Xus' }} opened onClose={onClose} />,
  );
  expect(screen.getByRole('button', { name: 'Link' })).toBeDisabled();
  await userEvent.click(await screen.findByText('Bus'));
  const btn = screen.getByRole('button', { name: 'Link' });
  await waitFor(() => expect(btn).toBeEnabled());
  await userEvent.click(btn);
  await waitFor(() => expect(linked).toBe('11->6'));
  await waitFor(() => expect(onClose).toHaveBeenCalled());
});
