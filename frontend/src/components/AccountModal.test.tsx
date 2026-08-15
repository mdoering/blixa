import { expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { render, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import AccountModal from './AccountModal';

test('prefills the email and saves an edited email', async () => {
  server.use(
    http.get('/api/me', () =>
      HttpResponse.json({ id: 1, username: 'alice', email: 'old@example.org', orcid: '',
        displayName: 'Alice', admin: false, state: 'ACTIVE' }),
    ),
  );
  let put: unknown = null;
  server.use(
    http.put('/api/me/email', async ({ request }) => {
      put = await request.json();
      return HttpResponse.json({ id: 1, username: 'alice', email: 'new@example.org', orcid: '',
        displayName: 'Alice', admin: false, state: 'ACTIVE' });
    }),
  );

  render(<AccountModal opened onClose={() => {}} />);
  const emailInput = await screen.findByLabelText(/email/i);
  await waitFor(() => expect(emailInput).toHaveValue('old@example.org'));

  await userEvent.clear(emailInput);
  await userEvent.type(emailInput, 'new@example.org');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));

  await waitFor(() => expect(put).toEqual({ email: 'new@example.org' }));
});
