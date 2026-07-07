export type Role = 'owner' | 'editor' | 'reviewer' | 'viewer';

export interface Me {
  id: number;
  username: string;
  orcid: string;
  displayName: string;
}

export interface Project {
  id: number;
  slug: string;
  title: string;
  alias: string | null;
  description: string | null;
  nomCode: string | null;
  license: string | null;
  version: string | null;
  issued: string | null;
  geographicScope: string | null;
  taxonomicScope: string | null;
  doi: string | null;
  role: Role;
}

export interface Member {
  userId: number;
  username: string;
  role: Role;
}

export interface CreateProjectPayload {
  slug: string;
  title: string;
  nomCode?: string;
}

export interface UpdateMetadataPayload {
  title: string;
  alias?: string;
  description?: string;
  nomCode?: string;
  license?: string;
  version?: string;
  issued?: string;
  geographicScope?: string;
  taxonomicScope?: string;
  doi?: string;
}
