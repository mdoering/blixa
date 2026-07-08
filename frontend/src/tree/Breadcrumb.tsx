import { Breadcrumbs, Loader, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getPath } from '../api/tree';

export interface BreadcrumbProps {
  pid: number;
  selectedId: number;
}

// The path endpoint returns the ancestor chain root-first, including the selected node itself
// as the last entry.
export default function Breadcrumb({ pid, selectedId }: BreadcrumbProps) {
  const { data: path, isLoading } = useQuery({
    queryKey: ['treePath', pid, selectedId],
    queryFn: () => getPath(pid, selectedId),
  });

  if (isLoading) return <Loader size="xs" />;

  return (
    <Breadcrumbs separator="›">
      {(path ?? []).map((node, i) => (
        <Text key={node.id} size="sm" fw={i === (path ?? []).length - 1 ? 600 : 400}>
          {node.scientificName}
        </Text>
      ))}
    </Breadcrumbs>
  );
}
