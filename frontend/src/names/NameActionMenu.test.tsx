import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import NameActionMenu from './NameActionMenu';

const usage = { id: 10, scientificName: 'Panthera leo', status: 'ACCEPTED' };
const synonymUsage = { id: 11, scientificName: 'Felis leo', status: 'SYNONYM' };

test('a non-editor sees no action menu at all', () => {
  renderWithProviders(
    <NameActionMenu pid={3} usage={usage} canEdit={false} onSelect={() => {}} />,
  );
  expect(screen.queryByLabelText('Actions')).not.toBeInTheDocument();
});

test('an editor sees add-child/add-synonym/change-status/delete actions', async () => {
  renderWithProviders(<NameActionMenu pid={3} usage={usage} canEdit onSelect={() => {}} />);

  await userEvent.click(screen.getByLabelText('Actions'));

  expect(await screen.findByText('Add child')).toBeInTheDocument();
  expect(screen.getByText('Add synonym')).toBeInTheDocument();
  expect(screen.getByText(/^Move/)).toBeInTheDocument();
  expect(screen.getByText('Change status')).toBeInTheDocument();
  expect(screen.getByText('Accepted')).toBeInTheDocument();
  expect(screen.getByText('Synonym')).toBeInTheDocument();
  expect(screen.getByText('Misapplied')).toBeInTheDocument();
  expect(screen.getByText('Unassessed')).toBeInTheDocument();
  expect(screen.getByText('Delete')).toBeInTheDocument();
});

test('the change-status list greys out the usage\'s current status', async () => {
  renderWithProviders(<NameActionMenu pid={3} usage={usage} canEdit onSelect={() => {}} />);

  await userEvent.click(screen.getByLabelText('Actions'));
  await screen.findByText('Change status');

  // `usage` is ACCEPTED -- that option is disabled (can't "change" to the status it already has)
  // while the others remain clickable.
  expect(screen.getByText('Accepted').closest('button')).toBeDisabled();
  expect(screen.getByText('Synonym').closest('button')).toBeEnabled();
  expect(screen.getByText('Misapplied').closest('button')).toBeEnabled();
  expect(screen.getByText('Unassessed').closest('button')).toBeEnabled();
});

test('add-child/add-synonym are hidden for a non-accepted usage (backend 400s both)', async () => {
  renderWithProviders(
    <NameActionMenu pid={3} usage={synonymUsage} canEdit onSelect={() => {}} />,
  );

  await userEvent.click(screen.getByLabelText('Actions'));

  await screen.findByText('Change status'); // menu is open
  expect(screen.queryByText('Add child')).not.toBeInTheDocument();
  expect(screen.queryByText('Add synonym')).not.toBeInTheDocument();
  expect(screen.queryByText(/^Move/)).not.toBeInTheDocument();
  expect(screen.getByText('Delete')).toBeInTheDocument();
});

test('"Add child" opens the create modal in child mode, anchored on this usage', async () => {
  renderWithProviders(<NameActionMenu pid={3} usage={usage} canEdit onSelect={() => {}} />);

  await userEvent.click(screen.getByLabelText('Actions'));
  await userEvent.click(await screen.findByText('Add child'));

  // The create modal is open (its fields are present) and shows this usage as the anchor.
  expect(await screen.findByLabelText('Scientific name')).toBeInTheDocument();
  expect(screen.getByText('Panthera leo')).toBeInTheDocument();
});

test('"Delete" asks for confirmation before calling DELETE', async () => {
  let deleteCalled = false;
  server.use(
    http.delete('/api/projects/3/usages/10', () => {
      deleteCalled = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const onSelect = vi.fn();
  renderWithProviders(<NameActionMenu pid={3} usage={usage} canEdit onSelect={onSelect} />);

  await userEvent.click(screen.getByLabelText('Actions'));
  await userEvent.click(await screen.findByText('Delete'));

  const dialog = await screen.findByRole('dialog');
  expect(deleteCalled).toBe(false); // not yet -- still awaiting confirmation
  await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

  await waitFor(() => expect(deleteCalled).toBe(true));
  expect(onSelect).not.toHaveBeenCalled();
});

test('"Synonym" on an accepted usage opens the guided Demote modal', async () => {
  server.use(
    http.get('/api/projects/3/usages/10', () =>
      HttpResponse.json({ id: 10, version: 1, status: 'ACCEPTED', scientificName: 'Panthera leo', synonymIds: [] }),
    ),
    http.get('/api/projects/3/tree/children/10', () => HttpResponse.json([])),
    http.get('/api/projects/3/tree/roots', () => HttpResponse.json([])),
  );
  renderWithProviders(<NameActionMenu pid={3} usage={usage} canEdit onSelect={() => {}} />);

  await userEvent.click(screen.getByLabelText('Actions'));
  await userEvent.click(await screen.findByText('Synonym'));

  // The Demote modal's title ("Demote <name> to a synonym") is unique to the opened modal.
  expect(await screen.findByText(/to a synonym/)).toBeInTheDocument();
});

test('a confirmed delete calls onAfterDelete with the deleted usage id', async () => {
  server.use(
    http.delete('/api/projects/3/usages/10', () => new HttpResponse(null, { status: 204 })),
  );
  const onAfterDelete = vi.fn();
  renderWithProviders(
    <NameActionMenu pid={3} usage={usage} canEdit onSelect={() => {}} onAfterDelete={onAfterDelete} />,
  );

  await userEvent.click(screen.getByLabelText('Actions'));
  await userEvent.click(await screen.findByText('Delete'));

  const dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

  await waitFor(() => expect(onAfterDelete).toHaveBeenCalledWith(10));
});
