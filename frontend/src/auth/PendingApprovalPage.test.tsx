import { expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { render, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import PendingApprovalPage from './PendingApprovalPage';

const me = (over: Record<string, unknown>) =>
  http.get('/api/me', () =>
    HttpResponse.json({ id: 1, username: 'u', email: '', orcid: '', displayName: 'U',
      admin: false, state: 'PENDING', ...over }),
  );

test('DISABLED shows the disabled notice and no application form', async () => {
  server.use(me({ state: 'DISABLED' }));
  render(<PendingApprovalPage state="DISABLED" />);
  expect(await screen.findByRole('heading', { name: /disabled/i })).toBeInTheDocument();
  expect(screen.queryByLabelText(/email/i)).not.toBeInTheDocument();
});

test('PENDING without email shows the apply form and submits email + message', async () => {
  server.use(me({ state: 'PENDING', email: '' }));
  let put: unknown = null;
  server.use(
    http.put('/api/me/application', async ({ request }) => {
      put = await request.json();
      return HttpResponse.json({ id: 1, username: 'u', email: 'a@example.org', orcid: '',
        displayName: 'U', admin: false, state: 'PENDING' });
    }),
  );
  render(<PendingApprovalPage state="PENDING" />);
  await userEvent.type(await screen.findByLabelText(/email/i), 'a@example.org');
  await userEvent.type(screen.getByLabelText(/message/i), 'I curate beetles');
  await userEvent.click(screen.getByRole('button', { name: /submit/i }));
  await waitFor(() => expect(put).toEqual({ email: 'a@example.org', note: 'I curate beetles' }));
});

test('PENDING with an email shows the waiting screen with an edit affordance', async () => {
  server.use(me({ state: 'PENDING', email: 'a@example.org' }));
  render(<PendingApprovalPage state="PENDING" />);
  expect(await screen.findByRole('heading', { name: /awaiting approval/i })).toBeInTheDocument();
  expect(screen.queryByLabelText(/email/i)).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: /edit application/i }));
  expect(await screen.findByLabelText(/email/i)).toBeInTheDocument();
});
