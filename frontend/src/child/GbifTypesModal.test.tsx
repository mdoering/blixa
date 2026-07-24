import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { notifications } from '@mantine/notifications';
import { afterEach, expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import GbifTypesModal from './GbifTypesModal';

afterEach(() => notifications.clean());

const twoCandidates = {
  name: 'Panthera leo',
  colId: 'TESTCOL',
  truncated: false,
  candidates: [
    {
      citation: 'Panthera leo', status: 'Holotype', institutionCode: 'AMNH', catalogNumber: 'M-1',
      occurrenceId: 'urn:1', locality: null, country: 'Congo', collector: 'Lang', date: '1912',
      sex: null, link: 'https://www.gbif.org/occurrence/111', latitude: null, longitude: null,
      alreadyImported: true,
    },
    {
      citation: 'Panthera leo azandica', status: 'Paratype', institutionCode: 'BMNH',
      catalogNumber: 'M-2', occurrenceId: 'urn:2', locality: null, country: null, collector: null,
      date: null, sex: null, link: null, latitude: null, longitude: null, alreadyImported: false,
    },
  ],
};

test('lists candidates, disables already-imported, and imports the ticked one', async () => {
  const posted: Record<string, unknown>[] = [];
  server.use(
    http.get('/api/projects/4/usages/10/gbif-types', () => HttpResponse.json(twoCandidates)),
    http.post('/api/projects/4/usages/10/type-material', async ({ request }) => {
      posted.push((await request.json()) as Record<string, unknown>);
      return HttpResponse.json({ id: 99 });
    }),
  );
  renderWithProviders(<GbifTypesModal pid={4} usageId={10} opened onClose={() => {}} />);

  expect(await screen.findByText('Panthera leo azandica')).toBeInTheDocument();
  // the already-imported holotype's checkbox is disabled
  expect(screen.getByRole('checkbox', { name: /Select Holotype M-1/ })).toBeDisabled();

  // tick the paratype and import it
  await userEvent.click(screen.getByRole('checkbox', { name: /Select Paratype M-2/ }));
  await userEvent.click(screen.getByRole('button', { name: /Import 1/ }));

  await waitFor(() => expect(posted).toHaveLength(1));
  expect(posted[0].occurrenceId).toBe('urn:2');
  expect(posted[0].status).toBe('Paratype');
});

test('shows a "no COL match" message when the name is unresolved', async () => {
  server.use(
    http.get('/api/projects/4/usages/10/gbif-types', () =>
      HttpResponse.json({ name: 'Foo bar', colId: null, truncated: false, candidates: [] }),
    ),
  );
  renderWithProviders(<GbifTypesModal pid={4} usageId={10} opened onClose={() => {}} />);
  expect(await screen.findByText(/No COL match/)).toBeInTheDocument();
});
