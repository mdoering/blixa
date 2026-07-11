import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import MergeMappingTables from './MergeMappingTables';

// Three name candidates spanning MATCHED/POSSIBLE_FUZZY/NEW, and two reference candidates
// spanning MATCHED/NEW -- enough to exercise every category-dependent action (Confirm/Reject/
// Re-point) on both tabs.
const nameRows = [
  {
    sourceId: 'n1',
    category: 'MATCHED',
    targetId: 't1',
    score: 1.0,
    sourceLabel: 'Aus bus Linnaeus, 1758 (species)',
    targetLabel: 'Aus bus (L.) (species)',
  },
  {
    sourceId: 'n2',
    category: 'POSSIBLE_FUZZY',
    targetId: 't2',
    score: 0.85,
    sourceLabel: 'Cus dus Miller (species)',
    targetLabel: 'Cus dax (species)',
  },
  {
    sourceId: 'n3',
    category: 'NEW',
    targetId: null,
    score: null,
    sourceLabel: 'Eus fus (species)',
    targetLabel: null,
  },
];

const refRows = [
  {
    sourceId: 'r1',
    category: 'MATCHED',
    targetId: 'rt1',
    score: 1.0,
    sourceLabel: 'Smith, J. 1990. A revision.',
    targetLabel: 'Smith, J. 1990. A revision (reprint).',
  },
  {
    sourceId: 'r2',
    category: 'NEW',
    targetId: null,
    score: null,
    sourceLabel: 'Jones, K. 2001. Another paper.',
    targetLabel: null,
  },
];

// GET /api/projects/5/merge/100/mapping?entity=&category=&page=&size= -- filters the fixture rows
// above by `entity` and (if present) `category`, same contract as the real MergeService.getMapping.
function mappingHandler() {
  return http.get('/api/projects/5/merge/100/mapping', ({ request }) => {
    const url = new URL(request.url);
    const entity = url.searchParams.get('entity');
    const category = url.searchParams.get('category');
    const rows = entity === 'name' ? nameRows : refRows;
    const filtered = category ? rows.filter((r) => r.category === category) : rows;
    return HttpResponse.json(filtered);
  });
}

test('both tabs render categorized rows from getMergeMapping, and the References tab shows reference rows', async () => {
  server.use(mappingHandler());
  renderWithProviders(<MergeMappingTables targetId={5} runId={100} />);

  // Names tab is active by default.
  expect(await screen.findByText('Aus bus Linnaeus, 1758 (species)')).toBeInTheDocument();
  expect(screen.getByText('Cus dus Miller (species)')).toBeInTheDocument();
  expect(screen.getByText('Eus fus (species)')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('tab', { name: 'References' }));
  expect(await screen.findByText('Smith, J. 1990. A revision.')).toBeInTheDocument();
  expect(screen.getByText('Jones, K. 2001. Another paper.')).toBeInTheDocument();
  expect(screen.queryByText('Aus bus Linnaeus, 1758 (species)')).not.toBeInTheDocument();
});

test('selecting a category chip refetches the Names tab with that category', async () => {
  server.use(mappingHandler());
  renderWithProviders(<MergeMappingTables targetId={5} runId={100} />);

  expect(await screen.findByText('Eus fus (species)')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('radio', { name: 'Matched' }));

  await waitFor(() => expect(screen.queryByText('Eus fus (species)')).not.toBeInTheDocument());
  expect(screen.queryByText('Cus dus Miller (species)')).not.toBeInTheDocument();
  expect(screen.getByText('Aus bus Linnaeus, 1758 (species)')).toBeInTheDocument();
});

test('rejecting a MATCHED name queues + saves a NEW override with targetId cleared', async () => {
  let overrideBody: unknown = null;
  server.use(
    mappingHandler(),
    http.put('/api/projects/5/merge/100/overrides', async ({ request }) => {
      overrideBody = await request.json();
      return HttpResponse.json({});
    }),
  );
  renderWithProviders(<MergeMappingTables targetId={5} runId={100} />);

  const matchedRow = (await screen.findByText('Aus bus Linnaeus, 1758 (species)')).closest('tr');
  expect(matchedRow).not.toBeNull();
  await userEvent.click(within(matchedRow as HTMLElement).getByRole('button', { name: 'Reject' }));

  expect(screen.getByText('1 pending edit')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Save overrides' }));

  await waitFor(() =>
    expect(overrideBody).toEqual([{ entity: 'name', sourceId: 'n1', category: 'NEW', targetId: null }]),
  );
});

test('confirming a POSSIBLE_FUZZY name queues + saves a MATCHED override with the suggested targetId', async () => {
  let overrideBody: unknown = null;
  server.use(
    mappingHandler(),
    http.put('/api/projects/5/merge/100/overrides', async ({ request }) => {
      overrideBody = await request.json();
      return HttpResponse.json({});
    }),
  );
  renderWithProviders(<MergeMappingTables targetId={5} runId={100} />);

  const possibleRow = (await screen.findByText('Cus dus Miller (species)')).closest('tr');
  expect(possibleRow).not.toBeNull();
  await userEvent.click(within(possibleRow as HTMLElement).getByRole('button', { name: 'Confirm' }));

  expect(screen.getByText('1 pending edit')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Save overrides' }));

  await waitFor(() =>
    expect(overrideBody).toEqual([{ entity: 'name', sourceId: 'n2', category: 'MATCHED', targetId: 't2' }]),
  );
});

test('re-pointing a name to a different target picked from the target-usage search queues that targetId', async () => {
  let overrideBody: unknown = null;
  server.use(
    mappingHandler(),
    http.get('/api/projects/5/usages', () =>
      HttpResponse.json({
        items: [
          { id: 99, scientificName: 'Zus zus', authorship: '(Ives)', rank: 'species' },
        ],
        total: 1,
      }),
    ),
    http.put('/api/projects/5/merge/100/overrides', async ({ request }) => {
      overrideBody = await request.json();
      return HttpResponse.json({});
    }),
  );
  renderWithProviders(<MergeMappingTables targetId={5} runId={100} />);

  const newRow = (await screen.findByText('Eus fus (species)')).closest('tr');
  expect(newRow).not.toBeNull();
  await userEvent.click(within(newRow as HTMLElement).getByRole('button', { name: 'Re-point' }));

  expect(await screen.findByText('Zus zus (Ives) (species)')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Zus zus (Ives) (species)' }));

  expect(screen.getByText('1 pending edit')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Save overrides' }));

  await waitFor(() =>
    expect(overrideBody).toEqual([{ entity: 'name', sourceId: 'n3', category: 'MATCHED', targetId: '99' }]),
  );
});
