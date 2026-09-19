import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { render, screen } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import RequireAuth from './RequireAuth';

const me = (state: string) =>
  http.get('/api/me', () =>
    HttpResponse.json({ id: 1, username: 'u', email: '', orcid: '', displayName: 'U', admin: false, state }),
  );

function renderAt(route: string) {
  return render(
    <Routes>
      <Route element={<RequireAuth />}>
        <Route path="/projects" element={<div>PROJECTS</div>} />
      </Route>
      <Route path="/invite/:token" element={<div>INVITE PAGE</div>} />
    </Routes>,
    { route },
  );
}

test('a stored invitation resumes before the PENDING approval gate', async () => {
  localStorage.setItem('blixa.pendingInvite', 'tok1');
  server.use(me('PENDING'));
  renderAt('/projects');
  expect(await screen.findByText('INVITE PAGE')).toBeInTheDocument();
});

test('a stored invitation also resumes for an ACTIVE user', async () => {
  localStorage.setItem('blixa.pendingInvite', 'tok1');
  server.use(me('ACTIVE'));
  renderAt('/projects');
  expect(await screen.findByText('INVITE PAGE')).toBeInTheDocument();
});

test('without a stored invitation the protected route renders', async () => {
  server.use(me('ACTIVE'));
  renderAt('/projects');
  expect(await screen.findByText('PROJECTS')).toBeInTheDocument();
});
