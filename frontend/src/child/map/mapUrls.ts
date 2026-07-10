// Pure URL/id helpers for the distribution map. Kept free of maplibre-gl so they can be
// unit-tested in jsdom (no WebGL) and reused by other views (e.g. Task 8 match-to-COL).

// Extract the COL id from a usage's alternativeId list by stripping a case-insensitive `col:`
// prefix. Returns the first match, or null when none is present.
export function colIdFrom(alternativeId: string[] | null | undefined): string | null {
  if (!alternativeId) return null;
  for (const id of alternativeId) {
    const m = /^col:(.+)$/i.exec(id);
    if (m) return m[1];
  }
  return null;
}

// GBIF v2 occurrence-density raster tile template. Keeps the literal `{z}/{x}/{y}` placeholders
// (maplibre substitutes them per tile); only the dynamic query values are URL-encoded.
export function gbifTileUrl(colId: string, checklistKey: string): string {
  const params = [
    'srs=EPSG:3857',
    'style=iNaturalist.poly',
    'bin=hex',
    'hexPerTile=64',
    'hasCoordinate=true',
    'hasGeospatialIssue=false',
    'occurrenceStatus=PRESENT',
    `checklistKey=${encodeURIComponent(checklistKey)}`,
    `taxonKey=${encodeURIComponent(colId)}`,
  ].join('&');
  return `https://api.gbif.org/v2/map/occurrence/density/{z}/{x}/{y}@1x.png?${params}`;
}

// ChecklistBank gazetteer area GeoJSON endpoint for a coded area (e.g. tdwg:AB).
export function areaGeojsonUrl(gazetteer: string, areaId: string): string {
  return `https://api.checklistbank.org/vocab/area/${gazetteer}:${areaId}`;
}
