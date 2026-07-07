import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ProjectMetadataPage from './ProjectMetadataPage';

const project = {
  id: 3, slug: 'mam', title: 'Mammals', alias: null, description: null, nomCode: 'zoological',
  license: null, version: null, issued: null, geographicScope: null, taxonomicScope: null, doi: null, role: 'owner',
};

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/metadata" element={<ProjectMetadataPage />} />
    </Routes>,
    { route: '/projects/3/metadata' },
  );
}

test('prefills the form and saves updated metadata', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
    http.put('/api/projects/3/metadata', async ({ request }) => {
      const body = (await request.json()) as { title: string };
      return HttpResponse.json({ ...project, title: body.title });
    }),
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));
  await userEvent.clear(title);
  await userEvent.type(title, 'Mammalia');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));
  await waitFor(() => expect(screen.getByText('Saved')).toBeInTheDocument());
});

test('viewer role sees a disabled Save button', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json({ ...project, role: 'viewer' })),
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  // Wait until the fetched role=viewer project has populated the form, so the
  // assertion reflects the loaded role (canEdit=false because viewer ∉ owner/editor),
  // not the pre-fetch `data === undefined` default.
  await waitFor(() => expect(title).toHaveValue('Mammals'));
  expect(screen.getByRole('button', { name: /save/i })).toBeDisabled();
});
