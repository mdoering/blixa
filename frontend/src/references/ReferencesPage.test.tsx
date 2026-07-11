import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
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
          author: 'Linnaeus, C.',
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

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/references" element={<ReferencesPage />} />
    </Routes>,
    { route: '/projects/3/references' },
  );
}

test('lists references', async () => {
  mockProject();
  renderPage();
  expect(await screen.findByText('Systema Naturae')).toBeInTheDocument();
  expect(screen.getByText('10.5/abc')).toBeInTheDocument();
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
