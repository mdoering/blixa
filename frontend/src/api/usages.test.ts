import { expect, test } from 'vitest';
import { server, http, HttpResponse } from '../test/server';
import { demoteUsage, promoteUsage } from './usages';

test('demoteUsage POSTs the acceptedId/status/childrenTo/synonymsTo/version body', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/7/usages/9/demote', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ id: 9 });
    }),
  );
  await demoteUsage(7, 9, {
    acceptedId: 6,
    status: 'SYNONYM',
    childrenTo: 'new-accepted',
    synonymsTo: 'unassessed',
    version: 2,
  });
  expect(body).toEqual({
    acceptedId: 6,
    status: 'SYNONYM',
    childrenTo: 'new-accepted',
    synonymsTo: 'unassessed',
    version: 2,
  });
});

test('promoteUsage POSTs parentId + version (null parent = root)', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/7/usages/9/promote', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ id: 9 });
    }),
  );
  await promoteUsage(7, 9, { parentId: null, version: 4 });
  expect(body).toEqual({ parentId: null, version: 4 });
});
