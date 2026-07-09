import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import MoveNameModal from './MoveNameModal';

const animalia = {
  id: 1,
  scientificName: 'Animalia',
  authorship: null,
  rank: 'kingdom',
  status: 'ACCEPTED',
  ordinal: 1,
  childCount: 0,
};
// The node being moved, as it also appears in the picker tree (to exercise the disabled row).
const movedNode = {
  id: 9,
  scientificName: 'Felis leo',
  authorship: null,
  rank: 'species',
  status: 'ACCEPTED',
  ordinal: 2,
  childCount: 0,
};
const moved = { id: 9, scientificName: 'Felis leo' };

test('picking a parent moves the node with its freshly-read version', async () => {
  let putBody: unknown = null;
  server.use(
    http.get('/api/projects/7/tree/roots', () => HttpResponse.json([animalia])),
    http.get('/api/projects/7/usages/1', () =>
      HttpResponse.json({ id: 1, scientificName: 'Animalia', version: 1 }),
    ),
    http.get('/api/projects/7/usages/9', () =>
      HttpResponse.json({ id: 9, scientificName: 'Felis leo', version: 3 }),
    ),
    http.put('/api/projects/7/tree/usages/9/parent', async ({ request }) => {
      putBody = await request.json();
      return new HttpResponse(null, { status: 200 });
    }),
  );
  const onClose = vi.fn();
  renderWithProviders(<MoveNameModal pid={7} usage={moved} opened onClose={onClose} />);

  // Move is disabled until a target is chosen.
  expect(screen.getByRole('button', { name: 'Move' })).toBeDisabled();

  await userEvent.click(await screen.findByText('Animalia'));
  expect(await screen.findByText(/New parent:/)).toBeInTheDocument();

  const moveBtn = screen.getByRole('button', { name: 'Move' });
  await waitFor(() => expect(moveBtn).toBeEnabled());
  await userEvent.click(moveBtn);

  await waitFor(() => expect(onClose).toHaveBeenCalled());
  expect(putBody).toEqual({ parentId: 1, version: 3 });
});

test('"Make it a root" moves the node to parentId null', async () => {
  let putBody: unknown = null;
  server.use(
    http.get('/api/projects/7/tree/roots', () => HttpResponse.json([animalia])),
    http.get('/api/projects/7/usages/9', () =>
      HttpResponse.json({ id: 9, scientificName: 'Felis leo', version: 5 }),
    ),
    http.put('/api/projects/7/tree/usages/9/parent', async ({ request }) => {
      putBody = await request.json();
      return new HttpResponse(null, { status: 200 });
    }),
  );
  const onClose = vi.fn();
  renderWithProviders(<MoveNameModal pid={7} usage={moved} opened onClose={onClose} />);

  await userEvent.click(await screen.findByText('Make it a root'));
  const moveBtn = screen.getByRole('button', { name: 'Move' });
  await waitFor(() => expect(moveBtn).toBeEnabled());
  await userEvent.click(moveBtn);

  await waitFor(() => expect(onClose).toHaveBeenCalled());
  expect(putBody).toEqual({ parentId: null, version: 5 });
});

test('the node being moved is not selectable as its own parent', async () => {
  server.use(
    http.get('/api/projects/7/tree/roots', () => HttpResponse.json([movedNode, animalia])),
    http.get('/api/projects/7/usages/1', () =>
      HttpResponse.json({ id: 1, scientificName: 'Animalia', version: 1 }),
    ),
  );
  renderWithProviders(<MoveNameModal pid={7} usage={moved} opened onClose={() => {}} />);

  await screen.findByText('Animalia');
  // 'Felis leo' appears twice: the modal title and the (disabled) tree row. Clicking the row must
  // not select it -> Move stays disabled.
  const felisLeo = screen.getAllByText('Felis leo');
  await userEvent.click(felisLeo[felisLeo.length - 1]);
  expect(screen.getByRole('button', { name: 'Move' })).toBeDisabled();

  // A different, enabled node selects fine.
  await userEvent.click(screen.getByText('Animalia'));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Move' })).toBeEnabled());
});
