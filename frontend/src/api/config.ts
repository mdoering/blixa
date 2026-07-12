import { api } from './client';

export interface AppConfig {
  orcidEnabled: boolean;
}

export function getConfig(): Promise<AppConfig> {
  return api<AppConfig>('/api/config');
}
