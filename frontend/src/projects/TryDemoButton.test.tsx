import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import TryDemoButton, { DEMO_ZIP_URL, demoTitle } from './TryDemoButton';

test('demoTitle numbers further copies', () => {
  expect(demoTitle([])).toBe('Blixa playground – Felidae');
  expect(demoTitle(['Blixa playground – Felidae'])).toBe('Blixa playground – Felidae (2)');
  expect(demoTitle(['Blixa playground – Felidae', 'Blixa playground – Felidae (2)'])).toBe(
    'Blixa playground – Felidae (3)',
  );
});

test('imports the bundled playground under a free title and opens its tree', async () => {
  let sentTitle: FormDataEntryValue | null = null;
  server.use(
    http.get(DEMO_ZIP_URL, () => new HttpResponse(new Uint8Array([80, 75, 3, 4]))),
    http.post('/api/projects/import', async ({ request }) => {
      sentTitle = (await request.formData()).get('title');
      return HttpResponse.json({ id: 7, status: 'RUNNING', projectId: null }, { status: 202 });
    }),
    http.get('/api/projects/import/7', () =>
      HttpResponse.json({ id: 7, status: 'DONE', projectId: 42, error: null }),
    ),
  );
  renderWithProviders(
    <Routes>
      <Route path="/" element={<TryDemoButton existingTitles={['Blixa playground – Felidae']} />} />
      <Route path="/projects/:pid/tree" element={<div>tree page</div>} />
    </Routes>,
  );
  await userEvent.click(screen.getByRole('button', { name: /try it with demo data/i }));
  expect(await screen.findByText('tree page', {}, { timeout: 3000 })).toBeInTheDocument();
  await waitFor(() => expect(sentTitle).toBe('Blixa playground – Felidae (2)'));
});
