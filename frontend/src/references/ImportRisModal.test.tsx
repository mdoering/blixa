import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ImportRisModal from './ImportRisModal';

const RIS = 'TY  - JOUR\nAU  - Doe, Jane\nTI  - A Title\nER  - ';

// FileInput renders its picker as a plain <button> with the real <input type="file"> hidden as a
// sibling (see @mantine/core/FileInput), not aria-linked to the "RIS file" label -- same reasoning
// as ImportProjectModal.test.tsx's fileInput() helper.
function fileInput() {
  return document.querySelector('input[type="file"]') as HTMLInputElement;
}

function makeRisFile(name = 'export.ris') {
  return new File([RIS], name, { type: 'application/x-research-info-systems' });
}

test('pasting RIS into the textarea posts it and shows the created count', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/3/references/import-ris', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json([{ id: 1 }, { id: 2 }]);
    }),
  );
  const onClose = vi.fn();
  renderWithProviders(<ImportRisModal pid={3} opened onClose={onClose} />);

  // disabled while empty -- the button click below is a no-op since it's disabled
  expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled();

  await userEvent.type(screen.getByLabelText('RIS'), RIS);
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  await waitFor(() => expect(body).toEqual({ ris: RIS }));
  expect(await screen.findByText('Imported 2 references')).toBeInTheDocument();
  expect(onClose).toHaveBeenCalled();
});

test('uploading a .ris file fills the textarea from its contents and posts it', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/3/references/import-ris', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json([{ id: 1 }]);
    }),
  );
  renderWithProviders(<ImportRisModal pid={3} opened onClose={() => {}} />);

  await userEvent.upload(fileInput(), makeRisFile());
  await waitFor(() => expect(screen.getByLabelText('RIS')).toHaveValue(RIS));

  await userEvent.click(screen.getByRole('button', { name: 'Import' }));
  await waitFor(() => expect(body).toEqual({ ris: RIS }));
  expect(await screen.findByText('Imported 1 reference')).toBeInTheDocument();
});

test('a failed import shows the error and does not close the modal', async () => {
  server.use(
    http.post('/api/projects/3/references/import-ris', () =>
      HttpResponse.json({ error: 'no RIS entries found' }, { status: 400 }),
    ),
  );
  const onClose = vi.fn();
  renderWithProviders(<ImportRisModal pid={3} opened onClose={onClose} />);

  await userEvent.type(screen.getByLabelText('RIS'), 'not RIS at all');
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  expect(await screen.findByText('no RIS entries found')).toBeInTheDocument();
  expect(onClose).not.toHaveBeenCalled();
});
