# Phase 1 — Frontend Shell & Project Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A React + TypeScript SPA that logs in (ORCID + local), lists/creates/switches projects, edits a project's ColDP metadata, and manages project members — consuming the Plan 1 backend REST API.

**Architecture:** Vite + React 18 + TypeScript single-page app using Ant Design 5 for UI, TanStack Query v5 for server state, and React Router v6 for routing. A thin `fetch` API client handles session cookies and the Spring Security SPA **CSRF cookie/header dance** (read `XSRF-TOKEN` cookie → send `X-XSRF-TOKEN` header on mutations). Tests run in Vitest + Testing Library with Mock Service Worker (MSW) mocking the backend; no live backend is needed for tests. Committed directly to `main` (this project uses no feature branches).

**Tech Stack:** Vite 6, React 18.3, TypeScript 5.6, Ant Design 5, @tanstack/react-query 5, react-router-dom 6, Vitest 2, @testing-library/react 16, MSW 2, jsdom.

## Global Constraints

- Frontend lives under `frontend/` in the repo root (`~/code/col/coldp-editor`), a sibling of `backend/`.
- Commit directly to `main`. Do NOT create branches. Never commit `node_modules/` or `dist/` (already git-ignored).
- The SPA is the only client of the backend REST API. All requests use `credentials: 'include'` (session cookie). All **mutating** requests (POST/PUT/DELETE) send the `X-XSRF-TOKEN` header read from the `XSRF-TOKEN` cookie — the backend rejects mutations without it (403).
- Backend endpoints consumed (from Plan 1): `GET /api/ping`; `GET /api/me`; `POST /api/auth/login` (form-encoded `username`,`password`); `POST /api/auth/logout`; ORCID login by navigating the browser to `/oauth2/authorization/orcid`; `GET/POST /api/projects`; `GET/PUT /api/projects/{id}`, `PUT /api/projects/{id}/metadata`; `GET/PUT /api/projects/{id}/members`, `DELETE /api/projects/{id}/members/{userId}`.
- Roles are exactly `owner`, `editor`, `reviewer`, `viewer`.
- Every task ends with `npm run build` (tsc + vite build) passing AND `npm run test` (`vitest run`) passing, then a commit.
- Dev: Vite proxies `/api`, `/oauth2`, `/login` to `http://localhost:8080` so cookies/session work against a locally-running backend.

## File Structure

```
frontend/
  package.json
  tsconfig.json
  tsconfig.node.json
  vite.config.ts
  vitest.config.ts            # (merged into vite.config.ts via test field)
  index.html
  .gitignore
  src/
    main.tsx                  # entry: providers (QueryClientProvider, BrowserRouter, AntD App)
    App.tsx                   # routes
    vite-env.d.ts
    api/
      client.ts               # fetch wrapper: base URL, credentials, CSRF, JSON/form, ApiError
      types.ts                # Me, Project, Member, Role, request payloads
      auth.ts                 # getMe, localLogin, logout, orcidLoginUrl
      projects.ts             # listProjects, getProject, createProject, updateMetadata, listMembers, setMember, removeMember
    auth/
      useMe.ts                # useQuery(['me']) wrapper
      LoginPage.tsx
      RequireAuth.tsx
    projects/
      ProjectListPage.tsx
      CreateProjectModal.tsx
      ProjectSwitcher.tsx
      ProjectLayout.tsx       # loads a project, renders sub-nav (Metadata / Members)
      ProjectMetadataPage.tsx
      MembersPage.tsx
    components/
      AppLayout.tsx           # top bar (brand, project switcher, user menu), <Outlet/>
    test/
      setup.ts                # jest-dom, matchMedia mock, MSW server lifecycle
      server.ts               # MSW node server + default handlers
      utils.tsx               # renderWithProviders(ui, {route})
  src/**/*.test.tsx
```

---

### Task 1: Vite + React + TypeScript + Ant Design scaffold with a green test harness

**Files:**
- Create: `frontend/package.json`, `frontend/tsconfig.json`, `frontend/tsconfig.node.json`, `frontend/vite.config.ts`, `frontend/index.html`, `frontend/.gitignore`, `frontend/src/main.tsx`, `frontend/src/App.tsx`, `frontend/src/vite-env.d.ts`
- Create: `frontend/src/test/setup.ts`, `frontend/src/test/utils.tsx`
- Test: `frontend/src/App.test.tsx`

**Interfaces:**
- Produces: a runnable Vite app whose `App` renders an Ant Design layout with the brand text "ColDP Editor"; `npm run dev`, `npm run build`, `npm run test` scripts; a `renderWithProviders` test helper wrapping children in `QueryClientProvider` + `MemoryRouter` + AntD `App`.

- [ ] **Step 1: Create `package.json`**

`frontend/package.json`:

```json
{
  "name": "coldp-editor-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "@tanstack/react-query": "^5.59.0",
    "antd": "^5.21.0",
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.26.0"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "^6.5.0",
    "@testing-library/react": "^16.0.0",
    "@testing-library/user-event": "^14.5.0",
    "@types/react": "^18.3.0",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.0",
    "jsdom": "^25.0.0",
    "msw": "^2.4.0",
    "typescript": "^5.6.0",
    "vite": "^6.0.0",
    "vitest": "^2.1.0"
  }
}
```

- [ ] **Step 2: Create TS + Vite config**

