export type Role = 'owner' | 'editor' | 'viewer';

export interface Me {
  id: number;
  username: string;
  orcid: string;
  displayName: string;
}

export interface Project {
  id: number;
  title: string;
  alias: string | null;
  description: string | null;
  nomCode: string | null;
  license: string | null;
  geographicScope: string | null;
  taxonomicScope: string | null;
  role: Role;
  gbifOccurrenceLayer: boolean;
  // Whether the project (and its READY releases) are visible on the public landing page /
  // portal-facing read API (see PUT /api/projects/{id}/public, owner-only).
  public: boolean;
  // Which alternative_id CURIE scopes (e.g. "ipni", "gbif") the taxon Details form renders a
  // real per-scope identifier field for -- null/absent on projects that haven't set any. Each
  // scope carries an optional CLB dataset key -- a scope is matchable (eligible for "match all
  // identifiers") iff datasetKey is set.
  identifierScopes: IdentifierScope[] | null;
}

export interface IdentifierScope {
  scope: string;
  datasetKey?: string | null;
}

export interface Member {
  userId: number;
  username: string;
  role: Role;
}

export interface CreateProjectPayload {
  title: string;
  nomCode?: string;
}

export interface TreeNode {
  id: number;
  scientificName: string | null;
  authorship: string | null;
  rank: string | null;
  status: string | null;
  ordinal: number | null;
  childCount: number;
}

export interface PathNode {
  id: number;
  scientificName: string | null;
  rank: string | null;
}

export interface UpdateMetadataPayload {
  title: string;
  alias?: string;
  description?: string;
  nomCode?: string;
  license?: string;
  geographicScope?: string;
  taxonomicScope?: string;
  gbifOccurrenceLayer?: boolean;
  identifierScopes?: IdentifierScope[];
}

// Mirrors backend NameUsageResponse (see backend/.../name/dto/NameUsageResponse.java): the
// API-writable fields plus the parser-derived atomized name parts / nameType / parseState, the
// computed formattedName, and the synonym_accepted link ids.
export interface NameUsage {
  id: number;
  parentId: number | null;
  alternativeId?: string[];
  status: string | null;
  namePhrase: string | null;
  referenceId: number[] | null;
  extinct: boolean | null;
  environment: string[] | null;
  temporalRangeStart: string | null;
  temporalRangeEnd: string | null;
  scientificName: string | null;
  authorship: string | null;
  rank: string | null;
  uninomial: string | null;
  genus: string | null;
  infragenericEpithet: string | null;
  specificEpithet: string | null;
  infraspecificEpithet: string | null;
  cultivarEpithet: string | null;
  notho: string | null;
  combinationAuthorship: string | null;
  combinationExAuthorship: string | null;
  combinationAuthorshipYear: string | null;
  basionymAuthorship: string | null;
  basionymExAuthorship: string | null;
  basionymAuthorshipYear: string | null;
  sanctioningAuthor: string | null;
  nomStatus: string | null;
  publishedInReferenceId: number | null;
  publishedInYear: number | null;
  publishedInPage: string | null;
  publishedInPageLink: string | null;
  gender: string | null;
  etymology: string | null;
  nameType: string | null;
  parseState: string | null;
  remarks: string | null;
  formattedName: string | null;
  acceptedParentIds: number[] | null;
  synonymIds: number[] | null;
  version: number;
}

// Mirrors backend UpdateNameUsageRequest: a full replace of every writable field, NOT a partial
// patch -- callers must carry over the loaded usage's current values for fields not exposed in
// the edit form (parentId, namePhrase, gender, extinct, environment, temporalRangeStart/End,
// alternativeId -- a later per-scope-identifier form will populate alternativeId directly), or
// those fields would be wiped out on save. `version` is the loaded usage's version, for
// optimistic locking (a stale version -> 409).
export interface UpdateUsagePayload {
  scientificName: string;
  authorship?: string;
  rank: string;
  status: string;
  parentId?: number;
  namePhrase?: string;
  nomStatus?: string;
  publishedInReferenceId?: number;
  publishedInYear?: number;
  publishedInPage?: string;
  publishedInPageLink?: string;
  gender?: string;
  extinct?: boolean;
  environment?: string[];
  temporalRangeStart?: string;
  temporalRangeEnd?: string;
  etymology?: string;
  remarks?: string;
  alternativeId?: string[];
  version: number;
}

