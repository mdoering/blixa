import { describe, expect, test } from 'vitest';
import {
  areaGeojsonUrl,
  colIdFrom,
  gbifCountUrl,
  gbifTileUrl,
  scopedId,
  withColId,
  withScopedId,
} from './mapUrls';

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

describe('withColId', () => {
  test('appends col:<id> to a list with no existing col: entry', () => {
    expect(withColId(['tsn:1'], '6W3C4')).toEqual(['tsn:1', 'col:6W3C4']);
  });
  test('drops an existing col: entry (case-insensitive) and appends the new one', () => {
    expect(withColId(['tsn:1', 'COL:OLD1', 'gbif:2'], '6W3C4')).toEqual([
      'tsn:1',
      'gbif:2',
      'col:6W3C4',
    ]);
  });
  test('works from an empty list', () => {
    expect(withColId([], '6W3C4')).toEqual(['col:6W3C4']);
  });
});

describe('scopedId', () => {
  test('strips the <scope>: prefix and returns the id', () => {
    expect(scopedId(['col:X', 'ipni:123'], 'ipni')).toBe('123');
  });
  test('is case-insensitive on the scope', () => {
    expect(scopedId(['IPNI:123'], 'ipni')).toBe('123');
    expect(scopedId(['ipni:123'], 'IPNI')).toBe('123');
  });
  test('returns null when no matching scope entry is present', () => {
    expect(scopedId(['col:X'], 'ipni')).toBeNull();
  });
  test('returns null for empty / missing input', () => {
    expect(scopedId([], 'ipni')).toBeNull();
    expect(scopedId(null, 'ipni')).toBeNull();
    expect(scopedId(undefined, 'ipni')).toBeNull();
  });
});

describe('withScopedId', () => {
  test('replaces an existing scope entry while preserving other entries (incl. col:)', () => {
    expect(withScopedId(['col:X', 'ipni:1'], 'ipni', '2')).toEqual(['col:X', 'ipni:2']);
  });
  test('is case-insensitive on the scope it replaces', () => {
    expect(withScopedId(['col:X', 'IPNI:1'], 'ipni', '2')).toEqual(['col:X', 'ipni:2']);
  });
  test('appends a new scope entry to a list with no existing entry for it', () => {
    expect(withScopedId(['col:X'], 'ipni', '123')).toEqual(['col:X', 'ipni:123']);
  });
  test('an empty value drops/omits the scope entry rather than appending an empty one', () => {
    expect(withScopedId(['col:X'], 'ipni', '')).toEqual(['col:X']);
    expect(withScopedId(['col:X', 'ipni:1'], 'ipni', '')).toEqual(['col:X']);
  });
  test('works from an empty list', () => {
    expect(withScopedId([], 'ipni', '123')).toEqual(['ipni:123']);
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

describe('gbifCountUrl', () => {
  test('contains the count path, limit=0, and the checklistKey + taxonKey values', () => {
    const url = gbifCountUrl('6W3C4', UUID);
    expect(url).toContain('/v1/occurrence/search');
    expect(url).toContain('limit=0');
    expect(url).toContain(`checklistKey=${UUID}`);
    expect(url).toContain('taxonKey=6W3C4');
    expect(url.startsWith('https://api.gbif.org')).toBe(true);
  });
  test('url-encodes the taxonKey value', () => {
    expect(gbifCountUrl('a b', UUID)).toContain('taxonKey=a%20b');
  });
});

describe('areaGeojsonUrl', () => {
  test('builds the exact gazetteer:areaId vocab URL', () => {
    expect(areaGeojsonUrl('tdwg', 'AB')).toBe(
      'https://api.checklistbank.org/vocab/area/tdwg:AB',
    );
  });
  test('url-encodes a special character in the areaId segment', () => {
    expect(areaGeojsonUrl('tdwg', 'A/B')).toContain('A%2FB');
    expect(areaGeojsonUrl('tdwg', 'A/B')).not.toContain('A/B');
  });
});
