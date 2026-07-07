import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import LoginPage from './LoginPage';

test('ORCID button links to the Spring OIDC authorization endpoint', () => {
  renderWithProviders(<LoginPage />);
  expect(screen.getByRole('link', { name: /sign in with orcid/i })).toHaveAttribute(
    'href',
    '/oauth2/authorization/orcid',
  );
});

test('shows an error when local login is rejected', async () => {
  server.use(http.post('/api/auth/login', () => new HttpResponse(null, { status: 401 })));
  renderWithProviders(<LoginPage />);
  await userEvent.type(screen.getByLabelText(/username/i), 'alice');
  await userEvent.type(screen.getByLabelText(/password/i), 'wrong');
  await userEvent.click(screen.getByRole('button', { name: /^sign in$/i }));
  await waitFor(() =>
    expect(screen.getByText(/invalid username or password/i)).toBeInTheDocument(),
  );
});
