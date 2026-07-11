import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import AppLayout from './AppLayout';

function renderShell(route: string) {
  server.use(
    http.get('/api/me', () =>
      HttpResponse.json({ id: 1, username: 'alice', orcid: '', displayName: 'Alice' }),
    ),
    http.get('/api/projects/3', () =>
      HttpResponse.json({ id: 3, title: 'Felidae', role: 'owner' }),
    ),
  );
  return renderWithProviders(
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/projects/:projectId/tree" element={<div>TREE PAGE</div>} />
      </Route>
    </Routes>,
    { route },
  );
}

test('renders brand, current project name, footer, and the project section nav', async () => {
  renderShell('/projects/3/tree');
  // Brand mark in the header (and the footer icon) — both render the accessible Blixa logo.
  expect((await screen.findAllByRole('img', { name: 'Blixa' })).length).toBeGreaterThanOrEqual(1);
  expect(await screen.findByText('Felidae')).toBeInTheDocument(); // current project (read-only)
  expect(screen.getByText(new RegExp(`v${__APP_VERSION__}`))).toBeInTheDocument(); // footer
  expect(screen.getByText('TREE PAGE')).toBeInTheDocument(); // Outlet
  expect(screen.getByText('Names')).toBeInTheDocument(); // sidebar section
});

test('the desktop collapse toggle hides the sidebar labels', async () => {
  renderShell('/projects/3/tree');
  expect(await screen.findByText('Names')).toBeInTheDocument();
  await userEvent.click(screen.getByLabelText('Collapse navigation'));
  expect(screen.queryByText('Names')).not.toBeInTheDocument();
});
