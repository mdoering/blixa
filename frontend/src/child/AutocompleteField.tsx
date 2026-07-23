import { Autocomplete } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { type Option } from './EntitySelect';

export interface AutocompleteFieldProps {
  value: string;
  onChange: (value: string) => void;
  // Loads the suggestion set (e.g. the project's property keys). Free-text: typing a value not in
  // the list is allowed, so new keys can still be introduced -- this only offers existing ones.
  load: () => Promise<Option[]>;
  queryKey: unknown[];
  label?: string;
  placeholder?: string;
}

// A free-text input with a suggestion dropdown, mirroring EntitySelect but NOT constrained to the
// loaded set (unlike Select). Used for the taxon Property key field, sourced from the project's
// standard property keys (getPropertyKeys) so spellings stay consistent while new keys remain typeable.
export default function AutocompleteField({
  value,
  onChange,
  load,
  queryKey,
  label,
  placeholder,
}: AutocompleteFieldProps) {
  const { data } = useQuery({ queryKey, queryFn: load });
  const options = (data ?? []).map((o) => o.value);
  return (
    <Autocomplete
      label={label}
      placeholder={placeholder}
      data={options}
      value={value}
      onChange={onChange}
    />
  );
}
