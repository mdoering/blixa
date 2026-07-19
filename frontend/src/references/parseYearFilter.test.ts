import { expect, test } from 'vitest';
import { parseYearFilter } from './parseYearFilter';

test('blank input yields no bounds', () => {
  expect(parseYearFilter('')).toEqual({});
  expect(parseYearFilter('   ')).toEqual({});
});

test('a single year becomes an exact (from == to) range', () => {
  expect(parseYearFilter('1941')).toEqual({ yearFrom: 1941, yearTo: 1941 });
});

test('a hyphenated range parses both bounds', () => {
  expect(parseYearFilter('1941-1944')).toEqual({ yearFrom: 1941, yearTo: 1944 });
});

test('surrounding and inner whitespace is tolerated', () => {
  expect(parseYearFilter('  1941 - 1944 ')).toEqual({ yearFrom: 1941, yearTo: 1944 });
});

test('open-ended ranges keep only the given bound', () => {
  expect(parseYearFilter('1941-')).toEqual({ yearFrom: 1941 });
  expect(parseYearFilter('-1944')).toEqual({ yearTo: 1944 });
});

test('non-numeric input yields no bounds', () => {
  expect(parseYearFilter('abc')).toEqual({});
  expect(parseYearFilter('19x')).toEqual({});
});
