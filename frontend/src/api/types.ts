export type Role = 'owner' | 'editor' | 'reviewer' | 'viewer';

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
}

// Mirrors backend NameUsageResponse (see backend/.../name/dto/NameUsageResponse.java): the
// API-writable fields plus the parser-derived atomized name parts / nameType / parseState, the
// computed formattedName, and the synonym_accepted link ids.
export interface NameUsage {
  id: number;
  parentId: number | null;
  status: string | null;
  namePhrase: string | null;
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
  link: string | null;
  remarks: string | null;
  formattedName: string | null;
  acceptedParentIds: number[] | null;
  synonymIds: number[] | null;
  version: number;
}

// Mirrors backend UpdateNameUsageRequest: a full replace of every writable field, NOT a partial
// patch -- callers must carry over the loaded usage's current values for fields not exposed in
// the edit form (parentId, namePhrase, publishedInReferenceId, gender, extinct, environment,
// temporalRangeStart/End, remarks), or those fields would be wiped out on save. `version` is the
// loaded usage's version, for optimistic locking (a stale version -> 409).
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
  link?: string;
  remarks?: string;
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
}
