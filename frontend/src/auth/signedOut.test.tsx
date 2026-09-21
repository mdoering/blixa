import { screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import { orcidLoginUrl } from '../api/auth';
import RequireAuth from './RequireAuth';
import { isSignedOut, markSignedOut } from './signedOut';

afterEach(() => localStorage.clear());

test('after an explicit sign-out the ORCID sign-in forces re-authentication', () => {
  expect(orcidLoginUrl()).toBe('/oauth2/authorization/orcid');
  markSignedOut();
  expect(orcidLoginUrl()).toBe('/oauth2/authorization/orcid?prompt=login');
});

test('signing in again clears the signed-out flag', async () => {
  markSignedOut();
  server.use(
    http.get('/api/me', () => HttpResponse.json({ id: 1, username: 'alice', state: 'ACTIVE' })),
  );
  renderWithProviders(
    <Routes>
      <Route element={<RequireAuth />}>
        <Route path="/projects" element={<div>APP</div>} />
      </Route>
    </Routes>,
    { route: '/projects' },
  );
  expect(await screen.findByText('APP')).toBeInTheDocument();
  await waitFor(() => expect(isSignedOut()).toBe(false));
});
