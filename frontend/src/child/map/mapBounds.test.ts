import { expect, test } from 'vitest';
import type { Feature } from 'geojson';
import { boundsOfFeatures } from './mapBounds';

const polygon = (coords: number[][]): Feature => ({
  type: 'Feature',
  properties: {},
  geometry: { type: 'Polygon', coordinates: [coords] },
});
const point = (lng: number, lat: number): Feature => ({
  type: 'Feature',
  properties: {},
  geometry: { type: 'Point', coordinates: [lng, lat] },
});

test('returns null for no features (keeps the default world view)', () => {
  expect(boundsOfFeatures([])).toBeNull();
});

test('computes the bbox of a single polygon as [[w,s],[e,n]]', () => {
  const f = polygon([
    [10, 40],
    [20, 40],
    [20, 50],
    [10, 50],
    [10, 40],
  ]);
  expect(boundsOfFeatures([f])).toEqual([
    [10, 40],
    [20, 50],
  ]);
});

test('unions polygon + point coordinates across features', () => {
  const bounds = boundsOfFeatures([
    polygon([
      [10, 40],
      [20, 50],
      [10, 40],
    ]),
    point(-5, 60),
  ]);
  expect(bounds).toEqual([
    [-5, 40],
    [20, 60],
  ]);
});

test('a single point yields a degenerate (min == max) bbox', () => {
  expect(boundsOfFeatures([point(8, 47)])).toEqual([
    [8, 47],
    [8, 47],
  ]);
});

test('ignores features whose geometry has no coordinates', () => {
  const empty: Feature = { type: 'Feature', properties: {}, geometry: null as never };
  const bounds = boundsOfFeatures([empty, point(1, 2)]);
  expect(bounds).toEqual([
    [1, 2],
    [1, 2],
  ]);
});
