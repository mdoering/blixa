import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import DatasetLabel from './DatasetLabel';

test('resolves a dataset key to its human-readable label', async () => {
  let requestedKeys: string[] = [];
  server.use(
    http.get('/api/clb/dataset-labels', ({ request }) => {
      requestedKeys = new URL(request.url).searchParams.getAll('key');
      return HttpResponse.json({ '3LXR': 'COL' });
    }),
  );
  renderWithProviders(<DatasetLabel datasetKey="3LXR" />);

  expect(await screen.findByText('COL')).toBeInTheDocument();
  expect(requestedKeys).toEqual(['3LXR']);
});

test('falls back to the key itself when the dataset cannot be resolved', async () => {
  server.use(http.get('/api/clb/dataset-labels', () => HttpResponse.json({})));
  renderWithProviders(<DatasetLabel datasetKey="9ZZZ" />);

  expect(await screen.findByText('9ZZZ')).toBeInTheDocument();
});

test('abbreviates a very long label with an ellipsis', async () => {
  const longTitle = 'A'.repeat(100);
  server.use(http.get('/api/clb/dataset-labels', () => HttpResponse.json({ BIG: longTitle })));
  renderWithProviders(<DatasetLabel datasetKey="BIG" maxChars={48} />);

  const el = await screen.findByText(/^A+…$/);
  expect(el.textContent).toHaveLength(48); // 47 chars + the ellipsis
});
