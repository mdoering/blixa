import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import JoinRequestModal from './JoinRequestModal';

test('an invalid ORCID shows a validation error and does not call the API', async () => {
  let called = false;
  server.use(
    http.post('/api/public/projects/5/join', () => {
      called = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderWithProviders(<JoinRequestModal idOrAlias="5" opened onClose={() => {}} />);

  await userEvent.type(screen.getByLabelText('ORCID'), 'not-an-orcid');
  await userEvent.click(screen.getByRole('button', { name: /send request/i }));

  expect(await screen.findByText(/enter a valid orcid/i)).toBeInTheDocument();
  expect(called).toBe(false);
});

test('a valid ORCID submits and shows a confirmation', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/public/projects/5/join', async ({ request }) => {
      body = await request.json();
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const onClose = vi.fn();
  renderWithProviders(<JoinRequestModal idOrAlias="5" opened onClose={onClose} />);

  await userEvent.type(screen.getByLabelText('ORCID'), '0000-0002-1825-0097');
  await userEvent.type(screen.getByLabelText('Name'), 'Jane Doe');
  await userEvent.click(screen.getByRole('button', { name: /send request/i }));

  expect(await screen.findByText(/request sent/i)).toBeInTheDocument();
  await waitFor(() =>
    expect(body).toEqual({ orcid: '0000-0002-1825-0097', name: 'Jane Doe' }),
  );
});