`frontend/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "useDefineForClassFields": true,
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "types": ["vitest/globals", "@testing-library/jest-dom"]
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

`frontend/tsconfig.node.json`:

```json
{
  "compilerOptions": {
    "composite": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "allowSyntheticDefaultImports": true,
    "strict": true,
    "noEmit": true
  },
  "include": ["vite.config.ts"]
}
```

`frontend/vite.config.ts`:

```ts
/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/oauth2': 'http://localhost:8080',
      '/login': 'http://localhost:8080',
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
```

- [ ] **Step 3: Create `index.html`, entry, and app shell**

`frontend/index.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>ColDP Editor</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`frontend/src/vite-env.d.ts`:

```ts
/// <reference types="vite/client" />
```

`frontend/src/main.tsx`:

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { App as AntApp, ConfigProvider } from 'antd';
import App from './App';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
```

`frontend/src/App.tsx`:

```tsx
import { Layout, Typography } from 'antd';

export default function App() {
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header>
        <Typography.Title level={4} style={{ color: '#fff', margin: 0 }}>
          ColDP Editor
        </Typography.Title>
      </Layout.Header>
      <Layout.Content style={{ padding: 24 }}>Loading…</Layout.Content>
    </Layout>
  );
}
```

- [ ] **Step 4: Create the test harness**

`frontend/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

// Ant Design (and other libs) call matchMedia; jsdom doesn't implement it.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }),
});

// jsdom lacks getComputedStyle scrollbar measuring used by some AntD components.
window.HTMLElement.prototype.scrollIntoView = vi.fn();

afterEach(() => cleanup());
```

`frontend/src/test/utils.tsx`:

```tsx
import { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { App as AntApp, ConfigProvider } from 'antd';

export function renderWithProviders(ui: ReactElement, opts: { route?: string } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <MemoryRouter initialEntries={[opts.route ?? '/']}>{children}</MemoryRouter>
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>
  );
  return render(ui, { wrapper });
}
```

- [ ] **Step 5: Write the failing smoke test**

`frontend/src/App.test.tsx`:

```tsx
import { screen } from '@testing-library/react';
import { test, expect } from 'vitest';
import { renderWithProviders } from './test/utils';
import App from './App';

test('renders the ColDP Editor brand', () => {
  renderWithProviders(<App />);
  expect(screen.getByText('ColDP Editor')).toBeInTheDocument();
});
```

- [ ] **Step 6: Install, run test (fail→pass), build**

Run: `cd frontend && npm install`
Run: `cd frontend && npm run test`
Expected: PASS (1 test). If it fails first due to a missing file, create it and re-run.
Run: `cd frontend && npm run build`
Expected: build succeeds (dist/ produced).

- [ ] **Step 7: Commit**

```bash
git add frontend/ && git commit -m "feat(frontend): Vite+React+TS+AntD scaffold with Vitest harness"
```

---

### Task 2: API client with CSRF handling, types, and auth/project API functions

**Files:**
- Create: `frontend/src/api/client.ts`, `frontend/src/api/types.ts`, `frontend/src/api/auth.ts`, `frontend/src/api/projects.ts`
- Test: `frontend/src/api/client.test.ts`

**Interfaces:**
- Produces:
  - `ApiError` class with `status: number` and `message`.
  - `api<T>(path, opts?)` where `opts` = `{ method?, json?, form?: Record<string,string> }`; sends `credentials: 'include'`; for non-GET, ensures the `XSRF-TOKEN` cookie exists (GETs `/api/ping` if absent) and sets `X-XSRF-TOKEN`; throws `ApiError` on non-2xx (using `{error}` body when present); returns parsed JSON or `undefined` for 204.
  - `types.ts`: `Role = 'owner'|'editor'|'reviewer'|'viewer'`; `Me`, `Project`, `Member`, `CreateProjectPayload`, `UpdateMetadataPayload`.
  - `auth.ts`: `getMe(): Promise<Me>`, `localLogin(username, password): Promise<void>`, `logout(): Promise<void>`, `orcidLoginUrl(): string` (`'/oauth2/authorization/orcid'`).
  - `projects.ts`: `listProjects()`, `getProject(id)`, `createProject(payload)`, `updateMetadata(id, payload)`, `listMembers(id)`, `setMember(id, username, role)`, `removeMember(id, userId)`.

- [ ] **Step 1: Create the client**

`frontend/src/api/client.ts`:

```ts
const BASE = import.meta.env.VITE_API_BASE ?? '';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
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
}

