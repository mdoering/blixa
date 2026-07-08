import { expect, test } from 'vitest';
import { server, http, HttpResponse } from '../test/server';
import { getChildren, getPath, getRoots } from './tree';

test('getRoots calls the project roots endpoint with paging params', async () => {
  let requestUrl = '';
  server.use(
    http.get('/api/projects/3/tree/roots', ({ request }) => {
      requestUrl = request.url;
      return HttpResponse.json([]);
    }),
  );
  await getRoots(3, { limit: 10, offset: 5 });
  expect(requestUrl).toContain('/api/projects/3/tree/roots');
  expect(requestUrl).toContain('limit=10');
  expect(requestUrl).toContain('offset=5');
});

test('getRoots omits paging params when not given', async () => {
  let requestUrl = '';
  server.use(
    http.get('/api/projects/3/tree/roots', ({ request }) => {
      requestUrl = request.url;
      return HttpResponse.json([]);
    }),
  );
  await getRoots(3);
  expect(requestUrl.endsWith('/api/projects/3/tree/roots')).toBe(true);
});

test('getChildren calls the children endpoint for the given parent', async () => {
  let requestUrl = '';
  server.use(
    http.get('/api/projects/3/tree/children/42', ({ request }) => {
      requestUrl = request.url;
      return HttpResponse.json([]);
    }),
  );
  await getChildren(3, 42, { limit: 20 });
  expect(requestUrl).toContain('/api/projects/3/tree/children/42');
  expect(requestUrl).toContain('limit=20');
});

test('getPath calls the path endpoint for the given id', async () => {
  let called = false;
  server.use(
    http.get('/api/projects/3/tree/path/42', () => {
      called = true;
      return HttpResponse.json([]);
    }),
  );
  await getPath(3, 42);
  expect(called).toBe(true);
});
