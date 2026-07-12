import { screen, waitFor } from '@testing-library/react';
import { expect, test } from 'vitest';
import { Route, Routes, useLocation } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import PublicProjectPage from './PublicProjectPage';

function LocationEcho() {
  return <div data-testid="loc">{useLocation().pathname}</div>;
}

function renderAt(route: string) {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/p/:idOrAlias" element={<PublicProjectPage />} />
      </Routes>
      <LocationEcho />
    </>,
    { route },
  );
}

const PROJECT = {
  id: 5,
  title: 'World Ferns',
  alias: 'ferns',
  description: 'A checklist of world ferns',
  license: 'CC0-1.0',
  nomCode: 'botanical',
  geographicScope: 'Global',
  taxonomicScope: 'Pteridophyta',
  contributors: [{ name: 'Jane', orcid: '0000-0001-2345-6789', role: 'owner' }],
  metrics: {
    acceptedByRank: { SPECIES: 120, GENUS: 30 },
    synonymsByRank: { SPECIES: 15 },
    supplementary: { vernacular: 5, distribution: 2 },
    changesSinceLastRelease: 7,
    contributions: [{ userId: 1, name: 'Jane', orcid: '0000-0001-2345-6789', count: 4 }],
  },
  releases: [
    {
      id: 9,
      version: '1.0',
      notes: null,
      createdAt: '2026-07-01T00:00:00Z',
      fileName: 'world-ferns-1.0.zip',
      fileSize: 20480,
      nameUsageCount: 150,
      metrics: null,
      downloadUrl: '/api/public/projects/5/releases/9/download',
    },
  ],
};

test('renders a public project: title, contributor, release version, and a download link', async () => {
  server.use(http.get('/api/public/projects/5', () => HttpResponse.json(PROJECT)));
  renderAt('/p/5');

  expect(await screen.findByText('World Ferns')).toBeInTheDocument();
  // "Jane" appears twice: once as the contributor, once in the metrics contributions table.
  expect(screen.getAllByText('Jane').length).toBeGreaterThan(0);
  expect(screen.getByText('1.0')).toBeInTheDocument();

  const downloadLink = screen.getByRole('link', { name: /download/i });
  expect(downloadLink.getAttribute('href')).toContain('/api/public/projects/5/releases/9/download');
});

test('shows a friendly message when the project is private or does not exist (404)', async () => {
  server.use(http.get('/api/public/projects/999', () => new HttpResponse(null, { status: 404 })));
  renderAt('/p/999');

  expect(
    await screen.findByText(/this project is not public or does not exist/i),
  ).toBeInTheDocument();
});

test('redirects /p/{alias} to the canonical /p/{id}', async () => {
  server.use(
    http.get('/api/public/projects/ferns', () => HttpResponse.json(PROJECT)),
    http.get('/api/public/projects/5', () => HttpResponse.json(PROJECT)),
  );
  renderAt('/p/ferns');

  await waitFor(() => expect(screen.getByTestId('loc')).toHaveTextContent('/p/5'));
});
