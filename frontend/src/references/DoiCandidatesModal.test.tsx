import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import DoiCandidatesModal from './DoiCandidatesModal';

test('lists Crossref candidates and applies the picked DOI', async () => {
  server.use(
    http.get('/api/projects/3/references/7/doi-candidates', () =>
      HttpResponse.json([
        {
          doi: '10.1/abc',
          title: 'On a new species',
          author: 'Smith, J.',
          containerTitle: 'Journal of Botany',
          year: '1899',
          score: 88.5,
        },
      ]),
    ),
  );
  const onPick = vi.fn();
  const onClose = vi.fn();
  renderWithProviders(
    <DoiCandidatesModal pid={3} referenceId={7} opened onClose={onClose} onPick={onPick} />,
  );

  expect(await screen.findByText('On a new species')).toBeInTheDocument();
  expect(screen.getByText('10.1/abc')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Use' }));
  await waitFor(() => expect(onPick).toHaveBeenCalledWith('10.1/abc'));
  expect(onClose).toHaveBeenCalled();
});

test('shows an empty state when Crossref returns no candidates', async () => {
  server.use(
    http.get('/api/projects/3/references/7/doi-candidates', () => HttpResponse.json([])),
  );
  renderWithProviders(
    <DoiCandidatesModal pid={3} referenceId={7} opened onClose={vi.fn()} onPick={vi.fn()} />,
  );
  expect(await screen.findByText(/No candidate DOIs found/)).toBeInTheDocument();
});
