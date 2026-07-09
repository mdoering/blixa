import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ProjectLayout from './ProjectLayout';

function renderAt(route: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId" element={<ProjectLayout />}>
        <Route index element={<div>SECTION CONTENT</div>} />
      </Route>
    </Routes>,
    { route },
  );
}

test('renders the child section once the project loads', async () => {
  server.use(
    http.get('/api/projects/3', () =>
      HttpResponse.json({ id: 3, title: 'Felidae', role: 'owner' }),
    ),
  );
  renderAt('/projects/3');
  expect(await screen.findByText('SECTION CONTENT')).toBeInTheDocument();
  // No tab strip any more — navigation lives in the sidebar.
  expect(screen.queryByRole('tab')).not.toBeInTheDocument();
});

test('shows a not-found alert when the project 404s', async () => {
  server.use(http.get('/api/projects/999', () => new HttpResponse(null, { status: 404 })));
  renderAt('/projects/999');
  expect(await screen.findByText('Project not found')).toBeInTheDocument();
});
