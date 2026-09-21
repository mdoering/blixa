import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getCountries, getLanguages } from '../api/vocab';
import type { Option } from '../child/EntitySelect';

// The CLB language / country vocabularies, fetched once per session (they don't change while the
// app runs) and shared by every component through the query cache.
const ONCE = { staleTime: Infinity, gcTime: Infinity, retry: 1 } as const;

function useLanguages() {
  return useQuery({ queryKey: ['vocab', 'languages'], queryFn: getLanguages, ...ONCE });
}

function useCountries() {
  return useQuery({ queryKey: ['vocab', 'countries'], queryFn: getCountries, ...ONCE });
}

// code -> English name; unknown codes (or while loading) come back unchanged.
export function useLanguageName(): (code: string | null | undefined) => string {
  const { data } = useLanguages();
  return useMemo(() => (code) => (code ? data?.[code.toLowerCase()] ?? code : ''), [data]);
}

export function useCountryName(): (code: string | null | undefined) => string {
  const { data } = useCountries();
  return useMemo(() => (code) => (code ? data?.[code.toUpperCase()] ?? code : ''), [data]);
}

// Select options ("Dutch (nld)"), sorted by name.
export function useLanguageOptions(): Option[] {
  const { data } = useLanguages();
  return useMemo(
    () =>
      Object.entries(data ?? {})
        .map(([value, name]) => ({ value, label: `${name} (${value})` }))
        .sort((a, b) => a.label.localeCompare(b.label)),
    [data],
  );
}

// Countries are stored by their 2-letter code, so only those are offered ("Germany (DE)").
export function useCountryOptions(): Option[] {
  const { data } = useCountries();
  return useMemo(
    () =>
      Object.entries(data ?? {})
        .filter(([code]) => code.length === 2)
        .map(([value, name]) => ({ value, label: `${name} (${value})` }))
        .sort((a, b) => a.label.localeCompare(b.label)),
    [data],
  );
}
