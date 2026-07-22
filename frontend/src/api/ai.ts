import { api } from './client';

// Mirrors AiConfigResponse: whether AI curation is usable for this project (a configured provider
// WITH a backend key) plus the resolved provider/model. Never carries a key.
export interface AiConfig {
  available: boolean;
  provider: string | null;
  model: string | null;
}

// Mirrors the backend AiSuggestionSet cards. References that resolved against Crossref/DataCite are
// verified=true; unresolvable ones are dropped server-side. Other categories are AI-suggested and
// must be reviewed before accepting.
export interface AiReferenceCard {
  doi: string;
  citation: string | null;
  verified: boolean;
}

export interface AiSynonymCard {
  scientificName: string;
  authorship: string | null;
  nomStatus: string | null;
  reference: AiReferenceCard | null;
}

export interface AiVernacularCard {
  name: string;
  language: string | null;
}

export interface AiDistributionCard {
  area: string;
}

export interface AiSuggestionSet {
  provider: string;
  model: string;
  synonyms: AiSynonymCard[];
  vernacularNames: AiVernacularCard[];
  distributions: AiDistributionCard[];
  descriptions: string[];
  references: AiReferenceCard[];
  etymology: string | null;
}

export function getAiConfig(pid: number): Promise<AiConfig> {
  return api<AiConfig>(`/api/projects/${pid}/ai/config`);
}

// POST -- triggers a live LLM call server-side, so this can take a few seconds.
export function requestSuggestions(pid: number, usageId: number): Promise<AiSuggestionSet> {
  return api<AiSuggestionSet>(`/api/projects/${pid}/usages/${usageId}/ai/suggest`, {
    method: 'POST',
  });
}
