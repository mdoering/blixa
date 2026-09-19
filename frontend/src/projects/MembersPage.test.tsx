import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import MembersPage from './MembersPage';

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/members" element={<MembersPage />} />
    </Routes>,
    { route: '/projects/5/members' },
  );
}

test('owner can see the add form and members list', async () => {
  server.use(
    http.get('/api/projects/5', () =>
      HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'owner' }),
    ),
    http.get('/api/projects/5/members', () =>
      HttpResponse.json([{ userId: 1, username: 'boss', role: 'owner' }]),
    ),
  );
  renderPage();
  expect(await screen.findByText('boss')).toBeInTheDocument();
  expect(screen.getByPlaceholderText('username')).toBeInTheDocument();
});

test('non-owner sees a read-only list (no add form)', async () => {
  server.use(
    http.get('/api/projects/5', () =>
      HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'editor' }),
    ),
    http.get('/api/projects/5/members', () =>
      HttpResponse.json([{ userId: 1, username: 'boss', role: 'owner' }]),
    ),
  );
  renderPage();
  expect(await screen.findByText('boss')).toBeInTheDocument();
  expect(screen.queryByPlaceholderText('username')).not.toBeInTheDocument();
});

test('owner can add a member', async () => {
  let putBody: unknown = null;
  server.use(
    http.get('/api/projects/5', () =>
      HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'owner' }),
    ),
    http.get('/api/projects/5/members', () => HttpResponse.json([])),
    http.put('/api/projects/5/members', async ({ request }) => {
      putBody = await request.json();
      return new HttpResponse(null, { status: 200 });
    }),
  );
  renderPage();
  await screen.findByPlaceholderText('username');
  await userEvent.type(screen.getByPlaceholderText('username'), 'helper');
  await userEvent.click(screen.getByRole('button', { name: /add \/ update/i }));
  await waitFor(() => expect(putBody).toEqual({ username: 'helper', role: 'editor' }));
});

const JOIN_REQUEST = {
  id: 42,
  orcid: '0000-0002-1825-0097',
  name: 'Jane Doe',
  message: 'I would like to help.',
  createdAt: '2026-07-01T00:00:00Z',
};

test('owner sees pending join requests and can dismiss one', async () => {
  let dismissed = false;
  // The GET handler reflects the DELETE below (rather than always returning the same fixed list)
  // so the post-dismiss refetch (triggered by the mutation's onSuccess invalidation) actually shows
  // the request gone, exercising the real "dismiss -> refetch -> list updates" flow.
  let pending = [JOIN_REQUEST];
  server.use(
    http.get('/api/projects/5', () =>
      HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'owner' }),
    ),
    http.get('/api/projects/5/members', () => HttpResponse.json([])),
    http.get('/api/projects/5/join-requests', () => HttpResponse.json(pending)),
    http.delete('/api/projects/5/join-requests/42', () => {
      dismissed = true;
      pending = [];
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderPage();

  expect(await screen.findByText('0000-0002-1825-0097')).toBeInTheDocument();
  expect(screen.getByText('Jane Doe')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: /dismiss/i }));
  await waitFor(() => expect(dismissed).toBe(true));
  await waitFor(() => expect(screen.queryByText('0000-0002-1825-0097')).not.toBeInTheDocument());
});

test('non-owner sees no join-requests section', async () => {
  let requested = false;
  server.use(
    http.get('/api/projects/5', () =>
      HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'editor' }),
    ),
    http.get('/api/projects/5/members', () =>
      HttpResponse.json([{ userId: 1, username: 'boss', role: 'owner' }]),
    ),
    http.get('/api/projects/5/join-requests', () => {
      requested = true;
      return HttpResponse.json([JOIN_REQUEST]);
    }),
  );
  renderPage();

  expect(await screen.findByText('boss')).toBeInTheDocument();
  expect(screen.queryByText('Join requests')).not.toBeInTheDocument();
  expect(requested).toBe(false);
});

const INVITATION = {
  id: 9,
  email: 'late@example.org',
  role: 'viewer',
  message: null,
  invitedBy: 'boss',
  createdAt: '2026-07-01T00:00:00Z',
  expiresAt: '2026-07-31T00:00:00Z',
  expired: true,
  acceptUrl: 'http://localhost:5173/invite/tok9',
};

function ownerProject() {
  return [
    http.get('/api/projects/5', () => HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'owner' })),
    http.get('/api/projects/5/members', () => HttpResponse.json([])),
  ];
}

test('owner invites someone by email', async () => {
  let body: unknown = null;
  server.use(
    ...ownerProject(),
    http.post('/api/projects/5/invitations', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ ...INVITATION, id: 10, email: 'new@example.org', role: 'editor', expired: false },
        { status: 201 });
    }),
  );
  renderPage();
  await userEvent.click(await screen.findByRole('button', { name: /invite by email/i }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText(/email/i), 'new@example.org');
  await userEvent.type(within(dialog).getByLabelText(/message/i), 'Welcome aboard');
  await userEvent.click(within(dialog).getByRole('button', { name: /send invitation/i }));
  await waitFor(() =>
    expect(body).toEqual({ email: 'new@example.org', role: 'editor', message: 'Welcome aboard' }),
  );
});

test('invite modal rejects a malformed email', async () => {
  let posted = false;
  server.use(
    ...ownerProject(),
    http.post('/api/projects/5/invitations', () => {
      posted = true;
      return HttpResponse.json(INVITATION, { status: 201 });
    }),
  );
  renderPage();
  await userEvent.click(await screen.findByRole('button', { name: /invite by email/i }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText(/email/i), 'nope');
  await userEvent.click(within(dialog).getByRole('button', { name: /send invitation/i }));
  expect(await within(dialog).findByText(/valid email/i)).toBeInTheDocument();
  expect(posted).toBe(false);
});

test('owner sees pending invitations, can resend and revoke', async () => {
  let resent = false;
  let pending = [INVITATION];
  server.use(
    ...ownerProject(),
    http.get('/api/projects/5/invitations', () => HttpResponse.json(pending)),
    http.post('/api/projects/5/invitations/9/resend', () => {
      resent = true;
      return HttpResponse.json({ ...INVITATION, expired: false });
    }),
    http.delete('/api/projects/5/invitations/9', () => {
      pending = [];
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderPage();
  expect(await screen.findByText('late@example.org')).toBeInTheDocument();
  expect(screen.getByText('expired')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /copy link/i })).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: /resend/i }));
  await waitFor(() => expect(resent).toBe(true));

  await userEvent.click(screen.getByRole('button', { name: /revoke/i }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByRole('button', { name: /revoke/i }));
  await waitFor(() => expect(screen.queryByText('late@example.org')).not.toBeInTheDocument());
});

test('non-owner sees no invite button and never loads invitations', async () => {
  let requested = false;
  server.use(
    http.get('/api/projects/5', () => HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'editor' })),
    http.get('/api/projects/5/members', () => HttpResponse.json([{ userId: 1, username: 'boss', role: 'owner' }])),
    http.get('/api/projects/5/invitations', () => {
      requested = true;
      return HttpResponse.json([INVITATION]);
    }),
  );
  renderPage();
  expect(await screen.findByText('boss')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /invite by email/i })).not.toBeInTheDocument();
  expect(requested).toBe(false);
});
