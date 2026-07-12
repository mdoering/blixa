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
  // Lower-case CSL style id (e.g. "apa", "chicago") used to render generated citations for this
  // project's references -- see CslFormatter.STYLE on the backend. Null on legacy/uninitialized
  // projects; the UI defaults to 'apa' when seeding the metadata form.
  cslStyle: string | null;
}

export interface IdentifierScope {
  scope: string;
  datasetKey?: string | null;
}

// One entry from the backend's ColDP identifier-scope vocab (GET /api/coldp/id-scopes, see
// IdScopeController/IdScopeService): a CURIE prefix from the CLB `identifier-scope` vocab, plus
// its human title and resolver base `link` (e.g. "ipni" -> "https://www.ipni.org"). Distinct from
// IdentifierScope above, which is a project's own per-scope form-field configuration.
export interface IdScope {
  scope: string;
  title: string | null;
  link: string | null;
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
  cslStyle?: string;
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

// Mirrors backend life.catalogue.api.model.CslName -- the structured CSL-JSON name parts that
// replaced the old free-text `author`/`editor` strings (see ReferenceResponse.author|editor).
// `dropping-particle`/`non-dropping-particle` are declared with their literal CSL-JSON wire keys
// (the Java model puts an explicit @JsonProperty("dropping-particle") etc. on those two fields
// only -- every other field is plain camelCase) so a round-trip through JSON.stringify/parse (see
// api/client.ts's `api()`, which does no field renaming) doesn't silently drop them. The editor
// UI (CslNameEditor) only exposes family/given/literal/isInstitution; the particle/suffix fields
// still round-trip untouched via object spreads wherever a name is edited.
export interface CslName {
  family?: string;
  given?: string;
  'dropping-particle'?: string;
  'non-dropping-particle'?: string;
  suffix?: string;
  isInstitution?: boolean;
  literal?: string;
}

// Mirrors backend IssueResponse. severity/status are lowercase API strings.
// Mirrors backend ReferenceResponse (writable fields + id/version). All strings are nullable.
export interface Reference {
  id: number;
  citation: string | null;
  // Whether `citation` was hand-edited (vs auto-rendered from the structured fields) -- see
  // ReferenceService's citationManual handling. Not yet surfaced as an editable control (Task 6
  // makes the Citation field read-only for structured refs); the form just carries it through.
  citationManual: boolean;
  type: string | null;
  author: CslName[] | null;
  editor: CslName[] | null;
  title: string | null;
  containerTitle: string | null;
  containerTitleShort: string | null;
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
  citationManual?: boolean;
  type?: string;
  author?: CslName[];
  editor?: CslName[];
  title?: string;
  containerTitle?: string;
  containerTitleShort?: string;
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
