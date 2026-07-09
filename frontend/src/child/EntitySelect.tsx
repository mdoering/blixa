import { Select } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';

export interface Option {
  value: string;
  label: string;
}

export interface EntitySelectProps {
  value: string | null;
  onChange: (value: string | null) => void;
  // Loads the pickable options (e.g. the project's usages or references). Client-side searchable
  // among the loaded set — good for typical projects; server-side search is a later refinement.
  load: () => Promise<Option[]>;
  queryKey: unknown[];
  label?: string;
  placeholder?: string;
  // Ensures the currently-selected option renders even if it isn't in the freshly-loaded page.
  current?: Option | null;
}

export default function EntitySelect({
  value,
  onChange,
  load,
  queryKey,
  label,
  placeholder,
  current,
}: EntitySelectProps) {
  const { data } = useQuery({ queryKey, queryFn: load });
  const options = data ?? [];
  const merged =
    current && !options.some((o) => o.value === current.value) ? [current, ...options] : options;
  return (
    <Select
      label={label}
      placeholder={placeholder ?? 'Search…'}
      searchable
      clearable
      data={merged}
      value={value}
      onChange={onChange}
      nothingFoundMessage="No matches"
    />
  );
}
