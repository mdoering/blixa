import { expect, test } from 'vitest';
import { server, http, HttpResponse } from '../test/server';
import {
  createReference,
  importBibtex,
  importRisReferences,
  listReferences,
  resolveDoi,
  updateReference,
} from './references';

test('listReferences sends q/limit/offset', async () => {
  let url = '';
  server.use(
    http.get('/api/projects/3/references', ({ request }) => {
      url = request.url;
      return HttpResponse.json([]);
    }),
  );
  await listReferences(3, { q: 'linnaeus', limit: 25, offset: 25 });
  expect(url).toContain('q=linnaeus');
  expect(url).toContain('limit=25');
  expect(url).toContain('offset=25');
});

test('createReference POSTs the payload', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/3/references', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ id: 1, version: 0 });
    }),
  );
  await createReference(3, { title: 'T', author: [{ family: 'A' }] });
  expect(body).toEqual({ title: 'T', author: [{ family: 'A' }] });
});

test('updateReference PUTs with version', async () => {
  let body: unknown = null;
  server.use(
    http.put('/api/projects/3/references/1', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ id: 1, version: 1 });
    }),
  );
  await updateReference(3, 1, { title: 'T2', version: 0 });
  expect(body).toEqual({ title: 'T2', version: 0 });
});

test('resolveDoi POSTs the doi and returns the preview', async () => {
  server.use(
    http.post('/api/projects/3/references/resolve-doi', async ({ request }) => {
      const b = (await request.json()) as { doi: string };
      return HttpResponse.json({ title: 'Resolved', doi: b.doi });
    }),
  );
  const preview = await resolveDoi(3, '10.1/x');
  expect(preview).toEqual({ title: 'Resolved', doi: '10.1/x' });
});

test('importBibtex POSTs the text and returns created refs', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/3/references/import-bibtex', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json([{ id: 1 }, { id: 2 }]);
    }),
  );
  const created = await importBibtex(3, '@article{k, title={T}}');
  expect(body).toEqual({ bibtex: '@article{k, title={T}}' });
  expect(created).toHaveLength(2);
});

test('importRisReferences POSTs the text and returns created refs', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/3/references/import-ris', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json([{ id: 1 }, { id: 2 }]);
    }),
  );
  const created = await importRisReferences(3, 'TY  - JOUR\nTI  - T\nER  - ');
  expect(body).toEqual({ ris: 'TY  - JOUR\nTI  - T\nER  - ' });
  expect(created).toHaveLength(2);
});
