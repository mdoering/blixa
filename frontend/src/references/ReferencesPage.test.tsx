import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import * as mergeApi from '../api/merge';
import ReferencesPage from './ReferencesPage';

function mockProject(role = 'owner') {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json({ id: 3, title: 'P', role })),
    http.get('/api/projects/3/references', () =>
      HttpResponse.json([
        {
          id: 1,
          citation: 'Linnaeus 1758',
          title: 'Systema Naturae',
          author: [{ family: 'Linnaeus', given: 'C.' }],
          issued: '1758',
          containerTitle: null,
          doi: '10.5/abc',
          accessed: '2026-07-01',
          version: 0,
        },
      ]),
    ),
  );
}

function renderPage(route = '/projects/3/references') {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/references" element={<ReferencesPage />} />
    </Routes>,
    { route },
  );
}

test('lists references', async () => {
  mockProject();
  renderPage();
  expect(await screen.findByText('Systema Naturae')).toBeInTheDocument();
  expect(screen.getByText('10.5/abc')).toBeInTheDocument();
  // The author cell renders a CslName[] author joined as "Family, Given" (authorsToString).
  expect(screen.getByText('Linnaeus, C.')).toBeInTheDocument();
});

test('typing a year range re-queries with yearFrom and yearTo', async () => {
  const seen: string[] = [];
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json({ id: 3, title: 'P', role: 'owner' })),
    http.get('/api/projects/3/references', ({ request }) => {
      seen.push(new URL(request.url).search);
      return HttpResponse.json([]);
    }),
  );
  renderPage();

  await userEvent.type(screen.getByLabelText('Year'), '1941-1944');

  await waitFor(() =>
    expect(
      seen.some((s) => s.includes('yearFrom=1941') && s.includes('yearTo=1944')),
    ).toBe(true),
  );
});

test('"New reference" opens the form', async () => {
  mockProject();
  renderPage();
  await screen.findByText('Systema Naturae');
  await userEvent.click(screen.getByRole('button', { name: 'New reference' }));
  expect(await screen.findByRole('dialog')).toHaveTextContent('New reference');
});

test('Import DOI resolves and opens the form pre-filled', async () => {
  mockProject();
  server.use(
    http.post('/api/projects/3/references/resolve-doi', () =>
      HttpResponse.json({ title: 'Resolved Title', doi: '10.9/z' }),
    ),
  );
  renderPage();
  await screen.findByText('Systema Naturae');
  await userEvent.click(screen.getByRole('button', { name: 'Import DOI' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText('DOI'), '10.9/z');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Fetch' }));
  // the resolved preview lands in the reference form's Title field
  expect(await screen.findByDisplayValue('Resolved Title')).toBeInTheDocument();
});

test('editing a reference round-trips the accessed field', async () => {
  mockProject();
  let body: unknown = null;
  server.use(
    http.put('/api/projects/3/references/1', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ id: 1, version: 1 });
    }),
  );
  renderPage();
  await userEvent.click(await screen.findByText('Systema Naturae'));
  const dialog = await screen.findByRole('dialog');
  const accessedInput = within(dialog).getByLabelText('Accessed');
  expect(accessedInput).toHaveValue('2026-07-01');
  await userEvent.clear(accessedInput);
  await userEvent.type(accessedInput, '2026-07-09');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
  expect(body).toMatchObject({ accessed: '2026-07-09' });
});

test('?ref= deep-link opens that reference in the edit form', async () => {
  mockProject();
  server.use(
    http.get('/api/projects/3/references/2', () =>
      HttpResponse.json({
        id: 2,
        citation: 'Darwin 1859',
        title: 'On the Origin of Species',
        author: [{ family: 'Darwin', given: 'C.' }],
        issued: '1859',
        containerTitle: null,
        doi: null,
        accessed: null,
        version: 0,
      }),
    ),
  );
  renderPage('/projects/3/references?ref=2');
  const dialog = await screen.findByRole('dialog');
  // findBy*, not getBy*: the reference is fetched, then the form's own effect seeds its fields --
  // the dialog itself mounts (empty) a tick before that effect flushes, now that ReferenceForm also
  // kicks off the Type select's vocab query on mount alongside its field-seeding effect.
  expect(await within(dialog).findByDisplayValue('On the Origin of Species')).toBeInTheDocument();
});

