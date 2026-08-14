import { useQuery } from '@tanstack/react-query';
import { api } from './client';

export interface AppConfig {
  orcidEnabled: boolean;
}

export function getConfig(): Promise<AppConfig> {
  return api<AppConfig>('/api/config');
}

// Shared, cache-forever config query. Fetched once and reused by LoginPage, PublicLayout and
// SignInRedirect to decide whether sign-in goes straight to ORCID or to the local /signin form.
export function useConfig() {
  return useQuery({ queryKey: ['config'], queryFn: getConfig, staleTime: Infinity });
}
