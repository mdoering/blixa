import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';

export const server = setupServer(
  http.get('/api/ping', () => HttpResponse.json({ status: 'ok' })),
  http.get('/api/me', () => new HttpResponse(null, { status: 401 })),
  http.post('/api/auth/login', () => new HttpResponse(null, { status: 200 })),
  http.get('/api/projects', () => HttpResponse.json([])),
  http.get('/api/config', () => HttpResponse.json({ orcidEnabled: true })),
  http.get('/api/public/projects', () => HttpResponse.json([])),
  // Default id-scopes vocab, so any page that seeds a scopes picker from it (ProjectMetadataPage)
  // or resolves a CURIE's link (CurieId) doesn't need this mocked per-test unless it cares about
  // the exact list.
  http.get('/api/coldp/id-scopes', () =>
    HttpResponse.json([
      { scope: 'col', title: 'Catalogue of Life', link: 'https://www.catalogueoflife.org' },
      { scope: 'gbif', title: 'GBIF', link: 'https://www.gbif.org' },
      { scope: 'ipni', title: 'IPNI', link: 'https://www.ipni.org' },
      { scope: 'tsn', title: 'ITIS TSN', link: null },
    ]),
  ),
  // Default enum vocab (VocabController), so any render of a component that seeds a dropdown from
  // it -- TaxonDetail's Rank/Nomenclatural-status selects, ReferenceForm's Type select -- doesn't
  // need this mocked per-test unless it cares about the exact list (both currently override this
  // per-test anyway; this default only covers renders that don't).
  http.get('/api/coldp/vocab', () =>
    HttpResponse.json({
      ranks: [],
      nomStatus: [],
      gender: [],
      environment: [],
      cslTypes: ['article-journal', 'book', 'chapter', 'thesis'],
    }),
  ),
  // Default empty release history, so any owner-role render of ProjectMetadataPage's Releases
  // section doesn't need this mocked per-test unless it cares about the actual list.
  http.get('/api/projects/:id/releases', () => HttpResponse.json([])),
  // Default empty lock list, so any render of TaxonDetail (which polls the project's locks for
  // the "locked by X" banner) doesn't need this mocked per-test unless it cares about a lock.
  http.get('/api/projects/:pid/locks', () => HttpResponse.json([])),
  // Default claim/release: TaxonDetail's fieldset now claims a lock on genuine user input (see
  // useUsageLock), so any existing test that types into a form field triggers this even when it
  // doesn't care about locking. Without a default, that POST/DELETE would be an unhandled request
  // (onUnhandledRequest: 'error' in setup.ts) -- respond with a normal "acquired by me" lock /
  // 204 so those tests stay quiet unless they override this to test locking itself.
  http.post('/api/projects/:pid/locks', async ({ request }) => {
    const body = (await request.json()) as { entityType: string; entityId: number };
    const now = new Date();
    return HttpResponse.json({
      id: 1,
      entityType: body.entityType,
      entityId: body.entityId,
      userId: 1,
      username: 'you',
      acquiredAt: now.toISOString(),
      expiresAt: new Date(now.getTime() + 300_000).toISOString(),
      heldByMe: true,
      taskId: null,
      taskTitle: null,
    });
  }),
  http.delete('/api/projects/:pid/locks/:id', () => new HttpResponse(null, { status: 204 })),
  // Discussion detail page fetches these (linked changes + recent changes for the editor picker).
  http.get('/api/projects/:pid/discussions/:did/changes', () => HttpResponse.json([])),
  http.get('/api/projects/:pid/changes', () => HttpResponse.json([])),
  // DeleteNameModal checks for accepted children before offering the reparent options.
  http.get('/api/projects/:pid/tree/children/:id', () => HttpResponse.json([])),
);

export { http, HttpResponse };
