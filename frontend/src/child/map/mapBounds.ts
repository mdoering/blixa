import type { Feature, Position } from 'geojson';

// [[west, south], [east, north]] — a maplibre LngLatBoundsLike ([sw, ne]).
export type Bounds = [[number, number], [number, number]];

// Walk any GeoJSON coordinate nesting (Point → Position, LineString/MultiPoint → Position[],
// Polygon/MultiLineString → Position[][], MultiPolygon → Position[][][]) down to the leaf
// [lng, lat] pairs. A Position is an array whose first element is a number; anything else is a
// deeper nesting to recurse into.
function eachPosition(coords: unknown, cb: (lng: number, lat: number) => void): void {
  if (!Array.isArray(coords)) return;
  if (typeof coords[0] === 'number') {
    const [lng, lat] = coords as Position;
    if (typeof lat === 'number') cb(lng, lat);
    return;
  }
  for (const c of coords) eachPosition(c, cb);
}

// Bounding box over every coordinate of every feature (all geometry types, incl. GeometryCollection),
// as [[w,s],[e,n]] — or null when there are no coordinates to fit. Used to fit the map to the taxon's
// rendered distribution polygons + type-specimen points instead of opening on the whole world. Kept
// free of maplibre-gl so it unit-tests in jsdom.
export function boundsOfFeatures(features: Feature[]): Bounds | null {
  let west = Infinity;
  let south = Infinity;
  let east = -Infinity;
  let north = -Infinity;
  const extend = (lng: number, lat: number) => {
    if (lng < west) west = lng;
    if (lng > east) east = lng;
    if (lat < south) south = lat;
    if (lat > north) north = lat;
  };
  for (const f of features) {
    const g = f.geometry;
    if (!g) continue;
    if (g.type === 'GeometryCollection') {
      for (const sub of g.geometries) eachPosition('coordinates' in sub ? sub.coordinates : [], extend);
    } else {
      eachPosition(g.coordinates, extend);
    }
  }
  if (west === Infinity) return null;
  return [
    [west, south],
    [east, north],
  ];
}