export async function api<T>(path: string, opts: ApiOptions = {}): Promise<T> {
  const method = opts.method ?? 'GET';
  const headers: Record<string, string> = {};
  const init: RequestInit = { method, credentials: 'include', headers };

  if (method !== 'GET') {
    await ensureCsrfCookie();
    const token = readCookie('XSRF-TOKEN');
    if (token) headers['X-XSRF-TOKEN'] = token;
  }

  if (opts.form) {
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
```

- [ ] **Step 2: Create types and API functions**

`frontend/src/api/types.ts`:

```ts
export type Role = 'owner' | 'editor' | 'reviewer' | 'viewer';

export interface Me {
  id: number;
  username: string;
  orcid: string;
  displayName: string;
}

export interface Project {
  id: number;
  slug: string;
  title: string;
  alias: string | null;
  description: string | null;
  nomCode: string | null;
  license: string | null;
  version: string | null;
  issued: string | null;
  geographicScope: string | null;
  taxonomicScope: string | null;
  doi: string | null;
  role: Role;
}

export interface Member {
  userId: number;
  username: string;
  role: Role;
}

export interface CreateProjectPayload {
  slug: string;
  title: string;
  nomCode?: string;
}

export interface UpdateMetadataPayload {
  title: string;
  alias?: string;
  description?: string;
  nomCode?: string;
  license?: string;
  version?: string;
  issued?: string;
  geographicScope?: string;
  taxonomicScope?: string;
  doi?: string;
}
```

`frontend/src/api/auth.ts`:

```ts
import { api } from './client';
import type { Me } from './types';

export function getMe(): Promise<Me> {
  return api<Me>('/api/me');
}

export function localLogin(username: string, password: string): Promise<void> {
  return api<void>('/api/auth/login', { method: 'POST', form: { username, password } });
}

export function logout(): Promise<void> {
  return api<void>('/api/auth/logout', { method: 'POST' });
}

export function orcidLoginUrl(): string {
  return '/oauth2/authorization/orcid';
}
```

`frontend/src/api/projects.ts`:

```ts
import { api } from './client';
import type { CreateProjectPayload, Member, Project, Role, UpdateMetadataPayload } from './types';

export function listProjects(): Promise<Project[]> {
  return api<Project[]>('/api/projects');
}
export function getProject(id: number): Promise<Project> {
  return api<Project>(`/api/projects/${id}`);
}
export function createProject(payload: CreateProjectPayload): Promise<Project> {
  return api<Project>('/api/projects', { method: 'POST', json: payload });
}
export function updateMetadata(id: number, payload: UpdateMetadataPayload): Promise<Project> {
  return api<Project>(`/api/projects/${id}/metadata`, { method: 'PUT', json: payload });
}
export function listMembers(id: number): Promise<Member[]> {
  return api<Member[]>(`/api/projects/${id}/members`);
}
export function setMember(id: number, username: string, role: Role): Promise<void> {
  return api<void>(`/api/projects/${id}/members`, { method: 'PUT', json: { username, role } });
}
export function removeMember(id: number, userId: number): Promise<void> {
  return api<void>(`/api/projects/${id}/members/${userId}`, { method: 'DELETE' });
}
```

- [ ] **Step 3: Write the failing client test (CSRF behavior)**

`frontend/src/api/client.test.ts`:

```ts
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
```

- [ ] **Step 4: Run tests (fail→pass) and build**

Run: `cd frontend && npm run test`
Expected: PASS (App test + 3 client tests). The POST test relies on the cookie being present (set in `beforeEach`), so `ensureCsrfCookie` short-circuits without calling `/api/ping`.
Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api && git commit -m "feat(frontend): API client with CSRF, types, auth/project functions"
```

---

### Task 3: Authentication — login page, current-user hook, and route guard

**Files:**
- Create: `frontend/src/auth/useMe.ts`, `frontend/src/auth/LoginPage.tsx`, `frontend/src/auth/RequireAuth.tsx`
- Create: `frontend/src/test/server.ts` (MSW server)
- Modify: `frontend/src/test/setup.ts` (start/stop MSW server)
- Test: `frontend/src/auth/LoginPage.test.tsx`

**Interfaces:**
- Consumes: `getMe`, `localLogin`, `orcidLoginUrl` (Task 2).
- Produces:
  - `useMe()` → `useQuery({ queryKey: ['me'], queryFn: getMe })` (returns the TanStack result).
  - `LoginPage` — an ORCID link button (href `/oauth2/authorization/orcid`) and a local username/password form; on submit calls `localLogin`, invalidates `['me']`, and navigates to `/`; shows the error message on failure.
  - `RequireAuth` — renders a spinner while `useMe` loads, `<Outlet/>` if authenticated, and `<Navigate to="/login"/>` on 401/error.
  - `test/server.ts` — an MSW `setupServer(...)` with default handlers for `GET /api/me`, `GET /api/ping`, `POST /api/auth/login`, `GET /api/projects`, exported as `server` plus `http`/`HttpResponse` re-exports for per-test overrides.

- [ ] **Step 1: Create the MSW server and wire it into setup**

`frontend/src/test/server.ts`:

```ts
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';

export const server = setupServer(
  http.get('/api/ping', () => HttpResponse.json({ status: 'ok' })),
  http.get('/api/me', () => new HttpResponse(null, { status: 401 })),
  http.post('/api/auth/login', () => new HttpResponse(null, { status: 200 })),
  http.get('/api/projects', () => HttpResponse.json([])),
);

export { http, HttpResponse };
```

Append to `frontend/src/test/setup.ts`:

```ts
import { afterAll, afterEach as afterEachTest, beforeAll } from 'vitest';
import { server } from './server';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEachTest(() => server.resetHandlers());
afterAll(() => server.close());
```

- [ ] **Step 2: Create `useMe` and `RequireAuth`**

`frontend/src/auth/useMe.ts`:

```ts
import { useQuery } from '@tanstack/react-query';
import { getMe } from '../api/auth';

export function useMe() {
  return useQuery({ queryKey: ['me'], queryFn: getMe });
}
```

`frontend/src/auth/RequireAuth.tsx`:

```tsx
import { Navigate, Outlet } from 'react-router-dom';
import { Spin } from 'antd';
import { useMe } from './useMe';

export default function RequireAuth() {
  const { data, isLoading, isError } = useMe();
  if (isLoading) return <Spin style={{ margin: 48 }} />;
  if (isError || !data) return <Navigate to="/login" replace />;
  return <Outlet />;
}
```

- [ ] **Step 3: Create `LoginPage`**

`frontend/src/auth/LoginPage.tsx`:

```tsx
import { Alert, Button, Card, Divider, Form, Input, Space } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { localLogin, orcidLoginUrl } from '../api/auth';
import { ApiError } from '../api/client';

export default function LoginPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onFinish(values: { username: string; password: string }) {
    setSubmitting(true);
    setError(null);
    try {
      await localLogin(values.username, values.password);
      await queryClient.invalidateQueries({ queryKey: ['me'] });
      navigate('/', { replace: true });
    } catch (e) {
      setError(e instanceof ApiError && e.status === 401 ? 'Invalid username or password' : 'Login failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
      <Card title="Sign in to ColDP Editor" style={{ width: 380 }}>
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Button block type="primary" href={orcidLoginUrl()}>
            Sign in with ORCID
          </Button>
          <Divider plain>or</Divider>
          {error && <Alert type="error" message={error} showIcon />}
          <Form layout="vertical" onFinish={onFinish} disabled={submitting}>
            <Form.Item label="Username" name="username" rules={[{ required: true }]}>
              <Input autoComplete="username" />
            </Form.Item>
            <Form.Item label="Password" name="password" rules={[{ required: true }]}>
              <Input.Password autoComplete="current-password" />
            </Form.Item>
            <Button block htmlType="submit" loading={submitting}>
              Sign in
            </Button>
          </Form>
        </Space>
      </Card>
    </div>
  );
}
```

- [ ] **Step 4: Write the failing login test**

`frontend/src/auth/LoginPage.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import LoginPage from './LoginPage';

test('ORCID button links to the Spring OIDC authorization endpoint', () => {
  renderWithProviders(<LoginPage />);
  expect(screen.getByRole('link', { name: /sign in with orcid/i })).toHaveAttribute(
    'href',
    '/oauth2/authorization/orcid',
  );
});

test('shows an error when local login is rejected', async () => {
  server.use(http.post('/api/auth/login', () => new HttpResponse(null, { status: 401 })));
  renderWithProviders(<LoginPage />);
  await userEvent.type(screen.getByLabelText(/username/i), 'alice');
  await userEvent.type(screen.getByLabelText(/password/i), 'wrong');
  await userEvent.click(screen.getByRole('button', { name: /^sign in$/i }));
  await waitFor(() =>
    expect(screen.getByText(/invalid username or password/i)).toBeInTheDocument(),
  );
});
```

- [ ] **Step 5: Run tests (fail→pass) and build**

Run: `cd frontend && npm run test`
Expected: PASS. (The success-path navigation is covered end-to-end in Task 4's routing test; here we assert the ORCID link and the error path.)
Run: `cd frontend && npm run build`
Expected: succeeds.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/auth frontend/src/test && git commit -m "feat(frontend): auth — login page, useMe hook, route guard, MSW server"
```

---

### Task 4: App layout, routing, project list, and project creation

**Files:**
- Create: `frontend/src/components/AppLayout.tsx`, `frontend/src/projects/ProjectListPage.tsx`, `frontend/src/projects/CreateProjectModal.tsx`, `frontend/src/projects/ProjectSwitcher.tsx`
- Modify: `frontend/src/App.tsx` (real routes)
- Test: `frontend/src/projects/ProjectListPage.test.tsx`, `frontend/src/AppRouting.test.tsx`

**Interfaces:**
- Consumes: `useMe`, `RequireAuth`, `LoginPage` (Task 3); `listProjects`, `createProject` (Task 2); `logout` (Task 2).
- Produces:
  - `AppLayout` — top bar with brand "ColDP Editor", a `ProjectSwitcher`, and a user menu showing the display name with a Logout item (calls `logout`, invalidates `['me']`, navigates to `/login`); renders `<Outlet/>`.
  - `ProjectListPage` — lists the user's projects (title + role tag), each linking to `/projects/{id}/metadata`; a "New project" button opening `CreateProjectModal`.
  - `CreateProjectModal` — form (slug, title, nomCode select from the four codes: `zoological`, `botanical`, `virus`, `bacterial`, `cultivars`, `phytosociological`); on success invalidates `['projects']`, closes, navigates to the new project.
  - `ProjectSwitcher` — a Select of the user's projects that navigates to the chosen project's metadata page.
  - `App` routes: `/login` → `LoginPage`; `/` guarded by `RequireAuth`+`AppLayout` with an index route → `ProjectListPage`.

- [ ] **Step 1: Create `App` routes**

`frontend/src/App.tsx` (replace entirely):

```tsx
import { Navigate, Route, Routes } from 'react-router-dom';
import LoginPage from './auth/LoginPage';
import RequireAuth from './auth/RequireAuth';
import AppLayout from './components/AppLayout';
import ProjectListPage from './projects/ProjectListPage';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route index element={<ProjectListPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
```

- [ ] **Step 2: Create `AppLayout` and `ProjectSwitcher`**

`frontend/src/components/AppLayout.tsx`:

```tsx
import { Dropdown, Layout, Space, Typography } from 'antd';
import { Link, Outlet, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useMe } from '../auth/useMe';
import { logout } from '../api/auth';
import ProjectSwitcher from '../projects/ProjectSwitcher';

export default function AppLayout() {
  const { data: me } = useMe();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  async function onLogout() {
    await logout();
    await queryClient.invalidateQueries({ queryKey: ['me'] });
    navigate('/login', { replace: true });
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
        <Link to="/">
          <Typography.Text strong style={{ color: '#fff' }}>
            ColDP Editor
          </Typography.Text>
        </Link>
        <ProjectSwitcher />
        <div style={{ marginLeft: 'auto' }}>
          <Dropdown
            menu={{ items: [{ key: 'logout', label: 'Logout', onClick: onLogout }] }}
          >
            <Typography.Text style={{ color: '#fff', cursor: 'pointer' }}>
              {me?.displayName || me?.username}
            </Typography.Text>
          </Dropdown>
        </div>
      </Layout.Header>
      <Layout.Content style={{ padding: 24 }}>
        <Outlet />
      </Layout.Content>
    </Layout>
  );
}
```

`frontend/src/projects/ProjectSwitcher.tsx`:

```tsx
import { Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { listProjects } from '../api/projects';

export default function ProjectSwitcher() {
  const navigate = useNavigate();
  const { data } = useQuery({ queryKey: ['projects'], queryFn: listProjects });
  return (
    <Select
      placeholder="Switch project"
      style={{ minWidth: 200 }}
      value={null}
      options={(data ?? []).map((p) => ({ value: p.id, label: p.title }))}
      onChange={(id) => navigate(`/projects/${id}/metadata`)}
    />
  );
}
```

- [ ] **Step 3: Create `ProjectListPage` and `CreateProjectModal`**

`frontend/src/projects/CreateProjectModal.tsx`:

```tsx
import { Form, Input, Modal, Select } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { createProject } from '../api/projects';
import type { CreateProjectPayload } from '../api/types';

export const NOM_CODES = ['zoological', 'botanical', 'virus', 'bacterial', 'cultivars', 'phytosociological'];

export default function CreateProjectModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [form] = Form.useForm<CreateProjectPayload>();
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const mutation = useMutation({
    mutationFn: (values: CreateProjectPayload) => createProject(values),
    onSuccess: async (project) => {
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      form.resetFields();
      onClose();
      navigate(`/projects/${project.id}/metadata`);
    },
  });

  return (
    <Modal
      open={open}
      title="New project"
      okText="Create"
      confirmLoading={mutation.isPending}
      onOk={() => form.submit()}
      onCancel={onClose}
    >
      <Form form={form} layout="vertical" onFinish={(v) => mutation.mutate(v)}>
        <Form.Item label="Slug" name="slug" rules={[{ required: true }]}>
          <Input placeholder="lepidoptera" />
        </Form.Item>
        <Form.Item label="Title" name="title" rules={[{ required: true }]}>
          <Input placeholder="Lepidoptera of the World" />
        </Form.Item>
        <Form.Item label="Nomenclatural code" name="nomCode">
          <Select allowClear options={NOM_CODES.map((c) => ({ value: c, label: c }))} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
```

`frontend/src/projects/ProjectListPage.tsx`:

```tsx
import { Button, List, Tag, Typography } from 'antd';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listProjects } from '../api/projects';
import CreateProjectModal from './CreateProjectModal';

export default function ProjectListPage() {
  const [creating, setCreating] = useState(false);
  const { data, isLoading } = useQuery({ queryKey: ['projects'], queryFn: listProjects });

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>
          My projects
        </Typography.Title>
        <Button type="primary" onClick={() => setCreating(true)}>
          New project
        </Button>
      </div>
      <List
        loading={isLoading}
        bordered
        dataSource={data ?? []}
        locale={{ emptyText: 'No projects yet' }}
        renderItem={(p) => (
          <List.Item actions={[<Tag key="role">{p.role}</Tag>]}>
            <Link to={`/projects/${p.id}/metadata`}>{p.title}</Link>
          </List.Item>
        )}
      />
      <CreateProjectModal open={creating} onClose={() => setCreating(false)} />
    </div>
  );
}
```

- [ ] **Step 4: Write the failing tests**

`frontend/src/projects/ProjectListPage.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ProjectListPage from './ProjectListPage';

test('lists the user projects with their role', async () => {
  server.use(
    http.get('/api/projects', () =>
      HttpResponse.json([{ id: 1, slug: 'aves', title: 'Birds', role: 'owner' }]),
    ),
  );
  renderWithProviders(<ProjectListPage />);
  expect(await screen.findByText('Birds')).toBeInTheDocument();
  expect(screen.getByText('owner')).toBeInTheDocument();
});

test('shows empty state when there are no projects', async () => {
  renderWithProviders(<ProjectListPage />); // default handler returns []
  await waitFor(() => expect(screen.getByText('No projects yet')).toBeInTheDocument());
});
```

`frontend/src/AppRouting.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from './test/utils';
import { server, http, HttpResponse } from './test/server';
import App from './App';

test('unauthenticated user is redirected to the login page', async () => {
  // default /api/me handler returns 401
  renderWithProviders(<App />, { route: '/' });
  expect(await screen.findByText(/sign in to coldp editor/i)).toBeInTheDocument();
});

test('authenticated user sees the project list inside the app layout', async () => {
  server.use(
    http.get('/api/me', () =>
      HttpResponse.json({ id: 1, username: 'alice', orcid: '', displayName: 'Alice' }),
    ),
    http.get('/api/projects', () =>
      HttpResponse.json([{ id: 7, slug: 'lep', title: 'Lepidoptera', role: 'editor' }]),
    ),
  );
  renderWithProviders(<App />, { route: '/' });
  expect(await screen.findByText('Lepidoptera')).toBeInTheDocument();
  await waitFor(() => expect(screen.getByText('Alice')).toBeInTheDocument());
});
```

- [ ] **Step 5: Run tests (fail→pass) and build**

Run: `cd frontend && npm run test`
Expected: PASS (routing redirect + authed layout + list + empty). Note MSW default `onUnhandledRequest: 'error'` requires every fetched path to have a handler — the default `server.ts` handlers plus per-test `server.use` overrides cover them.
Run: `cd frontend && npm run build`
Expected: succeeds.

- [ ] **Step 6: Commit**

```bash
git add frontend/src && git commit -m "feat(frontend): app layout, routing, project list + create, switcher"
```

---

### Task 5: Project detail layout and ColDP metadata editing

**Files:**
- Create: `frontend/src/projects/ProjectLayout.tsx`, `frontend/src/projects/ProjectMetadataPage.tsx`
- Modify: `frontend/src/App.tsx` (nested project routes)
- Test: `frontend/src/projects/ProjectMetadataPage.test.tsx`

**Interfaces:**
- Consumes: `getProject`, `updateMetadata` (Task 2); `NOM_CODES` (Task 4).
- Produces:
  - `ProjectLayout` — reads `:projectId` from the route, loads the project (`['project', id]`), shows its title and a tab-style nav (Metadata / Members) via nested routes; renders `<Outlet/>`. Shows a spinner while loading and an error alert on failure.
  - `ProjectMetadataPage` — an Ant Design form pre-filled from the loaded project (title, alias, description, nomCode select, license, version, issued, geographicScope, taxonomicScope, doi); Save calls `updateMetadata`, invalidates `['project', id]`, and shows a success message.
  - `App`: nested routes under `/projects/:projectId` → `ProjectLayout` with children `metadata` → `ProjectMetadataPage` (and a placeholder for `members`, wired fully in Task 6).

- [ ] **Step 1: Add nested project routes to `App`**

Update `frontend/src/App.tsx` — add the project routes inside the `AppLayout` route:

```tsx
import { Navigate, Route, Routes } from 'react-router-dom';
import LoginPage from './auth/LoginPage';
import RequireAuth from './auth/RequireAuth';
import AppLayout from './components/AppLayout';
import ProjectListPage from './projects/ProjectListPage';
import ProjectLayout from './projects/ProjectLayout';
import ProjectMetadataPage from './projects/ProjectMetadataPage';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route index element={<ProjectListPage />} />
          <Route path="projects/:projectId" element={<ProjectLayout />}>
            <Route index element={<Navigate to="metadata" replace />} />
            <Route path="metadata" element={<ProjectMetadataPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
```

- [ ] **Step 2: Create `ProjectLayout`**

`frontend/src/projects/ProjectLayout.tsx`:

```tsx
import { Alert, Spin, Tabs, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { Outlet, useNavigate, useParams, useLocation } from 'react-router-dom';
import { getProject } from '../api/projects';

export default function ProjectLayout() {
  const { projectId } = useParams();
  const id = Number(projectId);
  const navigate = useNavigate();
  const location = useLocation();
  const { data, isLoading, isError } = useQuery({
    queryKey: ['project', id],
    queryFn: () => getProject(id),
    enabled: Number.isFinite(id),
  });

  if (isLoading) return <Spin style={{ margin: 48 }} />;
  if (isError || !data) return <Alert type="error" showIcon message="Project not found" />;

  const active = location.pathname.endsWith('/members') ? 'members' : 'metadata';
  return (
    <div>
      <Typography.Title level={3}>{data.title}</Typography.Title>
      <Tabs
        activeKey={active}
        onChange={(k) => navigate(`/projects/${id}/${k}`)}
        items={[
          { key: 'metadata', label: 'Metadata' },
          { key: 'members', label: 'Members' },
        ]}
      />
      <Outlet />
    </div>
  );
}
```

- [ ] **Step 3: Create `ProjectMetadataPage`**

`frontend/src/projects/ProjectMetadataPage.tsx`:

```tsx
import { Button, Col, Form, Input, Row, Select, App as AntApp } from 'antd';
import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getProject, updateMetadata } from '../api/projects';
import type { UpdateMetadataPayload } from '../api/types';
import { NOM_CODES } from './CreateProjectModal';

export default function ProjectMetadataPage() {
  const { projectId } = useParams();
  const id = Number(projectId);
  const [form] = Form.useForm<UpdateMetadataPayload>();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();

  const { data } = useQuery({ queryKey: ['project', id], queryFn: () => getProject(id) });

  useEffect(() => {
    if (data) form.setFieldsValue(data as UpdateMetadataPayload);
  }, [data, form]);

  const mutation = useMutation({
    mutationFn: (values: UpdateMetadataPayload) => updateMetadata(id, values),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['project', id] });
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      message.success('Saved');
    },
    onError: () => message.error('Save failed'),
  });

  return (
    <Form form={form} layout="vertical" onFinish={(v) => mutation.mutate(v)} style={{ maxWidth: 720 }}>
      <Form.Item label="Title" name="title" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Row gutter={16}>
        <Col span={12}>
          <Form.Item label="Alias" name="alias"><Input /></Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item label="Nomenclatural code" name="nomCode">
            <Select allowClear options={NOM_CODES.map((c) => ({ value: c, label: c }))} />
          </Form.Item>
        </Col>
      </Row>
      <Form.Item label="Description" name="description"><Input.TextArea rows={3} /></Form.Item>
      <Row gutter={16}>
        <Col span={8}><Form.Item label="License" name="license"><Input /></Form.Item></Col>
        <Col span={8}><Form.Item label="Version" name="version"><Input /></Form.Item></Col>
        <Col span={8}><Form.Item label="Issued" name="issued"><Input placeholder="YYYY-MM-DD" /></Form.Item></Col>
      </Row>
      <Row gutter={16}>
        <Col span={12}><Form.Item label="Geographic scope" name="geographicScope"><Input /></Form.Item></Col>
        <Col span={12}><Form.Item label="Taxonomic scope" name="taxonomicScope"><Input /></Form.Item></Col>
      </Row>
      <Form.Item label="DOI" name="doi"><Input /></Form.Item>
      <Button type="primary" htmlType="submit" loading={mutation.isPending}>
        Save
      </Button>
    </Form>
  );
}
```

- [ ] **Step 4: Write the failing metadata test**

`frontend/src/projects/ProjectMetadataPage.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ProjectMetadataPage from './ProjectMetadataPage';

const project = {
  id: 3, slug: 'mam', title: 'Mammals', alias: null, description: null, nomCode: 'zoological',
  license: null, version: null, issued: null, geographicScope: null, taxonomicScope: null, doi: null, role: 'owner',
};

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/metadata" element={<ProjectMetadataPage />} />
    </Routes>,
    { route: '/projects/3/metadata' },
  );
}

