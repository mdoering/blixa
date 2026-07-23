import { expect, test } from 'vitest';
import { server, http, HttpResponse } from '../test/server';
import {
  bhlItemPages,
  bhlNamePages,
  bhlPublicationSearch,
  clearReferenceBhlItem,
  getBhlConfig,
  setReferenceBhlItem,
} from './bhl';

test('getBhlConfig GETs the project BHL config', async () => {
  server.use(http.get('/api/projects/3/bhl/config', () => HttpResponse.json({ available: true })));
  expect((await getBhlConfig(3)).available).toBe(true);
});

test('bhlPublicationSearch passes the query and returns items', async () => {
  let url = '';
  server.use(
    http.get('/api/projects/3/bhl/publication-search', ({ request }) => {
      url = request.url;
      return HttpResponse.json([{ itemId: 7, title: 'X', authors: null, year: '1900', url: 'u' }]);
    }),
  );
  const items = await bhlPublicationSearch(3, 'Genera Plantarum');
  expect(url).toContain('q=Genera%20Plantarum');
  expect(items[0].itemId).toBe(7);
});

test('setReferenceBhlItem PUTs to the reference bhl-item endpoint', async () => {
  let method = '';
  server.use(
    http.put('/api/projects/3/references/5/bhl-item/7', ({ request }) => {
      method = request.method;
      return HttpResponse.json({ id: 5, bhlItemId: 7 });
    }),
  );
  const ref = await setReferenceBhlItem(3, 5, 7);
  expect(method).toBe('PUT');
  expect(ref.bhlItemId).toBe(7);
});

test('bhlItemPages and bhlNamePages hit the item page endpoints', async () => {
  let nameUrl = '';
  server.use(
    http.get('/api/projects/3/bhl/items/7/pages', () =>
      HttpResponse.json([{ pageId: 9, pageNumber: '5', url: 'https://bhl/page/9', thumbnailUrl: 't' }]),
    ),
    http.get('/api/projects/3/bhl/items/7/name-pages', ({ request }) => {
      nameUrl = request.url;
      return HttpResponse.json([{ pageId: 9, pageNumber: '5', url: 'https://bhl/page/9', thumbnailUrl: null }]);
    }),
  );
  expect((await bhlItemPages(3, 7))[0].pageId).toBe(9);
  const pages = await bhlNamePages(3, 7, 'Aus bus');
  expect(pages[0].pageNumber).toBe('5');
  expect(nameUrl).toContain('name=Aus%20bus');
});

test('clearReferenceBhlItem DELETEs the reference bhl-item', async () => {
  let method = '';
  server.use(
    http.delete('/api/projects/3/references/5/bhl-item', ({ request }) => {
      method = request.method;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  await clearReferenceBhlItem(3, 5);
  expect(method).toBe('DELETE');
});
