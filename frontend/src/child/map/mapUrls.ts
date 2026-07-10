// Pure URL/id helpers for the distribution map. Kept free of maplibre-gl so they can be
// unit-tested in jsdom (no WebGL) and reused by other views (e.g. Task 8 match-to-COL).

// COL GBIF checklist UUID — matches backend `coldp.col.gbif-checklist-key`.
export const GBIF_CHECKLIST_KEY = '7ddf754f-d193-4cc9-b351-99906754a03b';

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

// Merges a picked COL id into a usage's alternativeId list: drops any existing (case-insensitive)
// `col:` entry, then appends `col:<colId>`. Mirrors the backend's mergeColId (see
// NameUsageService.setIdentifiers) so the "match to COL" write path stays a full replace that
// carries over non-col ids (e.g. `tsn:1`) instead of clobbering them.
export function withColId(alternativeId: string[], colId: string): string[] {
  return [...alternativeId.filter((id) => !/^col:/i.test(id)), `col:${colId}`];
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

// GBIF occurrence-search preflight: how many occurrences would the density raster (gbifTileUrl)
// actually show for this COL id? `limit=0` skips the record payload — we only want `count`.
// Mirrors gbifTileUrl's filters so the count reflects the same set of occurrences.
export function gbifCountUrl(colId: string, checklistKey: string): string {
  const params = [
    `checklistKey=${encodeURIComponent(checklistKey)}`,
    `taxonKey=${encodeURIComponent(colId)}`,
    'hasCoordinate=true',
    'hasGeospatialIssue=false',
    'occurrenceStatus=PRESENT',
    'limit=0',
  ].join('&');
  return `https://api.gbif.org/v1/occurrence/search?${params}`;
}

// ChecklistBank gazetteer area GeoJSON endpoint for a coded area (e.g. tdwg:AB). gazetteer/areaId
// are DB-sourced free text, so both are URL-encoded before interpolation (mirrors gbifTileUrl above).
export function areaGeojsonUrl(gazetteer: string, areaId: string): string {
  return `https://api.checklistbank.org/vocab/area/${encodeURIComponent(gazetteer)}:${encodeURIComponent(areaId)}`;
}

// Fetches the GBIF preflight occurrence count (see gbifCountUrl) for a COL id and parses out
// `count`. Colocated with the URL builders so DistributionMapPanel's queryFn stays a plain
// `api/*`-style wrapper call instead of an inline fetch+parse.
export async function getGbifCount(colId: string): Promise<number> {
  const res = await fetch(gbifCountUrl(colId, GBIF_CHECKLIST_KEY));
  const json = (await res.json()) as { count: number };
  return json.count;
}
