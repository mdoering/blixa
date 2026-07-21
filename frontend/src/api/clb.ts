import { api } from './client';

// --- URL parsing (client-side mirror of the backend's ClbTaxonUrl.parse) --------------------
//
// Purely for instant feedback while the user is typing/pasting a URL -- the backend's own
// `resolve` endpoint (resolveClbTaxon below) is what actually confirms the (datasetKey, taxonId)
// pair is real and fetches the name to show. Deliberately lenient, same as the backend: any
// http(s) scheme, any subdomain, a trailing slash, and a query string/#fragment are all tolerated.

// Same default COL release dataset alias ClbTaxonUrl.COL_PORTAL_DATASET / the backend's
// coldp.col.match-dataset use -- every catalogueoflife.org/data/taxon/{id} link resolves against it.
const COL_PORTAL_DATASET = '3LXR';

// group(1) = dataset key (numeric key or alias), group(2) = taxon/usage id -- "taxon" and
// "nameusage" both identify a single usage by id and resolve to the exact same pair.
const CLB_DATASET_USAGE =
  /^https?:\/\/(?:[\w-]+\.)*checklistbank\.org\/dataset\/([^/?#]+)\/(?:taxon|nameusage)\/([^/?#]+)\/?(?:[?#].*)?$/i;

// group(1) = taxon id; the portal always serves the current COL release, so the dataset key is
// fixed to COL_PORTAL_DATASET rather than captured from the URL.
const COL_PORTAL_TAXON = /^https?:\/\/(?:[\w-]+\.)*catalogueoflife\.org\/data\/taxon\/([^/?#]+)\/?(?:[?#].*)?$/i;

export interface ClbRef {
  datasetKey: string;
  taxonId: string;
}

export function parseClbUrl(url: string): ClbRef | null {
  const s = url.trim();
  if (!s) return null;
  let m = CLB_DATASET_USAGE.exec(s);
  if (m) return { datasetKey: m[1], taxonId: m[2] };
  m = COL_PORTAL_TAXON.exec(s);
  if (m) return { datasetKey: COL_PORTAL_DATASET, taxonId: m[1] };
  return null;
}

// --- suggest proxy (mirrors ClbImportClient's own hit shapes) --------------------------------

export interface ClbDatasetHit {
  key: string;
  title: string | null;
  alias: string | null;
}

export interface ClbUsageHit {
  id: string;
  scientificName: string | null;
  rank: string | null;
  status: string | null;
}

export interface ClbResolvedTaxon {
  datasetKey: string;
  taxonId: string;
  scientificName: string | null;
  rank: string | null;
  datasetTitle: string | null;
}

export function searchClbDatasets(q: string): Promise<ClbDatasetHit[]> {
  const search = new URLSearchParams();
  if (q) search.set('q', q);
  return api<ClbDatasetHit[]>(`/api/clb/datasets?${search.toString()}`);
}

// Resolve CLB dataset keys to their human-readable label (alias, else title). Server-side cached;
// an unresolvable key maps to itself. Returns { key: label } for every non-blank key requested.
export function getClbDatasetLabels(keys: string[]): Promise<Record<string, string>> {
  const search = new URLSearchParams();
  for (const k of keys) if (k) search.append('key', k);
  return api<Record<string, string>>(`/api/clb/dataset-labels?${search.toString()}`);
}

export function searchClbUsages(datasetKey: string, q: string, rank?: string): Promise<ClbUsageHit[]> {
  const search = new URLSearchParams();
  if (q) search.set('q', q);
  if (rank) search.set('rank', rank);
  return api<ClbUsageHit[]>(`/api/clb/${encodeURIComponent(datasetKey)}/usages?${search.toString()}`);
}

export function resolveClbTaxon(datasetKey: string, taxonId: string): Promise<ClbResolvedTaxon> {
  return api<ClbResolvedTaxon>(
    `/api/clb/${encodeURIComponent(datasetKey)}/resolve/${encodeURIComponent(taxonId)}`,
  );
}

// --- comparison (mirrors ClbComparison / ClbGlobalUsageHit) ----------------------------------

export interface ClbRankName {
  rank: string | null;
  name: string | null;
}

export interface ClbComparisonSynonym {
  scientificName: string | null;
  authorship: string | null;
  status: string | null;
}

export interface ClbComparison {
  datasetKey: string;
  datasetTitle: string | null;
  taxonId: string;
  link: string;
  scientificName: string | null;
  authorship: string | null;
  rank: string | null;
  status: string | null;
  classification: ClbRankName[];
  synonyms: ClbComparisonSynonym[];
}

export interface ClbGlobalUsageHit {
  datasetKey: string;
  datasetTitle: string | null;
  id: string;
  scientificName: string | null;
  authorship: string | null;
  rank: string | null;
  status: string | null;
}

// Global name search across all CLB datasets (each hit carries its dataset).
export function searchClbAllDatasets(q: string, rank?: string): Promise<ClbGlobalUsageHit[]> {
  const search = new URLSearchParams();
  if (q) search.set('q', q);
  if (rank) search.set('rank', rank);
  return api<ClbGlobalUsageHit[]>(`/api/clb/usages?${search.toString()}`);
}

export function compareClbTaxon(datasetKey: string, taxonId: string): Promise<ClbComparison> {
  return api<ClbComparison>(
    `/api/clb/${encodeURIComponent(datasetKey)}/compare/${encodeURIComponent(taxonId)}`,
  );
}

// --- the import call itself (mirrors ClbImportRequest/ClbImportSummary) ----------------------

export type ClbImportMode = 'TAXON_SUBTREE' | 'CHILDREN_ONLY' | 'UPDATE_FOCAL';

// ClbImportRequest.entityTypes' own vocabulary -- see that record's javadoc.
export type ClbEntityType =
  | 'synonyms'
  | 'vernacular'
  | 'distribution'
  | 'typeMaterial'
  | 'media'
  | 'estimate'
  | 'property'
  | 'nameRelation';

export interface ClbImportPayload {
  datasetKey: string;
  sourceTaxonId: string;
  mode: ClbImportMode;
  // undefined/empty means "include everything" for TAXON_SUBTREE/CHILDREN_ONLY but "attach
  // nothing" for UPDATE_FOCAL -- see ClbImportRequest's javadoc; the modal only ever sends this
  // explicitly for UPDATE_FOCAL.
  entityTypes?: ClbEntityType[];
}

export interface ClbImportIssue {
  entity: string;
  sourceId: string;
  message: string;
}

export interface ClbImportSummary {
  nameUsages: number;
  synonyms: number;
  references: number;
  children: Record<string, number>;
  issues: ClbImportIssue[];
}

export function clbImport(
  pid: number,
  focalId: number,
  payload: ClbImportPayload,
): Promise<ClbImportSummary> {
  return api<ClbImportSummary>(`/api/projects/${pid}/usages/${focalId}/clb-import`, {
    method: 'POST',
    json: payload,
  });
}
