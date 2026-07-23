import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ImportCslJsonModal from './ImportCslJsonModal';

const CSL = '[{"type":"book","title":"A Title"}]';

// FileInput renders its picker as a <button> with the real <input type="file"> hidden as a sibling
// (see ImportRisModal.test.tsx) -- reach the input directly.
function fileInput() {
  return document.querySelector('input[type="file"]') as HTMLInputElement;
}

test('pasting CSL-JSON posts it and shows the created count', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/3/references/import-csl-json', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json([{ id: 1 }]);
    }),
  );
  const onClose = vi.fn();
  renderWithProviders(<ImportCslJsonModal pid={3} opened onClose={onClose} />);

  expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled();

  // paste, not type: CSL's { and [ would be parsed as userEvent special-key sequences.
  await userEvent.click(screen.getByLabelText('CSL-JSON'));
  await userEvent.paste(CSL);
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  await waitFor(() => expect(body).toEqual({ cslJson: CSL }));
  expect(await screen.findByText('Imported 1 reference')).toBeInTheDocument();
  expect(onClose).toHaveBeenCalled();
});

test('uploading a .json file fills the textarea from its contents and posts it', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/3/references/import-csl-json', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json([{ id: 1 }, { id: 2 }]);
    }),
  );
  renderWithProviders(<ImportCslJsonModal pid={3} opened onClose={() => {}} />);

  await userEvent.upload(fileInput(), new File([CSL], 'refs.json', { type: 'application/json' }));
  await waitFor(() => expect(screen.getByLabelText('CSL-JSON')).toHaveValue(CSL));

  await userEvent.click(screen.getByRole('button', { name: 'Import' }));
  await waitFor(() => expect(body).toEqual({ cslJson: CSL }));
  expect(await screen.findByText('Imported 2 references')).toBeInTheDocument();
});

test('a failed import shows the error and does not close the modal', async () => {
  server.use(
    http.post('/api/projects/3/references/import-csl-json', () =>
      HttpResponse.json({ error: 'could not parse CSL-JSON' }, { status: 400 }),
    ),
  );
  const onClose = vi.fn();
  renderWithProviders(<ImportCslJsonModal pid={3} opened onClose={onClose} />);

  await userEvent.click(screen.getByLabelText('CSL-JSON'));
  await userEvent.paste('{not json');
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  expect(await screen.findByText('could not parse CSL-JSON')).toBeInTheDocument();
  expect(onClose).not.toHaveBeenCalled();
});
