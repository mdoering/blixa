import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { api, ApiError } from './client';

describe('api client', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=tok-123; path=/';
  });
  afterEach(() => {
    vi.restoreAllMocks();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  test('GET sends credentials and no CSRF header', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), { status: 200 }),
    );
    await api('/api/me');
    const [, init] = fetchMock.mock.calls[0];
    expect(init?.credentials).toBe('include');
    expect((init?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined();
  });

  test('POST includes the X-XSRF-TOKEN header from the cookie', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockResolvedValue(new Response(null, { status: 204 }));
    await api('/api/projects', { method: 'POST', json: { slug: 'x', title: 'X' } });
    const [, init] = fetchMock.mock.calls.at(-1)!;
    expect((init?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('tok-123');
    expect(init?.body).toBe(JSON.stringify({ slug: 'x', title: 'X' }));
  });

  test('throws ApiError with the {error} body message', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ error: 'nope' }), { status: 409 }),
    );
    await expect(api('/api/projects', { method: 'POST', json: {} })).rejects.toMatchObject({
      status: 409,
      message: 'nope',
    } satisfies Partial<ApiError>);
  });
});
