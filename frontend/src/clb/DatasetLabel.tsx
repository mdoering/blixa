import { Text, Tooltip } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getClbDatasetLabels } from '../api/clb';

const MAX_CHARS = 48;

export interface DatasetLabelProps {
  datasetKey: string;
  size?: string;
  c?: string;
  // Characters shown before abbreviating with "…" (default 48). Aliases are short; titles can be huge.
  maxChars?: number;
}

// Shows a CLB dataset's human-readable label (its alias, else its title) in place of the opaque
// datasetKey, resolved and cached via /api/clb/dataset-labels (per-key react-query, deduped across
// the app). Long labels are abbreviated, with a tooltip carrying the full label + the underlying
// key. Falls back to the key itself while loading or when the dataset can't be resolved.
export default function DatasetLabel({ datasetKey, size = 'sm', c, maxChars = MAX_CHARS }: DatasetLabelProps) {
  const { data } = useQuery({
    queryKey: ['clbDatasetLabel', datasetKey],
    queryFn: () => getClbDatasetLabels([datasetKey]),
    enabled: !!datasetKey,
    staleTime: Infinity, // labels change rarely and the backend caches too
  });
  const label = data?.[datasetKey] ?? datasetKey;
  const abbreviated = label.length > maxChars ? `${label.slice(0, maxChars - 1)}…` : label;
  const tooltip = label === datasetKey ? datasetKey : `${label} · ${datasetKey}`;
  return (
    <Tooltip label={tooltip} withArrow multiline maw={360} openDelay={200}>
      <Text span size={size} c={c}>
        {abbreviated}
      </Text>
    </Tooltip>
  );
}
