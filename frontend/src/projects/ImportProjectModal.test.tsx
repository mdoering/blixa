import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ImportProjectModal from './ImportProjectModal';

function makeZipFile(name = 'checklist.zip') {
  return new File(['pretend-zip-bytes'], name, { type: 'application/zip' });
}

// FileInput renders its picker as a plain <button> (see @mantine/core/FileInput), with the real
// `<input type="file">` hidden as a sibling rather than aria-linked to the "ColDP file" label --
// so tests have to reach the native input directly rather than via getByLabelText. It's also
// teleported into the Modal's portal (outside RTL's render() container), so this queries the
// document rather than a container ref.
function fileInput() {
  return document.querySelector('input[type="file"]') as HTMLInputElement;
}

test('uploads a file, polls RUNNING -> DONE, and shows counts + the new-project link + issues', async () => {
  let getCalls = 0;
  let postedBody: FormData | null = null;
  server.use(
    http.post('/api/projects/import', async ({ request }) => {
      postedBody = await request.formData();
      return HttpResponse.json(
        {
          id: 42,
          projectId: null,
          status: 'RUNNING',
          sourceName: 'checklist.zip',
          preserveIds: false,
          idScope: null,
          nameUsageCount: 0,
          referenceCount: 0,
          authorCount: 0,
          issues: [],
          startedAt: '2026-07-11T00:00:00Z',
          finishedAt: null,
          error: null,
        },
        { status: 202 },
      );
    }),
    http.get('/api/projects/import/42', () => {
      getCalls += 1;
      const done = getCalls > 1;
      return HttpResponse.json({
        id: 42,
        projectId: done ? 9 : null,
        status: done ? 'DONE' : 'RUNNING',
        sourceName: 'checklist.zip',
        preserveIds: false,
        idScope: null,
        nameUsageCount: done ? 120 : 0,
        referenceCount: done ? 15 : 0,
        authorCount: done ? 8 : 0,
        issues: done ? [{ entity: 'Reference', sourceId: 'ref-1', message: 'missing year' }] : [],
        startedAt: '2026-07-11T00:00:00Z',
        finishedAt: done ? '2026-07-11T00:00:05Z' : null,
        error: null,
      });
    }),
  );

  renderWithProviders(<ImportProjectModal opened onClose={() => {}} />);

  await userEvent.upload(fileInput(), makeZipFile());
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  expect(await screen.findByText('Importing…')).toBeInTheDocument();
  await waitFor(() => expect(postedBody).not.toBeNull());
  expect(postedBody!.get('preserveIds')).toBe('false');
  expect(postedBody!.get('idScope')).toBeNull();
  expect((postedBody!.get('file') as File).name).toBe('checklist.zip');

  // DONE: entity counts, the new-project link, and the non-fatal issues summary. Generous timeout:
  // the mocked RUNNING -> DONE transition only surfaces after one IMPORT_POLL_MS refetchInterval
  // tick (1500ms), longer than @testing-library's default 1000ms waitFor timeout.
  await waitFor(() => expect(screen.getByText('usages 120')).toBeInTheDocument(), { timeout: 5000 });
  expect(screen.getByText('references 15')).toBeInTheDocument();
  expect(screen.getByText('authors 8')).toBeInTheDocument();
  const link = screen.getByRole('link', { name: 'Open imported project' });
  expect(link).toHaveAttribute('href', '/projects/9');
  expect(screen.getByText('1 issue')).toBeInTheDocument();
  expect(screen.getByText('Reference: missing year')).toBeInTheDocument();
});

