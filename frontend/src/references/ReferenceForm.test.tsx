import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ReferenceForm from './ReferenceForm';
import type { Reference } from '../api/types';

function makeReference(overrides: Partial<Reference> = {}): Reference {
  return {
    id: 1,
    citation: 'Linnaeus 1758',
    type: null,
    author: 'Linnaeus, C.',
    editor: null,
    title: 'Systema Naturae',
    containerTitle: null,
    issued: '1758',
    volume: null,
    issue: null,
    page: null,
    publisher: null,
    doi: null,
    isbn: null,
    issn: null,
    link: null,
    accessed: null,
    remarks: null,
    version: 0,
    pdfUrl: null,
    ...overrides,
  };
}

function makePdfFile(name = 'paper.pdf') {
  return new File(['%PDF-1.4 pretend bytes'], name, { type: 'application/pdf' });
}

// FileInput renders its picker as a plain <button> with the real <input type="file"> hidden as a
// sibling, teleported into the Modal's portal -- same reasoning as ImportRisModal.test.tsx's
// fileInput() helper.
function fileInput() {
  return document.querySelector('input[type="file"]') as HTMLInputElement;
}

test('a saved reference with no pdfUrl shows the FileInput; attaching posts multipart and reveals View/Remove', async () => {
  let postedBody: FormData | null = null;
  server.use(
    http.post('/api/projects/3/references/1/pdf', async ({ request }) => {
      postedBody = await request.formData();
      return HttpResponse.json(makeReference({ pdfUrl: '/pdf/1-paper.pdf' }));
    }),
  );

  renderWithProviders(
    <ReferenceForm pid={3} reference={makeReference()} opened onClose={() => {}} />,
  );

  // No PDF yet: the FileInput + Attach control is present, no View/Remove.
  expect(screen.queryByRole('link', { name: 'View PDF' })).not.toBeInTheDocument();
  const attachButton = screen.getByRole('button', { name: 'Attach' });
  expect(attachButton).toBeDisabled();

  await userEvent.upload(fileInput(), makePdfFile());
  expect(attachButton).toBeEnabled();
  await userEvent.click(attachButton);

  await waitFor(() => expect(postedBody).not.toBeNull());
  expect((postedBody!.get('file') as File).name).toBe('paper.pdf');

  const link = await screen.findByRole('link', { name: 'View PDF' });
  expect(link).toHaveAttribute('href', '/pdf/1-paper.pdf');
  expect(link).toHaveAttribute('target', '_blank');
  expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Attach' })).not.toBeInTheDocument();
});

test('Remove deletes the PDF and returns to the FileInput', async () => {
  let deleteCalled = false;
  server.use(
    http.delete('/api/projects/3/references/1/pdf', () => {
      deleteCalled = true;
      return HttpResponse.json(makeReference({ pdfUrl: null }));
    }),
  );

  renderWithProviders(
    <ReferenceForm
      pid={3}
      reference={makeReference({ pdfUrl: '/pdf/1-paper.pdf' })}
      opened
      onClose={() => {}}
    />,
  );

  expect(screen.getByRole('link', { name: 'View PDF' })).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Remove' }));

  await waitFor(() => expect(deleteCalled).toBe(true));
  expect(await screen.findByRole('button', { name: 'Attach' })).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'View PDF' })).not.toBeInTheDocument();
});

test('a rejected attach shows an error message and stays on the FileInput', async () => {
  server.use(
    http.post('/api/projects/3/references/1/pdf', () =>
      HttpResponse.json({ error: 'file must be a PDF' }, { status: 400 }),
    ),
  );

  renderWithProviders(
    <ReferenceForm pid={3} reference={makeReference()} opened onClose={() => {}} />,
  );

  await userEvent.upload(fileInput(), makePdfFile());
  await userEvent.click(screen.getByRole('button', { name: 'Attach' }));

  expect(await screen.findByText('file must be a PDF')).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'View PDF' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Attach' })).toBeInTheDocument();
});

test('a new (unsaved) reference has no PDF control', () => {
  renderWithProviders(<ReferenceForm pid={3} reference={null} opened onClose={() => {}} />);

  expect(screen.queryByText('PDF')).not.toBeInTheDocument();
  expect(document.querySelector('input[type="file"]')).not.toBeInTheDocument();
});
