import { Select } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';

export interface Option {
  value: string;
  label: string;
}

export interface EntitySelectProps {
  value: string | null;
  onChange: (value: string | null) => void;
  // Loads the options matching what the user typed (e.g. the project's usages or references),
  // searched on the server -- q is '' before anything is typed. Called again (debounced) as the
  // search text changes; queryKey is extended with q.
  load: (q: string) => Promise<Option[]>;
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
  const [search, setSearch] = useState('');
  // the option the user picked, so it keeps rendering once a new search no longer returns it
  const [picked, setPicked] = useState<Option | null>(null);
  const [debounced] = useDebouncedValue(search.trim(), 250);
  // once an option is picked Mantine shows its label as the search text -- list everything again
  // rather than searching for that label
  const selectedLabel = [picked, current].find((o) => o && o.value === value)?.label;
  const q = debounced === selectedLabel ? '' : debounced;
  const { data, isFetching } = useQuery({
    queryKey: [...queryKey, q],
    queryFn: () => load(q),
    placeholderData: keepPreviousData,
  });
  const merged = [...(data ?? [])];
  for (const o of [picked, current]) {
    if (o && o.value === value && !merged.some((m) => m.value === o.value)) merged.unshift(o);
  }
  return (
    <Select
      label={label}
      placeholder={placeholder ?? 'Search…'}
      searchable
      clearable
      data={merged}
      value={value}
      onChange={(v, option) => {
        setPicked(v ? option : null);
        onChange(v);
      }}
      searchValue={search}
      onSearchChange={setSearch}
      // the server already filtered; don't hide matches whose label lacks the typed text
      filter={({ options }) => options}
      nothingFoundMessage={isFetching ? 'Searching…' : 'No matches'}
    />
  );
}
