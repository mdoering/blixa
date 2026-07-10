import { screen } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../../test/utils';
import { server, http, HttpResponse } from '../../test/server';
import DistributionMapPanel from './DistributionMapPanel';

// Stub the maplibre-touching child so no WebGL / real maplibre runs in jsdom.
vi.mock('./MapView', () => ({
  default: () => <div data-testid="map-view-stub" />,
}));

const project = {
  id: 4,
  title: 'Mammals',
  alias: null,
  description: null,
  nomCode: null,
  license: null,
  geographicScope: null,
  taxonomicScope: null,
  role: 'owner',
  gbifOccurrenceLayer: true,
};

function mockProject() {
  server.use(http.get('/api/projects/4', () => HttpResponse.json(project)));
}

function mockMap(colId: string | null) {
  server.use(
    http.get('/api/projects/4/usages/10/map', () =>
      HttpResponse.json({
        colId,
        distributions: [
          { usageId: 10, name: 'Panthera leo', focal: true, gazetteer: 'tdwg', areaId: 'AB', area: null },
          { usageId: 10, name: 'Somewhere', focal: true, gazetteer: null, areaId: null, area: 'Free text land' },
        ],
        typeSpecimens: [],
      }),
    ),
  );
}

function mockGbifCount(count: number) {
  server.use(
    http.get('https://api.gbif.org/v1/occurrence/search', () => HttpResponse.json({ count })),
  );
}

test('renders the layer checkboxes with children layers unchecked by default', async () => {
  mockProject();
  mockMap('6W3C4');
  mockGbifCount(1234);
  renderWithProviders(<DistributionMapPanel pid={4} usageId={10} canEdit />);

  const focal = await screen.findByLabelText('Distribution (focal)');
  expect(focal).toBeChecked();
  expect(screen.getByLabelText('Type specimens (focal)')).toBeChecked();
  expect(screen.getByLabelText('Distribution (children)')).not.toBeChecked();
  expect(screen.getByLabelText('Type specimens (children)')).not.toBeChecked();
  // GBIF checkbox shows once matched to COL, and is checked by default (gbifOccurrenceLayer &&
  // colId), and enabled since the preflight count confirms occurrences exist.
  const gbif = await screen.findByLabelText('GBIF occurrences');
  expect(gbif).toBeChecked();
  expect(gbif).not.toBeDisabled();
  // The lazy MapView stub mounted.
  expect(await screen.findByTestId('map-view-stub')).toBeInTheDocument();
  // Free-text-only area listed as not mappable.
  expect(screen.getByText(/not mappable/i)).toBeInTheDocument();
});

test('greys out the GBIF checkbox when the preflight count confirms zero occurrences', async () => {
  mockProject();
  mockMap('6W3C4');
  mockGbifCount(0);
  renderWithProviders(<DistributionMapPanel pid={4} usageId={10} canEdit />);

  await screen.findByLabelText('Distribution (focal)');
  const gbif = await screen.findByLabelText('GBIF occurrences (none)');
  expect(gbif).toBeDisabled();
  expect(gbif).not.toBeChecked();
  // No plain (enabled) "GBIF occurrences" checkbox alongside the disabled one.
  expect(screen.queryByLabelText('GBIF occurrences')).not.toBeInTheDocument();
  // Other layers unaffected.
  expect(screen.getByLabelText('Distribution (focal)')).toBeChecked();
  expect(screen.getByLabelText('Type specimens (focal)')).toBeChecked();
});

test('shows the Match to COL button when the usage has no colId', async () => {
  mockProject();
  mockMap(null);
  // Deliberately no mockGbifCount(): the count query is `enabled: !!colId`, so with colId null it
  // must never fire. The test server errors on unhandled requests (see test/setup.ts), so this
  // test would fail on its own if the preflight query fired despite no colId.
  renderWithProviders(<DistributionMapPanel pid={4} usageId={10} canEdit />);

  await screen.findByText(/not matched to col yet/i);
  expect(screen.getByRole('button', { name: /match to col/i })).toBeInTheDocument();
  // No GBIF checkbox without a COL match.
  expect(screen.queryByLabelText('GBIF occurrences')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('GBIF occurrences (none)')).not.toBeInTheDocument();
});