test('toggling "Preserve source identifiers" reveals the scope field, hides it when off, and includes it in the POST', async () => {
  let postedBody: FormData | null = null;
  server.use(
    http.post('/api/projects/import', async ({ request }) => {
      postedBody = await request.formData();
      return HttpResponse.json(
        {
          id: 43,
          projectId: null,
          status: 'RUNNING',
          sourceName: 'checklist.zip',
          preserveIds: true,
          idScope: 'col',
          nameUsageCount: 0,
          referenceCount: 0,
          authorCount: 0,
          issues: [],
          startedAt: '2026-07-11T00:00:00Z',
          finishedAt: null,
          error: null,
        },
        { status: 202 },
      );
    }),
    http.get('/api/projects/import/43', () =>
      HttpResponse.json({
        id: 43,
        projectId: null,
        status: 'RUNNING',
        sourceName: 'checklist.zip',
        preserveIds: true,
        idScope: 'col',
        nameUsageCount: 0,
        referenceCount: 0,
        authorCount: 0,
        issues: [],
        startedAt: '2026-07-11T00:00:00Z',
        finishedAt: null,
        error: null,
      }),
    ),
  );

  renderWithProviders(<ImportProjectModal opened onClose={() => {}} />);

  // Mantine's Combobox-family widgets also expose their (hidden) options listbox via
  // aria-labelledby pointing at the field's label, so getByRole('textbox', ...) rather than
  // getByLabelText is used to pin the match to the actual input (getByLabelText would ambiguously
  // match both the input and the listbox div).
  expect(screen.queryByRole('textbox', { name: 'Identifier scope' })).not.toBeInTheDocument();

  await userEvent.click(screen.getByLabelText('Preserve source identifiers'));
  expect(screen.getByRole('textbox', { name: 'Identifier scope' })).toBeInTheDocument();

  // Submit is disabled until a scope is entered (no file yet either) -- required-when-on.
  expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled();

  await userEvent.upload(fileInput(), makeZipFile());
  await userEvent.type(screen.getByRole('textbox', { name: 'Identifier scope' }), 'col');
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  await waitFor(() => expect(postedBody).not.toBeNull());
  expect(postedBody!.get('preserveIds')).toBe('true');
  expect(postedBody!.get('idScope')).toBe('col');

  // Toggling back off hides the field again.
  await userEvent.click(screen.getByLabelText('Preserve source identifiers'));
  expect(screen.queryByRole('textbox', { name: 'Identifier scope' })).not.toBeInTheDocument();
});

test('uploads a text-tree file with a title, forcing preserveIds off and hiding idScope', async () => {
  let postedBody: FormData | null = null;
  server.use(
    http.post('/api/projects/import', async ({ request }) => {
      postedBody = await request.formData();
      return HttpResponse.json(
        {
          id: 45,
          projectId: null,
          status: 'RUNNING',
          sourceName: 'cats.txtree',
          preserveIds: false,
          idScope: null,
          nameUsageCount: 0,
          referenceCount: 0,
          authorCount: 0,
          issues: [],
          startedAt: '2026-07-11T00:00:00Z',
          finishedAt: null,
          error: null,
        },
        { status: 202 },
      );
    }),
    http.get('/api/projects/import/45', () =>
      HttpResponse.json({
        id: 45,
        projectId: null,
        status: 'RUNNING',
        sourceName: 'cats.txtree',
        preserveIds: false,
        idScope: null,
        nameUsageCount: 0,
        referenceCount: 0,
        authorCount: 0,
        issues: [],
        startedAt: '2026-07-11T00:00:00Z',
        finishedAt: null,
        error: null,
      }),
    ),
  );

  renderWithProviders(<ImportProjectModal opened onClose={() => {}} />);

  const file = new File(['Aus bus\nBus cus\n'], 'cats.txtree', { type: 'text/plain' });
  await userEvent.upload(fileInput(), file);

  // preserveIds doesn't apply to text-tree uploads -- the switch (and the scope field it reveals)
  // is hidden rather than merely disabled.
  expect(screen.queryByLabelText('Preserve source identifiers')).not.toBeInTheDocument();

  await userEvent.type(screen.getByLabelText('Title'), 'Cats');
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  await waitFor(() => expect(postedBody).not.toBeNull());
  expect(postedBody!.get('preserveIds')).toBe('false');
  expect(postedBody!.get('idScope')).toBeNull();
  expect(postedBody!.get('title')).toBe('Cats');
  expect((postedBody!.get('file') as File).name).toBe('cats.txtree');
});

test('a FAILED run renders the error alert', async () => {
  server.use(
    http.post('/api/projects/import', () =>
      HttpResponse.json(
        {
          id: 44,
          projectId: null,
          status: 'RUNNING',
          sourceName: 'checklist.zip',
          preserveIds: false,
          idScope: null,
          nameUsageCount: 0,
          referenceCount: 0,
          authorCount: 0,
          issues: [],
          startedAt: '2026-07-11T00:00:00Z',
          finishedAt: null,
          error: null,
        },
        { status: 202 },
      ),
    ),
    http.get('/api/projects/import/44', () =>
      HttpResponse.json({
        id: 44,
        projectId: null,
        status: 'FAILED',
        sourceName: 'checklist.zip',
        preserveIds: false,
        idScope: null,
        nameUsageCount: 0,
        referenceCount: 0,
        authorCount: 0,
        issues: [],
        startedAt: '2026-07-11T00:00:00Z',
        finishedAt: '2026-07-11T00:00:02Z',
        error: 'not a valid ColDP archive',
      }),
    ),
  );

  renderWithProviders(<ImportProjectModal opened onClose={() => {}} />);

  await userEvent.upload(fileInput(), makeZipFile());
  await userEvent.click(screen.getByRole('button', { name: 'Import' }));

  expect(await screen.findByText('Import failed')).toBeInTheDocument();
  expect(screen.getByText('not a valid ColDP archive')).toBeInTheDocument();
});
