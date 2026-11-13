import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
import { setUnauthorizedHandler } from '../api/api';

/**
 * Some Node versions ship an experimental `localStorage` global that shadows jsdom's. Install a
 * real in-memory Storage so the token and cart modules behave as they do in a browser.
 */
function installLocalStorage(): void {
  const existing: Storage | undefined = window.localStorage;
  if (existing !== undefined && typeof existing.getItem === 'function') {
    return;
  }
  const store = new Map<string, string>();
  const storage: Storage = {
    get length(): number {
      return store.size;
    },
    clear: () => {
      store.clear();
    },
    getItem: (key: string) => store.get(key) ?? null,
    key: (index: number) => [...store.keys()][index] ?? null,
    removeItem: (key: string) => {
      store.delete(key);
    },
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
  };
  Object.defineProperty(window, 'localStorage', { configurable: true, value: storage });
}

installLocalStorage();

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  cleanup();
  setUnauthorizedHandler(null);
  window.localStorage.clear();
});
