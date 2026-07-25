import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ImportReferencesModal from './ImportReferencesModal';

async function pasteInto(label: string, value: string) {
  const el = screen.getByLabelText(label);
  await userEvent.click(el);
  await userEvent.paste(value); // paste, not type: BibTeX/JSON contain { which userEvent.type eats
}

async function selectFormat(name: string) {
  await userEvent.click(screen.getByRole('textbox', { name: 'Format' }));
  await userEvent.click(await screen.findByRole('option', { name }));
}

test('imports pasted BibTeX (the default format)', async () => {
  let body: Record<string, unknown> | undefined;
  server.use(
    http.post('/api/projects/3/references/import-bibtex', async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json([{ id: 1 }, { id: 2 }]);
    }),
  );
  renderWithProviders(<ImportReferencesModal pid={3} opened onClose={() => {}} />);

  expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled();
  await pasteInto('BibTeX', '@article{k, title={T}}');
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  await waitFor(() => expect(body).toEqual({ bibtex: '@article{k, title={T}}' }));
  expect(await screen.findByText('Imported 2 references')).toBeInTheDocument();
});

test('switching the format relabels the field and posts to that format endpoint (RIS)', async () => {
  let body: Record<string, unknown> | undefined;
  server.use(
    http.post('/api/projects/3/references/import-ris', async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json([{ id: 5 }]);
    }),
  );
  renderWithProviders(<ImportReferencesModal pid={3} opened onClose={() => {}} />);

  await selectFormat('RIS');
  // The textarea is now labelled RIS (the label follows the selected format).
  await pasteInto('RIS', 'TY  - JOUR\nER  - ');
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  await waitFor(() => expect(body).toEqual({ ris: 'TY  - JOUR\nER  - ' }));
  expect(await screen.findByText('Imported 1 reference')).toBeInTheDocument();
});

test('CSL-JSON format posts to the csl-json endpoint', async () => {
  let body: Record<string, unknown> | undefined;
  server.use(
    http.post('/api/projects/3/references/import-csl-json', async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json([{ id: 7 }]);
    }),
  );
  renderWithProviders(<ImportReferencesModal pid={3} opened onClose={() => {}} />);

  await selectFormat('CSL-JSON');
  await pasteInto('CSL-JSON', '[{"type":"book"}]');
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  await waitFor(() => expect(body).toEqual({ cslJson: '[{"type":"book"}]' }));
});
