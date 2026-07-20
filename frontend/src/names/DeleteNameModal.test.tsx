import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import DeleteNameModal from './DeleteNameModal';

const usage = { id: 1, scientificName: 'Panthera', status: 'ACCEPTED' };

test('subtree mode hides the reparent choice and confirms SUBTREE', async () => {
  const onConfirm = vi.fn();
  server.use(
    http.get('/api/projects/9/tree/children/1', () =>
      HttpResponse.json([{ id: 2, scientificName: 'Panthera leo', rank: 'species', status: 'ACCEPTED' }]),
    ),
  );
  renderWithProviders(
    <DeleteNameModal pid={9} usage={usage} opened onClose={() => {}} onConfirm={onConfirm} />,
  );

  // has children -> the reparent choice is offered
  expect(await screen.findByText(/Move them to/)).toBeInTheDocument();

  // choosing "entire subtree" removes the reparent choice
  await userEvent.click(screen.getByRole('radio', { name: /Entire subtree/ }));
  expect(screen.queryByText(/Move them to/)).not.toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
  expect(onConfirm).toHaveBeenCalledWith('SUBTREE', null);
});

test('reparenting to a searched-for taxon passes reparentTo', async () => {
  const onConfirm = vi.fn();
  server.use(
    http.get('/api/projects/9/tree/children/1', () =>
      HttpResponse.json([{ id: 2, scientificName: 'Panthera leo', rank: 'species', status: 'ACCEPTED' }]),
    ),
    http.get('/api/projects/9/usages', () =>
      HttpResponse.json({
        items: [{ id: 5, scientificName: 'Felis', authorship: null, rank: 'genus', status: 'ACCEPTED', version: 0 }],
        total: 1,
      }),
    ),
  );
  renderWithProviders(
    <DeleteNameModal pid={9} usage={usage} opened onClose={() => {}} onConfirm={onConfirm} />,
  );

  await screen.findByText(/Move them to/);
  await userEvent.click(screen.getByText('Another taxon…'));
  await userEvent.click(screen.getByPlaceholderText('Search for a new parent…'));
  await userEvent.click(await screen.findByRole('option', { name: 'Felis' }));
  await userEvent.click(screen.getByRole('button', { name: 'Delete' }));

  await waitFor(() => expect(onConfirm).toHaveBeenCalledWith('FOCAL_ONLY', 5));
});
