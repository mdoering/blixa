import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { render, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import SignInRedirect from './SignInRedirect';

let assign: ReturnType<typeof vi.fn>;
let realLocation: Location;

// window.location.assign is non-configurable in jsdom, so swap the whole location object — but keep
// the URL props (origin/href/…) so MSW can still resolve relative request URLs like /api/config.
beforeEach(() => {
  realLocation = window.location;
  assign = vi.fn();
  const stub: Record<string, unknown> = { assign, replace: vi.fn(), reload: vi.fn() };
  for (const p of ['href', 'origin', 'protocol', 'host', 'hostname', 'port', 'pathname', 'search', 'hash']) {
    stub[p] = (realLocation as unknown as Record<string, unknown>)[p];
  }
  Object.defineProperty(window, 'location', { configurable: true, value: stub });
});

afterEach(() => {
  Object.defineProperty(window, 'location', { configurable: true, value: realLocation });
});

test('redirects straight to ORCID when ORCID is enabled', async () => {
  server.use(http.get('/api/config', () => HttpResponse.json({ orcidEnabled: true })));
  render(<SignInRedirect />);
  await waitFor(() => expect(assign).toHaveBeenCalledWith('/oauth2/authorization/orcid'));
});

test('navigates to the local /signin page when ORCID is disabled', async () => {
  server.use(http.get('/api/config', () => HttpResponse.json({ orcidEnabled: false })));
  render(
    <Routes>
      <Route path="/" element={<SignInRedirect />} />
      <Route path="/signin" element={<div>LOCAL SIGN IN</div>} />
    </Routes>,
    { route: '/' },
  );
  expect(await screen.findByText('LOCAL SIGN IN')).toBeInTheDocument();
  expect(assign).not.toHaveBeenCalled();
});
