import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import TreePage from './TreePage';

const animalia = {
  id: 1,
  scientificName: 'Animalia',
  authorship: null,
  rank: 'KINGDOM',
  status: 'ACCEPTED',
  ordinal: 1,
  childCount: 0,
};

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/tree" element={<TreePage />} />
    </Routes>,
    { route: '/projects/9/tree' },
  );
}

test('selecting a node shows its breadcrumb path and the detail placeholder', async () => {
  server.use(
    http.get('/api/projects/9/tree/roots', () => HttpResponse.json([animalia])),
    http.get('/api/projects/9/tree/path/1', () =>
      HttpResponse.json([{ id: 1, scientificName: 'Animalia', rank: 'KINGDOM' }]),
    ),
  );
  renderPage();

  expect(
    screen.getByText('Select a taxon in the tree to see its details.'),
  ).toBeInTheDocument();

  await userEvent.click(await screen.findByText('Animalia'));

  // Breadcrumb (path) and the placeholder both render for the selected node.
  await screen.findByText('Selected usage #1');
  const breadcrumbEntries = await screen.findAllByText('Animalia');
  expect(breadcrumbEntries.length).toBeGreaterThan(0);
});
