import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import NameActionMenu from './NameActionMenu';

const usage = { id: 10, scientificName: 'Panthera leo' };

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
  expect(screen.getByText('Change status')).toBeInTheDocument();
  expect(screen.getByText('Accepted')).toBeInTheDocument();
  expect(screen.getByText('Synonym')).toBeInTheDocument();
  expect(screen.getByText('Misapplied')).toBeInTheDocument();
  expect(screen.getByText('Unassessed')).toBeInTheDocument();
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
