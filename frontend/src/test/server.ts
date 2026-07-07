import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';

export const server = setupServer(
  http.get('/api/ping', () => HttpResponse.json({ status: 'ok' })),
  http.get('/api/me', () => new HttpResponse(null, { status: 401 })),
  http.post('/api/auth/login', () => new HttpResponse(null, { status: 200 })),
  http.get('/api/projects', () => HttpResponse.json([])),
);

export { http, HttpResponse };
