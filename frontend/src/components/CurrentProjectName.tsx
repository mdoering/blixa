import { Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getProject } from '../api/projects';

export interface CurrentProjectNameProps {
  projectId: number | null;
}

// Header context: the active project's title, shown read-only next to the brand (nothing when not
// inside a project). Selecting a project happens on the Projects list page, not here. Shares the
// ['project', id] query key with ProjectLayout, so it adds no extra request.
export default function CurrentProjectName({ projectId }: CurrentProjectNameProps) {
  const { data } = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => getProject(projectId as number),
    enabled: projectId != null,
  });
  if (projectId == null || !data) return null;
  return (
    <Text fw={500} truncate maw={360}>
      {data.title}
    </Text>
  );
}
