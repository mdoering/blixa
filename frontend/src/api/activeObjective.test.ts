import { afterEach, expect, test } from 'vitest';
import { readActiveObjectiveId, writeActiveObjectiveId } from './activeObjective';

afterEach(() => localStorage.clear());

test('defaults to null (no objective) for a project', () => {
  expect(readActiveObjectiveId(7)).toBeNull();
});

test('round-trips an active objective id per project', () => {
  writeActiveObjectiveId(7, 42);
  expect(readActiveObjectiveId(7)).toBe(42);
  // scoped per project — another project is unaffected
  expect(readActiveObjectiveId(8)).toBeNull();
});

test('clearing to null removes it', () => {
  writeActiveObjectiveId(7, 42);
  writeActiveObjectiveId(7, null);
  expect(readActiveObjectiveId(7)).toBeNull();
});

test('a garbage stored value reads as null', () => {
  localStorage.setItem('coldp-active-objective-7', 'notanumber');
  expect(readActiveObjectiveId(7)).toBeNull();
});
