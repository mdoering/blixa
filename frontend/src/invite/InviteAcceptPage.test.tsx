import { expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { render, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import InviteAcceptPage from './InviteAcceptPage';

const KEY = 'blixa.pendingInvite';

const preview = (status: string) =>
  http.get('/api/public/invitations/tok1', () =>
    HttpResponse.json({ projectTitle: 'Beetles', invitedBy: 'Olga Owner', role: 'editor',
      message: 'Please help with the weevils.', status }),
  );

const signedIn = http.get('/api/me', () =>
  HttpResponse.json({ id: 3, username: '0000-0002-9999-0201', email: '', orcid: '0000-0002-9999-0201',
    displayName: 'Ina', admin: false, state: 'PENDING' }),
);

function renderPage() {
  return render(
    <Routes>
      <Route path="/invite/:token" element={<InviteAcceptPage />} />
      <Route path="/projects/:pid" element={<div>PROJECT PAGE</div>} />
    </Routes>,
    { route: '/invite/tok1' },
  );
}

test('signed out: shows the invitation, remembers the token and offers ORCID sign-in', async () => {
  server.use(preview('VALID')); // default /api/me is 401
  renderPage();
  expect(await screen.findByText(/join “Beetles”/i)).toBeInTheDocument();
  expect(screen.getByText(/Olga Owner invited you to join as an editor/i)).toBeInTheDocument();
  expect(screen.getByText('Please help with the weevils.')).toBeInTheDocument();
  const link = await screen.findByRole('link', { name: /sign in with orcid to accept/i });
  expect(link).toHaveAttribute('href', '/oauth2/authorization/orcid');
  await waitFor(() => expect(localStorage.getItem(KEY)).toBe('tok1'));
});

test('signed in: clears the stored token, accepts and opens the project', async () => {
  localStorage.setItem(KEY, 'tok1');
  let accepted = false;
  server.use(
    preview('VALID'),
    signedIn,
    http.post('/api/invitations/tok1/accept', () => {
      accepted = true;
      return HttpResponse.json({ projectId: 7 });
    }),
  );
  renderPage();
  const button = await screen.findByRole('button', { name: /accept invitation/i });
  await waitFor(() => expect(localStorage.getItem(KEY)).toBeNull());
  await userEvent.click(button);
  await waitFor(() => expect(accepted).toBe(true));
  expect(await screen.findByText('PROJECT PAGE')).toBeInTheDocument();
});

test('signed in: shows the server error when accepting fails', async () => {
  server.use(
    preview('VALID'),
    signedIn,
    http.post('/api/invitations/tok1/accept', () =>
      HttpResponse.json({ error: 'account disabled' }, { status: 403 })),
  );
  renderPage();
  await userEvent.click(await screen.findByRole('button', { name: /accept invitation/i }));
  expect(await screen.findByText('account disabled')).toBeInTheDocument();
});

test('expired: explains and offers no action, and forgets the stored token', async () => {
  localStorage.setItem(KEY, 'tok1');
  server.use(preview('EXPIRED'));
  renderPage();
  expect(await screen.findByText(/this invitation has expired/i)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /accept/i })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /sign in/i })).not.toBeInTheDocument();
  await waitFor(() => expect(localStorage.getItem(KEY)).toBeNull());
});

test('already used', async () => {
  server.use(preview('ACCEPTED'));
  renderPage();
  expect(await screen.findByText(/already been used/i)).toBeInTheDocument();
});

test('unknown link', async () => {
  server.use(
    http.get('/api/public/invitations/tok1', () =>
      HttpResponse.json({ error: 'invitation not found' }, { status: 404 })),
  );
  renderPage();
  expect(await screen.findByText(/not valid/i)).toBeInTheDocument();
});
