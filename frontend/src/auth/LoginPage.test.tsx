import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import LoginPage from './LoginPage';

test('shows the ORCID button when ORCID is configured', async () => {
  server.use(http.get('/api/config', () => HttpResponse.json({ orcidEnabled: true })));
  renderWithProviders(<LoginPage />);
  expect(await screen.findByRole('link', { name: /sign in with orcid/i })).toHaveAttribute(
    'href',
    '/oauth2/authorization/orcid',
  );
  expect(screen.queryByLabelText(/username/i)).not.toBeInTheDocument();
});

test('shows the local login form when ORCID is not configured', async () => {
  server.use(http.get('/api/config', () => HttpResponse.json({ orcidEnabled: false })));
  renderWithProviders(<LoginPage />);
  expect(await screen.findByLabelText(/username/i)).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /sign in with orcid/i })).not.toBeInTheDocument();
});

test('shows an error when local login is rejected', async () => {
  server.use(
    http.get('/api/config', () => HttpResponse.json({ orcidEnabled: false })),
    http.post('/api/auth/login', () => new HttpResponse(null, { status: 401 })),
  );
  renderWithProviders(<LoginPage />);
  await userEvent.type(await screen.findByLabelText(/username/i), 'alice');
  await userEvent.type(screen.getByLabelText(/password/i), 'wrong');
  await userEvent.click(screen.getByRole('button', { name: /^sign in$/i }));
  await waitFor(() =>
    expect(screen.getByText(/invalid username or password/i)).toBeInTheDocument(),
  );
});