test('prefills the form and saves updated metadata', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
    http.put('/api/projects/3/metadata', async ({ request }) => {
      const body = (await request.json()) as { title: string };
      return HttpResponse.json({ ...project, title: body.title });
    }),
  );
  renderPage();
  const title = await screen.findByLabelText('Title');
  await waitFor(() => expect(title).toHaveValue('Mammals'));
  await userEvent.clear(title);
  await userEvent.type(title, 'Mammalia');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));
  await waitFor(() => expect(screen.getByText('Saved')).toBeInTheDocument());
});
```

- [ ] **Step 5: Run tests (fail→pass) and build**

Run: `cd frontend && npm run test`
Expected: PASS.
Run: `cd frontend && npm run build`
Expected: succeeds.

- [ ] **Step 6: Commit**

```bash
git add frontend/src && git commit -m "feat(frontend): project detail layout + ColDP metadata editing"
```

---

### Task 6: Member management UI

**Files:**
- Create: `frontend/src/projects/MembersPage.tsx`
- Modify: `frontend/src/App.tsx` (add the `members` route)
- Test: `frontend/src/projects/MembersPage.test.tsx`

**Interfaces:**
- Consumes: `listMembers`, `setMember`, `removeMember` (Task 2); `getProject` (to know the caller's role); `Role` (Task 2).
- Produces:
  - `MembersPage` — a table of members (username, role selector, remove button). Adding: a username Input + role Select (the four roles) + Add button (`setMember`). Changing a role uses the per-row Select (`setMember`). Remove uses `removeMember` with a confirm popconfirm. All mutations invalidate `['members', id]`. Add/remove/role controls are enabled only when the caller's role on the project is `owner` (from `getProject().role`); otherwise the table is read-only.
  - `App`: `members` child route under `/projects/:projectId` → `MembersPage`.

- [ ] **Step 1: Add the members route to `App`**

In `frontend/src/App.tsx`, add inside the `projects/:projectId` route (after the `metadata` route), and import `MembersPage`:

```tsx
          <Route path="projects/:projectId" element={<ProjectLayout />}>
            <Route index element={<Navigate to="metadata" replace />} />
            <Route path="metadata" element={<ProjectMetadataPage />} />
            <Route path="members" element={<MembersPage />} />
          </Route>
