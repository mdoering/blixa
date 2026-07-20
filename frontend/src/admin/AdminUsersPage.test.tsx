import { beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import AdminUsersPage from './AdminUsersPage';

const USERS = [
  { id: 1, username: 'me-admin', orcid: '0000-0001-0000-0001', displayName: 'Me Admin', state: 'ACTIVE', admin: true },
  { id: 2, username: '0000-0003-1111-2222', orcid: '0000-0003-1111-2222', displayName: 'Pending Person', state: 'PENDING', admin: false },
  { id: 3, username: 'active-user', orcid: null, displayName: 'Active User', state: 'ACTIVE', admin: false },
];

describe('AdminUsersPage', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/me', () => HttpResponse.json(USERS[0])),
      http.get('/api/admin/users', () => HttpResponse.json(USERS)),
    );
  });

  it('lists users with their state', async () => {
    render(<AdminUsersPage />);
    expect(await screen.findByText('Pending Person')).toBeInTheDocument();
    expect(screen.getByText('PENDING')).toBeInTheDocument();
    expect(screen.getByText('Active User')).toBeInTheDocument();
  });

  it('approves a pending user', async () => {
    let posted: unknown = null;
    server.use(
      http.post('/api/admin/users/2/state', async ({ request }) => {
        posted = await request.json();
        return HttpResponse.json({ ...USERS[1], state: 'ACTIVE' });
      }),
    );
    render(<AdminUsersPage />);
    const approve = await screen.findByRole('button', { name: /approve/i });
    await userEvent.click(approve);
    await waitFor(() => expect(posted).toEqual({ state: 'ACTIVE' }));
  });

  it('toggles a user to admin', async () => {
    let posted: unknown = null;
    server.use(
      http.post('/api/admin/users/3/admin', async ({ request }) => {
        posted = await request.json();
        return HttpResponse.json({ ...USERS[2], admin: true });
      }),
    );
    render(<AdminUsersPage />);
    const toggle = await screen.findByRole('switch', { name: /admin-active-user/i });
    await userEvent.click(toggle);
    await waitFor(() => expect(posted).toEqual({ admin: true }));
  });
});
