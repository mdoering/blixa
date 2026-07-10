import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';

export const server = setupServer(
  http.get('/api/ping', () => HttpResponse.json({ status: 'ok' })),
  http.get('/api/me', () => new HttpResponse(null, { status: 401 })),
  http.post('/api/auth/login', () => new HttpResponse(null, { status: 200 })),
  http.get('/api/projects', () => HttpResponse.json([])),
  // Default id-scopes vocab, so any page that seeds a scopes picker from it (ProjectMetadataPage)
  // doesn't need this mocked per-test unless it cares about the exact list.
  http.get('/api/coldp/id-scopes', () => HttpResponse.json(['col', 'gbif', 'ipni', 'tsn'])),
);

export { http, HttpResponse };