```
(add `import MembersPage from './projects/MembersPage';` at the top.)

- [ ] **Step 2: Create `MembersPage`**

`frontend/src/projects/MembersPage.tsx`:

```tsx
import { Button, Form, Input, Popconfirm, Select, Space, Table, App as AntApp } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getProject, listMembers, removeMember, setMember } from '../api/projects';
import type { Member, Role } from '../api/types';

const ROLES: Role[] = ['owner', 'editor', 'reviewer', 'viewer'];

export default function MembersPage() {
  const { projectId } = useParams();
  const id = Number(projectId);
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<{ username: string; role: Role }>();

  const { data: project } = useQuery({ queryKey: ['project', id], queryFn: () => getProject(id) });
  const { data: members, isLoading } = useQuery({ queryKey: ['members', id], queryFn: () => listMembers(id) });
  const canManage = project?.role === 'owner';

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['members', id] });

  const setMut = useMutation({
    mutationFn: ({ username, role }: { username: string; role: Role }) => setMember(id, username, role),
    onSuccess: async () => { await invalidate(); form.resetFields(); },
    onError: () => message.error('Could not update member'),
  });
  const removeMut = useMutation({
    mutationFn: (userId: number) => removeMember(id, userId),
    onSuccess: invalidate,
    onError: () => message.error('Could not remove member'),
  });

  return (
    <div>
      {canManage && (
        <Form form={form} layout="inline" style={{ marginBottom: 16 }} onFinish={(v) => setMut.mutate(v)}>
          <Form.Item name="username" rules={[{ required: true }]}>
            <Input placeholder="username" />
          </Form.Item>
          <Form.Item name="role" initialValue="editor" rules={[{ required: true }]}>
            <Select style={{ width: 140 }} options={ROLES.map((r) => ({ value: r, label: r }))} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={setMut.isPending}>
            Add / update
          </Button>
        </Form>
      )}
      <Table<Member>
        rowKey="userId"
        loading={isLoading}
        dataSource={members ?? []}
        pagination={false}
        columns={[
          { title: 'Username', dataIndex: 'username' },
          {
            title: 'Role',
            dataIndex: 'role',
            render: (role: Role, m) =>
              canManage ? (
                <Select
                  value={role}
                  style={{ width: 140 }}
                  options={ROLES.map((r) => ({ value: r, label: r }))}
                  onChange={(r) => setMut.mutate({ username: m.username, role: r })}
                />
              ) : (
                role
              ),
          },
          {
            title: '',
            key: 'actions',
            render: (_, m) =>
              canManage ? (
                <Popconfirm title="Remove member?" onConfirm={() => removeMut.mutate(m.userId)}>
                  <Button danger size="small">Remove</Button>
                </Popconfirm>
              ) : null,
          },
        ]}
      />
    </div>
  );
}
```

- [ ] **Step 3: Write the failing members test**

`frontend/src/projects/MembersPage.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import MembersPage from './MembersPage';

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/members" element={<MembersPage />} />
    </Routes>,
    { route: '/projects/5/members' },
  );
}

