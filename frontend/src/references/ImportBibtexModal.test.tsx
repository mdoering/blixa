import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ImportBibtexModal from './ImportBibtexModal';

const BIBTEX = '@article{doe1758, author = {Doe, Jane}, title = {A Title}, year = {1758}}';
// userEvent.type() parses `{` as the start of special-key syntax (`}` alone is just a printable
// char), so BibTeX's opening braces need escaping there -- doubling `{` produces a literal one, see
// https://testing-library.com/docs/user-event/keyboard.
const BIBTEX_TYPING = BIBTEX.replace(/\{/g, '{{');

// FileInput renders its picker as a plain <button> with the real <input type="file"> hidden as a
// sibling (see @mantine/core/FileInput), not aria-linked to the label -- same reasoning as
// ImportRisModal.test.tsx's fileInput() helper.
function fileInput() {
  return document.querySelector('input[type="file"]') as HTMLInputElement;
}

function makeBibtexFile(name = 'export.bib') {
  return new File([BIBTEX], name, { type: 'application/x-bibtex' });
}

test('pasting BibTeX into the textarea posts it and shows the created count', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/3/references/import-bibtex', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json([{ id: 1 }, { id: 2 }]);
    }),
  );
  const onClose = vi.fn();
  renderWithProviders(<ImportBibtexModal pid={3} opened onClose={onClose} />);

  // disabled while empty -- the button click below is a no-op since it's disabled
  expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled();

  await userEvent.type(screen.getByLabelText('BibTeX'), BIBTEX_TYPING);
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  await waitFor(() => expect(body).toEqual({ bibtex: BIBTEX }));
  expect(await screen.findByText('Imported 2 references')).toBeInTheDocument();
  expect(onClose).toHaveBeenCalled();
});

test('uploading a .bib file fills the textarea from its contents and posts it', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/3/references/import-bibtex', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json([{ id: 1 }]);
    }),
  );
  renderWithProviders(<ImportBibtexModal pid={3} opened onClose={() => {}} />);

  await userEvent.upload(fileInput(), makeBibtexFile());
  await waitFor(() => expect(screen.getByLabelText('BibTeX')).toHaveValue(BIBTEX));

  await userEvent.click(screen.getByRole('button', { name: 'Import' }));
  await waitFor(() => expect(body).toEqual({ bibtex: BIBTEX }));
  expect(await screen.findByText('Imported 1 reference')).toBeInTheDocument();
});

test('a failed import shows the error and does not close the modal', async () => {
  server.use(
    http.post('/api/projects/3/references/import-bibtex', () =>
      HttpResponse.json({ error: 'no BibTeX entries found' }, { status: 400 }),
    ),
  );
  const onClose = vi.fn();
  renderWithProviders(<ImportBibtexModal pid={3} opened onClose={onClose} />);

  await userEvent.type(screen.getByLabelText('BibTeX'), 'not BibTeX at all');
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  expect(await screen.findByText('no BibTeX entries found')).toBeInTheDocument();
  expect(onClose).not.toHaveBeenCalled();
});
