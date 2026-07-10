import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../../test/utils';
import { server, http, HttpResponse } from '../../test/server';
import MatchColModal from './MatchColModal';

function baseUsage(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 10,
    parentId: 1,
    alternativeId: ['tsn:1'],
    status: 'ACCEPTED',
    scientificName: 'Panthera leo',
    authorship: 'Linnaeus, 1758',
    rank: 'species',
    version: 3,
    ...overrides,
  };
}

function mockUsage(overrides: Partial<Record<string, unknown>> = {}) {
  server.use(
    http.get('/api/projects/4/usages/10', () => HttpResponse.json(baseUsage(overrides))),
  );
}

function mockCandidates(candidates: unknown[]) {
  server.use(
    http.get('/api/projects/4/usages/10/col-match', () => HttpResponse.json(candidates)),
  );
}

const candidate1 = {
  colId: '6W3C4',
  name: 'Panthera leo',
  authorship: 'Linnaeus, 1758',
  rank: 'species',
  status: 'accepted',
  matchType: 'EXACT',
  classification: 'Animalia > Chordata > Mammalia > Carnivora > Felidae > Panthera',
};

const candidate2 = {
  colId: 'OTHER1',
  name: 'Panthera leo persica',
  authorship: 'Meyer, 1826',
  rank: 'subspecies',
  status: 'synonym',
  matchType: 'ALTERNATIVE',
  classification: 'Animalia > Chordata > Mammalia > Carnivora > Felidae > Panthera',
};

test('picks the first candidate and PUTs merged identifiers, preserving prior non-col ids', async () => {
  mockUsage();
  mockCandidates([candidate1, candidate2]);
  let putBody: Record<string, unknown> | undefined;
  server.use(
    http.put('/api/projects/4/usages/10/identifiers', async ({ request }) => {
      putBody = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(baseUsage({ alternativeId: ['tsn:1', 'col:6W3C4'], version: 4 }));
    }),
  );
  const onClose = vi.fn();
  renderWithProviders(<MatchColModal pid={4} usageId={10} opened onClose={onClose} />);

  // Both candidates render with name/authorship/rank/status/matchType and dimmed classification.
  await screen.findByText(/Panthera leo persica/);
  expect(screen.getByText(/EXACT/)).toBeInTheDocument();
  expect(screen.getByText(/ALTERNATIVE/)).toBeInTheDocument();
  expect(
    screen.getAllByText(/Animalia > Chordata > Mammalia > Carnivora > Felidae > Panthera/).length,
  ).toBeGreaterThan(0);

  // First candidate is preselected; explicitly (re)pick it, then confirm.
  const radios = screen.getAllByRole('radio');
  expect(radios[0]).toBeChecked();
  await userEvent.click(radios[0]);

  const useBtn = screen.getByRole('button', { name: 'Use this' });
  await waitFor(() => expect(useBtn).toBeEnabled());
  await userEvent.click(useBtn);

  await waitFor(() => expect(putBody).toBeDefined());
  expect(putBody?.alternativeId).toContain('col:6W3C4');
  expect(putBody?.alternativeId).toContain('tsn:1');
  expect(putBody?.version).toBe(3);
  await waitFor(() => expect(onClose).toHaveBeenCalled());
});

test('no candidates shows "No COL match found" and does not PUT', async () => {
  mockUsage();
  mockCandidates([]);
  let putCalls = 0;
  server.use(
    http.put('/api/projects/4/usages/10/identifiers', () => {
      putCalls += 1;
      return new HttpResponse(null, { status: 200 });
    }),
  );
  renderWithProviders(<MatchColModal pid={4} usageId={10} opened onClose={() => {}} />);

  await screen.findByText('No COL match found.');
  expect(screen.queryAllByRole('radio')).toHaveLength(0);
  expect(screen.queryByRole('button', { name: 'Use this' })).not.toBeInTheDocument();
  expect(putCalls).toBe(0);
});