test('owner can see the add form and members list', async () => {
  server.use(
    http.get('/api/projects/5', () =>
      HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'owner' }),
    ),
    http.get('/api/projects/5/members', () =>
      HttpResponse.json([{ userId: 1, username: 'boss', role: 'owner' }]),
    ),
  );
  renderPage();
  expect(await screen.findByText('boss')).toBeInTheDocument();
  expect(screen.getByPlaceholderText('username')).toBeInTheDocument();
});

test('non-owner sees a read-only list (no add form)', async () => {
  server.use(
    http.get('/api/projects/5', () =>
      HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'editor' }),
    ),
    http.get('/api/projects/5/members', () =>
      HttpResponse.json([{ userId: 1, username: 'boss', role: 'owner' }]),
    ),
  );
  renderPage();
  expect(await screen.findByText('boss')).toBeInTheDocument();
  expect(screen.queryByPlaceholderText('username')).not.toBeInTheDocument();
});

test('owner can add a member', async () => {
  let putBody: unknown = null;
  server.use(
    http.get('/api/projects/5', () =>
      HttpResponse.json({ id: 5, slug: 's', title: 'T', role: 'owner' }),
    ),
    http.get('/api/projects/5/members', () => HttpResponse.json([])),
    http.put('/api/projects/5/members', async ({ request }) => {
      putBody = await request.json();
      return new HttpResponse(null, { status: 200 });
    }),
  );
  renderPage();
  await screen.findByPlaceholderText('username');
  await userEvent.type(screen.getByPlaceholderText('username'), 'helper');
  await userEvent.click(screen.getByRole('button', { name: /add \/ update/i }));
  await waitFor(() => expect(putBody).toEqual({ username: 'helper', role: 'editor' }));
});
```

- [ ] **Step 4: Run tests (fail→pass) and build**

Run: `cd frontend && npm run test`
Expected: PASS (all frontend tests across tasks).
Run: `cd frontend && npm run build`
Expected: succeeds.

- [ ] **Step 5: Commit**

```bash
git add frontend/src && git commit -m "feat(frontend): project member management UI"
```

---

## Self-Review Notes

- **Spec coverage (phase-1 item 1 UI + metadata):** project switcher (Task 4 `ProjectSwitcher`), multi-project list/create (Task 4), ORCID + local login (Task 3), logout/user menu (Task 4), project ColDP metadata editing incl. `nom_code` (Task 5), per-project role-gated member management (Task 6). Backend REST + auth are Plan 1.
- **CSRF carry-forward** from Plan 1's final review is handled in the Task 2 client (`ensureCsrfCookie` + `X-XSRF-TOKEN`).
- **Deferred to later plans:** the classification tree, name/reference/usage editing, audit/locks, validation UI — Plans 3–6.
- **Manual verification** (beyond tests): `cd backend && mvn spring-boot:run` (with a local Postgres and ORCID creds or the `unconfigured` placeholder) + `cd frontend && npm run dev`, then log in locally and exercise project create/metadata/members in the browser. The Vite proxy forwards `/api`,`/oauth2`,`/login` to `:8080`.
- **Known test-harness notes for implementers:** AntD needs the `window.matchMedia` mock (in `test/setup.ts`); MSW runs with `onUnhandledRequest:'error'`, so every path a component fetches must have a handler (defaults in `server.ts` + per-test `server.use`).
