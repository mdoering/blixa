import { readActiveObjectiveId } from './activeObjective';

const BASE = import.meta.env.VITE_API_BASE ?? '';

// The project id in an /api/projects/{pid}/... path, or null. Used to attach the active work
// objective (per project) to writes.
function projectIdFromPath(path: string): number | null {
  const m = /\/api\/projects\/(\d+)(?:\/|\?|$)/.exec(path);
  return m ? Number(m[1]) : null;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

export function messageFor(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.message ? error.message : fallback;
}

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(^|; )' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[2]) : null;
}

async function ensureCsrfCookie(): Promise<void> {
  if (readCookie('XSRF-TOKEN')) return;
  // A permitted GET makes the backend's CsrfCookieFilter write the XSRF-TOKEN cookie.
  await fetch(BASE + '/api/ping', { credentials: 'include' });
}

export interface ApiOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  json?: unknown;
  form?: Record<string, string>;
  formData?: FormData;
}

export async function api<T>(path: string, opts: ApiOptions = {}): Promise<T> {
  const method = opts.method ?? 'GET';
  const headers: Record<string, string> = {};
  const init: RequestInit = { method, credentials: 'include', headers };

  if (method !== 'GET') {
    await ensureCsrfCookie();
    const token = readCookie('XSRF-TOKEN');
    if (token) headers['X-XSRF-TOKEN'] = token;
    // Attach the active work objective (an OPEN discussion) for this write's project, if any, so
    // the backend stamps the resulting change (and lock) with it. No objective selected -> no
    // header -> ungrouped, the default. Per-project keyed, so cross-project writes stay correct.
    const pid = projectIdFromPath(path);
    if (pid != null) {
      const objectiveId = readActiveObjectiveId(pid);
      if (objectiveId != null) headers['X-Objective-Id'] = String(objectiveId);
    }
  }

  if (opts.formData) {
    // Multipart upload: the browser must set its own `Content-Type: multipart/form-data;
    // boundary=...` header from the FormData body, so we deliberately don't set one here (setting
    // any Content-Type manually would drop the boundary and break parsing server-side). The
    // X-XSRF-TOKEN header above still applies -- that's unrelated to the body encoding.
    init.body = opts.formData;
  } else if (opts.form) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    init.body = new URLSearchParams(opts.form).toString();
  } else if (opts.json !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(opts.json);
  }

  const res = await fetch(BASE + path, init);
  if (!res.ok) {
    let message = res.statusText;
    try {
      const body = await res.json();
      if (body && typeof body.error === 'string') message = body.error;
    } catch {
      /* no JSON body */
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
