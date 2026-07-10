import { describe, expect, test } from 'vitest';
import { areaGeojsonUrl, colIdFrom, gbifTileUrl } from './mapUrls';

const UUID = '7ddf754f-d193-4cc9-b351-99906754a03b';

describe('colIdFrom', () => {
  test('strips the col: prefix and returns the id', () => {
    expect(colIdFrom(['tsn:1', 'col:6W3C4'])).toBe('6W3C4');
  });
  test('is case-insensitive on the prefix', () => {
    expect(colIdFrom(['COL:6W3C4'])).toBe('6W3C4');
  });
  test('returns null when no col: entry is present', () => {
    expect(colIdFrom(['tsn:1'])).toBeNull();
  });
  test('returns null for empty / missing input', () => {
    expect(colIdFrom([])).toBeNull();
    expect(colIdFrom(null)).toBeNull();
    expect(colIdFrom(undefined)).toBeNull();
  });
});

describe('gbifTileUrl', () => {
  test('contains the density path and the checklistKey + taxonKey values', () => {
    const url = gbifTileUrl('6W3C4', UUID);
    expect(url).toContain('/v2/map/occurrence/density/{z}/{x}/{y}@1x.png');
    expect(url).toContain(`checklistKey=${UUID}`);
    expect(url).toContain('taxonKey=6W3C4');
    expect(url.startsWith('https://api.gbif.org')).toBe(true);
  });
  test('url-encodes the taxonKey value', () => {
    expect(gbifTileUrl('a b', UUID)).toContain('taxonKey=a%20b');
  });
});

describe('areaGeojsonUrl', () => {
  test('builds the exact gazetteer:areaId vocab URL', () => {
    expect(areaGeojsonUrl('tdwg', 'AB')).toBe(
      'https://api.checklistbank.org/vocab/area/tdwg:AB',
    );
  });
});
