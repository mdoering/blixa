import { expect, test } from 'vitest';
import { usageExportTsvUrl } from './usages';
import { referenceExportTsvUrl } from './references';

test('usageExportTsvUrl serialises only the set filters', () => {
  expect(usageExportTsvUrl(7, { q: 'Abies', rank: 'species', status: 'ACCEPTED' })).toBe(
    '/api/projects/7/usages/export.tsv?q=Abies&rank=species&status=ACCEPTED',
  );
  // blank / null filters are omitted, leaving a bare export URL
  expect(usageExportTsvUrl(7, {})).toBe('/api/projects/7/usages/export.tsv');
  expect(usageExportTsvUrl(7, { q: '  ', rank: null, status: null })).toBe(
    '/api/projects/7/usages/export.tsv',
  );
});

test('referenceExportTsvUrl serialises only the set filters', () => {
  expect(referenceExportTsvUrl(7, { q: 'Darwin', yearFrom: 1850, yearTo: 1860 })).toBe(
    '/api/projects/7/references/export.tsv?q=Darwin&yearFrom=1850&yearTo=1860',
  );
  expect(referenceExportTsvUrl(7, {})).toBe('/api/projects/7/references/export.tsv');
});
