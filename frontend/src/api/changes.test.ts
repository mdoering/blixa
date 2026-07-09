import { expect, test } from 'vitest';
import { server, http, HttpResponse } from '../test/server';
import { listChanges, listTasks } from './changes';

test('listChanges sends taskId/limit/offset', async () => {
  let url = '';
  server.use(
    http.get('/api/projects/3/changes', ({ request }) => {
      url = request.url;
      return HttpResponse.json([]);
    }),
  );
  await listChanges(3, { taskId: 7, limit: 25, offset: 25 });
  expect(url).toContain('taskId=7');
  expect(url).toContain('limit=25');
  expect(url).toContain('offset=25');
});

test('listChanges omits taskId when not given', async () => {
  let url = '';
  server.use(
    http.get('/api/projects/3/changes', ({ request }) => {
      url = request.url;
      return HttpResponse.json([]);
    }),
  );
  await listChanges(3, { limit: 25, offset: 0 });
  expect(url).not.toContain('taskId');
});

test('listTasks GETs the tasks endpoint', async () => {
  let called = false;
  server.use(
    http.get('/api/projects/3/tasks', () => {
      called = true;
      return HttpResponse.json([]);
    }),
  );
  await listTasks(3);
  expect(called).toBe(true);
});
