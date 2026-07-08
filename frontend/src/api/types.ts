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
