import { api } from './client';

// ISO 639-3 code -> English language name, from CLB's /vocab/language (proxied + cached server-side).
export function getLanguages(): Promise<Record<string, string>> {
  return api<Record<string, string>>('/api/clb/vocab/languages');
}

// ISO 3166 code (2- and 3-letter, upper case) -> English country name, from CLB's /vocab/country.
export function getCountries(): Promise<Record<string, string>> {
  return api<Record<string, string>>('/api/clb/vocab/countries');
}
