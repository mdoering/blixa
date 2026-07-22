import { Blob, File } from 'node:buffer';
import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import { server } from './server';

// Vitest's jsdom test environment substitutes its own File/FormData/Blob/Headers
// implementations for Node's (see vitest's populateGlobal), which Node's native
// fetch/Request body-extraction algorithm doesn't recognize (it duck-types Blob/FormData
// against the global Blob/FormData -- see undici's isBlobLike) -- a multipart FormData
// upload built with `new FormData()` then silently loses its Content-Type header under
// test (affects ImportProjectModal's file upload and any future multipart client code).
// Re-point these globals at one consistent set: Node's own native Blob/File (unaffected
// by jsdom's override, reached via node:buffer) plus the standalone `undici` package's
// fetch/FormData/Headers/Request/Response, so `new FormData()` + `fetch()` behave the
// same under test as they do in a real browser (where these were already consistent;
// production code is unaffected by this file). Blob/File must land on globalThis BEFORE
// `undici` is first imported below: its webidl layer captures the ambient Blob/File
// classes once, at that module's own load time, to brand-check FormData/File values --
// importing it any earlier (e.g. as a static top-of-file import, which ES modules
// resolve before this file's own code runs) would have it capture jsdom's classes
// instead, silently turning every uploaded File into an "[object File]" string field.
Object.assign(globalThis, { Blob, File });
const { fetch, FormData, Headers, Request, Response } = await import('undici');
Object.assign(globalThis, { fetch, FormData, Headers, Request, Response });

// Mantine (and other libs) call matchMedia; jsdom doesn't implement it.
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

// jsdom lacks ResizeObserver, used by Mantine / Mantine React Table / TanStack Virtual.
window.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};

// jsdom lacks getComputedStyle scrollbar measuring used by some Mantine components.
window.HTMLElement.prototype.scrollIntoView = vi.fn();

// jsdom (without a --localstorage-file) doesn't provide localStorage; supply a minimal in-memory
// Storage so components that persist small UI preferences (e.g. CollapsibleSplit's collapsed flag)
// work under test and can be asserted on. Cleared after each test to avoid cross-test leakage.
const memoryStorage = new (class {
  private store = new Map<string, string>();
  get length() {
    return this.store.size;
  }
  clear() {
    this.store.clear();
  }
  getItem(key: string) {
    return this.store.has(key) ? (this.store.get(key) as string) : null;
  }
  setItem(key: string, value: string) {
    this.store.set(key, String(value));
  }
  removeItem(key: string) {
    this.store.delete(key);
  }
  key(index: number) {
    return Array.from(this.store.keys())[index] ?? null;
  }
})();
Object.defineProperty(window, 'localStorage', { writable: true, value: memoryStorage });
Object.assign(globalThis, { localStorage: memoryStorage });

afterEach(() => cleanup());
afterEach(() => memoryStorage.clear());

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
