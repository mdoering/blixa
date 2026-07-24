import { expect, test } from 'vitest';
import { server, http, HttpResponse } from '../test/server';
import { listChanges } from './changes';

test('listChanges sends discussionId/limit/offset', async () => {
  let url = '';
  server.use(
    http.get('/api/projects/3/changes', ({ request }) => {
      url = request.url;
      return HttpResponse.json([]);
    }),
  );
  await listChanges(3, { discussionId: 7, limit: 25, offset: 25 });
  expect(url).toContain('discussionId=7');
  expect(url).toContain('limit=25');
  expect(url).toContain('offset=25');
});

test('listChanges omits discussionId when not given', async () => {
  let url = '';
  server.use(
    http.get('/api/projects/3/changes', ({ request }) => {
      url = request.url;
      return HttpResponse.json([]);
    }),
  );
  await listChanges(3, { limit: 25, offset: 0 });
  expect(url).not.toContain('discussionId');
});
