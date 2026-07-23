import { expect, test } from 'vitest';
import { server, http, HttpResponse } from '../test/server';
import {
  definePropertyKey,
  deletePropertyKey,
  getPropertyKeys,
  mergePropertyKeys,
} from './propertyKeys';

test('getPropertyKeys GETs the project property-keys overview', async () => {
  server.use(
    http.get('/api/projects/3/property-keys', () =>
      HttpResponse.json([{ key: 'bodyMass', count: 2, description: 'Adult body mass' }]),
    ),
  );
  const keys = await getPropertyKeys(3);
  expect(keys[0].key).toBe('bodyMass');
  expect(keys[0].count).toBe(2);
});

test('definePropertyKey PUTs the key + description in the body', async () => {
  let body: unknown;
  let method = '';
  server.use(
    http.put('/api/projects/3/property-keys', async ({ request }) => {
      method = request.method;
      body = await request.json();
      return HttpResponse.json({ key: 'body mass', count: 2, description: 'Adult body mass' });
    }),
  );
  const info = await definePropertyKey(3, 'body mass', 'Adult body mass');
  expect(method).toBe('PUT');
  expect(body).toEqual({ key: 'body mass', description: 'Adult body mass' });
  expect(info.description).toBe('Adult body mass');
});

test('deletePropertyKey DELETEs with the key as a query param', async () => {
  let url = '';
  let method = '';
  server.use(
    http.delete('/api/projects/3/property-keys', ({ request }) => {
      url = request.url;
      method = request.method;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  await deletePropertyKey(3, 'body mass');
  expect(method).toBe('DELETE');
  expect(url).toContain('key=body%20mass');
});

test('mergePropertyKeys POSTs canonical + variants and returns the updated count', async () => {
  let body: unknown;
  server.use(
    http.post('/api/projects/3/property-keys/merge', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ updated: 3 });
    }),
  );
  const res = await mergePropertyKeys(3, 'bodyMass', ['body mass', 'bodyMass']);
  expect(body).toEqual({ canonical: 'bodyMass', variants: ['body mass', 'bodyMass'] });
  expect(res.updated).toBe(3);
});
