import { expect, test } from 'vitest';
import { server, http, HttpResponse } from '../test/server';
import { issueSummary, listIssues, reviewIssue, revalidate } from './issues';

test('listIssues sends status/severity/limit/offset', async () => {
  let url = '';
  server.use(
    http.get('/api/projects/3/issues', ({ request }) => {
      url = request.url;
      return HttpResponse.json([]);
    }),
  );
  await listIssues(3, { status: 'open', severity: 'error', limit: 25, offset: 50 });
  expect(url).toContain('status=open');
  expect(url).toContain('severity=error');
  expect(url).toContain('limit=25');
  expect(url).toContain('offset=50');
});

test('issueSummary GETs the summary endpoint', async () => {
  server.use(
    http.get('/api/projects/3/issues/summary', () =>
      HttpResponse.json({ total: 3, byStatus: { open: 3 }, bySeverity: { error: 1, warning: 2 } }),
    ),
  );
  const s = await issueSummary(3);
  expect(s.total).toBe(3);
  expect(s.bySeverity.warning).toBe(2);
});

test('reviewIssue POSTs the action', async () => {
  let body: unknown = null;
  server.use(
    http.post('/api/projects/3/issues/5/review', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ id: 5, status: 'rejected' });
    }),
  );
  await reviewIssue(3, 5, 'reject');
  expect(body).toEqual({ action: 'reject' });
});

test('revalidate POSTs and returns the fresh summary', async () => {
  server.use(
    http.post('/api/projects/3/revalidate', () =>
      HttpResponse.json({ total: 0, byStatus: {}, bySeverity: {} }),
    ),
  );
  expect((await revalidate(3)).total).toBe(0);
});