test('?ref= for a deleted reference (404) fails gracefully -- no form, no crash', async () => {
  mockProject();
  server.use(
    http.get('/api/projects/3/references/99', () =>
      HttpResponse.json({ error: 'not found' }, { status: 404 }),
    ),
  );
  renderPage('/projects/3/references?ref=99');
  await screen.findByText('Systema Naturae');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

test('a viewer sees no editing controls', async () => {
  mockProject('viewer');
  renderPage();
  await screen.findByText('Systema Naturae');
  expect(screen.queryByRole('button', { name: 'New reference' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Import DOI' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Import BibTeX' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Import RIS' })).not.toBeInTheDocument();
});

test('Import RIS parses pasted text and refreshes the list', async () => {
  mockProject();
  server.use(
    http.post('/api/projects/3/references/import-ris', async ({ request }) => {
      const body = (await request.json()) as { ris: string };
      expect(body.ris).toContain('TY  - JOUR');
      return HttpResponse.json([{ id: 2 }]);
    }),
  );
  renderPage();
  await screen.findByText('Systema Naturae');
  await userEvent.click(screen.getByRole('button', { name: 'Import RIS' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText('RIS'), 'TY  - JOUR\nTI  - T\nER  - ');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Import' }));
  expect(await screen.findByText('Imported 1 reference')).toBeInTheDocument();
});

test('selecting 2 references opens the merge modal and refreshes the list on success', async () => {
  mockProject();
  let listCalls = 0;
  server.use(
    http.get('/api/projects/3/references', () => {
      listCalls++;
      return HttpResponse.json([
        {
          id: 1,
          citation: 'Linnaeus 1758',
          title: 'Systema Naturae',
          author: [{ family: 'Linnaeus', given: 'C.' }],
          issued: '1758',
          containerTitle: null,
          doi: '10.5/abc',
          accessed: '2026-07-01',
          version: 0,
        },
        {
          id: 2,
          citation: 'Darwin 1859',
          title: 'On the Origin of Species',
          author: [{ family: 'Darwin', given: 'C.' }],
          issued: '1859',
          containerTitle: null,
          doi: null,
          accessed: null,
          version: 0,
        },
      ]);
    }),
  );

  vi.spyOn(mergeApi, 'previewReferenceMerge').mockResolvedValue([
    { id: 1, alternativeId: null, citation: 'Linnaeus 1758', doi: '10.5/abc', counts: {} },
    { id: 2, alternativeId: null, citation: 'Darwin 1859', doi: null, counts: {} },
  ]);
  const merge = vi
    .spyOn(mergeApi, 'mergeReferences')
    .mockResolvedValue({ survivorId: 1, mergedCount: 1 });

  renderPage();
  await screen.findByText('Systema Naturae');
  await screen.findByText('On the Origin of Species');

  // No merge button until 2+ rows are selected.
  expect(screen.queryByRole('button', { name: /merge \d+ selected/i })).not.toBeInTheDocument();

  const checkboxes = screen.getAllByRole('checkbox');
  expect(checkboxes).toHaveLength(2);
  await userEvent.click(checkboxes[0]);
  await userEvent.click(checkboxes[1]);

  // Clicking a checkbox toggles selection only -- it must not open the edit form.
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

  await userEvent.click(await screen.findByRole('button', { name: 'Merge 2 selected…' }));

  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByText('Linnaeus 1758')).toBeInTheDocument();
  expect(within(dialog).getByText('Darwin 1859')).toBeInTheDocument();

  await userEvent.click(within(dialog).getByRole('button', { name: /merge 2 records into/i }));

  await waitFor(() => expect(merge).toHaveBeenCalledWith(3, 1, [1, 2]));
  // onDone invalidates the references query, triggering a refetch of the list.
  await waitFor(() => expect(listCalls).toBeGreaterThanOrEqual(2));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(screen.queryByRole('button', { name: /merge \d+ selected/i })).not.toBeInTheDocument();
});
