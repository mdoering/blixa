import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { notifications } from '@mantine/notifications';
import { afterEach, expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import BiologyTab from './BiologyTab';
import type { NameUsage } from '../api/types';

// Mantine's notification store is a module-level singleton; clear it between tests so 'Saved'
// assertions stay reliable (see TaxonDetail.test.tsx).
afterEach(() => notifications.clean());

function usage(overrides: Partial<NameUsage> = {}): NameUsage {
  return {
    id: 10,
    version: 3,
    extinct: null,
    environment: null,
    temporalRangeStart: null,
    temporalRangeEnd: null,
    ...overrides,
  } as unknown as NameUsage;
}

// PropertyTab (rendered below the biology form) fetches its list + lazy option loaders.
function stubPropertyTab() {
  server.use(
    http.get('/api/projects/4/usages/10/properties', () => HttpResponse.json([])),
    http.get('/api/projects/4/property-keys', () => HttpResponse.json([])),
    http.get('/api/projects/4/references', () => HttpResponse.json([])),
  );
}

test('renders the biology form seeded from the usage, with the Properties list below', async () => {
  stubPropertyTab();
  renderWithProviders(
    <BiologyTab
      pid={4}
      usageId={10}
      canEdit
      usage={usage({ extinct: true, temporalRangeStart: 'Cretaceous' })}
    />,
  );

  const extinct = await screen.findByRole('checkbox', { name: /extinct/i });
  expect(extinct).toBeChecked();
  // temporal range start seeded
  expect(await screen.findByDisplayValue('Cretaceous')).toBeInTheDocument();
  // the Properties section is stacked below
  expect(screen.getByText('Properties')).toBeInTheDocument();
});

test('editing and saving PUTs to /taxon-info with the shared usage version', async () => {
  stubPropertyTab();
  let putBody: Record<string, unknown> | undefined;
  server.use(
    http.put('/api/projects/4/usages/10/taxon-info', async ({ request }) => {
      putBody = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(usage({ extinct: true, version: 4 }));
    }),
  );
  renderWithProviders(<BiologyTab pid={4} usageId={10} canEdit usage={usage({ version: 3 })} />);

  const extinct = await screen.findByRole('checkbox', { name: /extinct/i });
  await userEvent.click(extinct);
  await userEvent.click(screen.getByRole('button', { name: /save/i }));

  await waitFor(() => expect(putBody).toBeDefined());
  expect(putBody?.extinct).toBe(true);
  expect(putBody?.version).toBe(3);
  expect(await screen.findByText('Saved')).toBeInTheDocument();
});

test('a viewer sees disabled fields and no Save button', async () => {
  stubPropertyTab();
  renderWithProviders(<BiologyTab pid={4} usageId={10} canEdit={false} usage={usage()} />);

  const extinct = await screen.findByRole('checkbox', { name: /extinct/i });
  expect(extinct).toBeDisabled();
  expect(screen.queryByRole('button', { name: /save/i })).not.toBeInTheDocument();
});