// Mirrors backend CreateNameUsageRequest's writable subset used by the create flow (root/child/
// synonym). rank/status use the same wire form as elsewhere: rank is free-form but the backend's
// name parser re-renders it lower-case once parsed (see NameUsageService/ParsedNameMapping);
// status is the upper-case enum name (see UpdateUsagePayload / TaxonDetail's STATUS_OPTIONS).
export interface CreateUsagePayload {
  scientificName: string;
  authorship?: string;
  rank?: string;
  status: string;
  parentId?: number;
}

// Mirrors backend UsagePage: a page of name-usage search results. `total` counts ALL matches for
// the same q/rank/status filters, ignoring limit/offset -- lets the Names search table's
// mantine-react-table drive server-side pagination off a stable row count.
export interface UsagePage {
  items: NameUsage[];
  total: number;
}

// Mirrors backend IssueResponse. severity/status are lowercase API strings.
// Mirrors backend ReferenceResponse (writable fields + id/version). All strings are nullable.
export interface Reference {
  id: number;
  citation: string | null;
  type: string | null;
  author: string | null;
  editor: string | null;
  title: string | null;
  containerTitle: string | null;
  issued: string | null;
  volume: string | null;
  issue: string | null;
  page: string | null;
  publisher: string | null;
  doi: string | null;
  isbn: string | null;
  issn: string | null;
  link: string | null;
  accessed: string | null;
  remarks: string | null;
  version: number;
  // Public URL of the hosted PDF (coldp.pdf.base-url + filename), or null if none is attached --
  // see attachReferencePdf/removeReferencePdf in api/references.ts.
  pdfUrl: string | null;
}

// Mirrors backend CreateReferenceRequest (also the shape returned by resolve-doi as a preview).
export interface CreateRefPayload {
  citation?: string;
  type?: string;
  author?: string;
  editor?: string;
  title?: string;
  containerTitle?: string;
  issued?: string;
  volume?: string;
  issue?: string;
  page?: string;
  publisher?: string;
  doi?: string;
  isbn?: string;
  issn?: string;
  link?: string;
  accessed?: string;
  remarks?: string;
}

// Mirrors backend UpdateReferenceRequest (create fields + optimistic-lock version).
export interface UpdateRefPayload extends CreateRefPayload {
  version: number;
}

export interface Issue {
  id: number;
  entityType: string;
  entityId: number;
  rule: string;
  severity: string;
  message: string;
  status: string;
  context?: string | null;
  createdAt?: string;
  updatedAt?: string;
  reviewerId?: number | null;
  reviewerUsername?: string | null;
  reviewedAt?: string | null;
}

// Mirrors backend IssueSummaryResponse: a per-project rollup of issue counts.
export interface IssueSummary {
  total: number;
  byStatus: Record<string, number>;
  bySeverity: Record<string, number>;
}

// Mirrors backend Change (audit log row). `diff` is a raw JSON string.
export interface Change {
  id: number;
  userId: number;
  username: string | null;
  at: string;
  entityType: string;
  entityId: number;
  operation: string;
  diff: string;
  taskId: number | null;
}

// Mirrors backend TaskResponse (subset used by the History task filter).
export interface Task {
  id: number;
  title: string;
  status: string;
}

// Mirrors backend LockResponse: a soft lock on an entity (e.g. a name_usage), held by a user for
// a limited time (see api/locks.ts). `heldByMe` distinguishes the caller's own lock from someone
// else's; `taskId`/`taskTitle` are set when the lock was acquired as part of a task.
export interface Lock {
  id: number;
  entityType: string;
  entityId: number;
  userId: number;
  username: string;
  acquiredAt: string;
  expiresAt: string;
  heldByMe: boolean;
  taskId: number | null;
  taskTitle: string | null;
}
